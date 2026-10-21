package com.ntpx.alchemist.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

enum class TaskStatus { COMPLETED, DENIED, FAILED, TIMED_OUT }

sealed interface TaskEvent {
    val taskId: String

    data class Started(override val taskId: String, val goal: String, val stepCount: Int) : TaskEvent
    data class StepStarted(override val taskId: String, val index: Int, val label: String) : TaskEvent
    data class StepSucceeded(override val taskId: String, val index: Int, val output: String) : TaskEvent
    data class StepDenied(override val taskId: String, val index: Int, val permission: Permission) : TaskEvent
    data class StepFailed(override val taskId: String, val index: Int, val error: String) : TaskEvent
    data class Finished(override val taskId: String, val status: TaskStatus, val outputs: List<String>) : TaskEvent
}

/**
 * Executes [AgentTask]s step by step. Every step is permission-checked first and the
 * runtime fails closed: a denied permission, an error or a timeout ends the task.
 * Every transition is written to the [AuditLog]. Cancelling the collector cancels the task.
 */
class AgentRuntime(
    private val gate: PermissionGate,
    private val audit: AuditLog,
    private val models: ModelPort,
    private val tools: ToolPort,
    private val memory: MemoryPort,
) {
    fun run(task: AgentTask): Flow<TaskEvent> = flow {
        val run = RunState()
        audit.append(task.agentId, task.id, AuditType.TASK_STARTED, task.goal)
        // Everything after TASK_STARTED, including the first emit, is inside the try so that a
        // collector cancelling at any point still leaves a terminal TASK_CANCELLED record.
        try {
            emit(TaskEvent.Started(task.id, task.goal, task.steps.size))

            if (task.steps.size > task.budget.maxSteps) {
                fail(task, -1, "Plan has ${task.steps.size} steps; budget allows ${task.budget.maxSteps}", TaskStatus.FAILED, run)
                return@flow
            }

            for ((index, step) in task.steps.withIndex()) {
                audit.append(task.agentId, task.id, AuditType.STEP_STARTED, "#$index ${step.label}")
                emit(TaskEvent.StepStarted(task.id, index, step.label))

                // Route resolution, permission checks (including consent prompts) and execution
                // all share the step timeout so no part of a step can wait unbounded.
                val outcome = try {
                    withTimeout(task.budget.stepTimeoutMillis) {
                        val route = if (step is AgentStep.Generate) models.route(step.allowCloud) else null
                        val required = step.requiredPermissions(tools, route)
                        for (permission in required.sortedBy { it.ordinal }) {
                            if (!gate.authorize(task.agentId, task.id, permission, "#$index ${step.label}")) {
                                return@withTimeout StepOutcome.Denied(permission)
                            }
                        }
                        StepOutcome.Ok(execute(step, route, task.goal, run.outputs))
                    }
                } catch (e: TimeoutCancellationException) {
                    fail(task, index, "Timed out after ${task.budget.stepTimeoutMillis} ms", TaskStatus.TIMED_OUT, run)
                    return@flow
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(task, index, e.message ?: e::class.simpleName.orEmpty(), TaskStatus.FAILED, run)
                    return@flow
                }

                val output = when (outcome) {
                    is StepOutcome.Denied -> {
                        emit(TaskEvent.StepDenied(task.id, index, outcome.permission))
                        finish(task, TaskStatus.DENIED, run)
                        return@flow
                    }
                    is StepOutcome.Ok -> outcome.output
                }

                run.outputs += output
                audit.append(task.agentId, task.id, AuditType.STEP_SUCCEEDED, "#$index ${preview(output)}")
                emit(TaskEvent.StepSucceeded(task.id, index, output))
            }
            finish(task, TaskStatus.COMPLETED, run)
        } catch (e: CancellationException) {
            // A collector that stops while receiving Finished must not add a contradictory terminal record.
            if (!run.finished) audit.append(task.agentId, task.id, AuditType.TASK_CANCELLED, "after ${run.outputs.size} step(s)")
            throw e
        }
    }

    private class RunState {
        val outputs = mutableListOf<String>()
        var finished = false
    }

    private sealed interface StepOutcome {
        data class Ok(val output: String) : StepOutcome
        data class Denied(val permission: Permission) : StepOutcome
    }

    private suspend fun execute(step: AgentStep, route: ModelRoute?, goal: String, outputs: List<String>): String = when (step) {
        is AgentStep.Generate -> models.generate(
            route = requireNotNull(route),
            prompt = Templates.resolve(step.prompt, goal, outputs),
            system = step.system?.let { Templates.resolve(it, goal, outputs) },
        )
        is AgentStep.CallTool -> tools.call(
            step.tool,
            step.arguments.mapValues { (_, value) -> Templates.resolve(value, goal, outputs) },
        )
        is AgentStep.Recall -> {
            val hits = memory.recall(Templates.resolve(step.query, goal, outputs), step.limit.coerceIn(1, 50))
            if (hits.isEmpty()) "(no matching memories)" else hits.joinToString("\n") { "• $it" }
        }
        is AgentStep.Remember -> {
            val id = memory.remember(Templates.resolve(step.content, goal, outputs), step.tags)
            "Stored memory $id"
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<TaskEvent>.fail(
        task: AgentTask,
        index: Int,
        error: String,
        status: TaskStatus,
        run: RunState,
    ) {
        audit.append(task.agentId, task.id, AuditType.STEP_FAILED, "#$index $error")
        emit(TaskEvent.StepFailed(task.id, index, error))
        finish(task, status, run)
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<TaskEvent>.finish(
        task: AgentTask,
        status: TaskStatus,
        run: RunState,
    ) {
        audit.append(task.agentId, task.id, AuditType.TASK_FINISHED, status.name)
        run.finished = true
        emit(TaskEvent.Finished(task.id, status, run.outputs.toList()))
    }

    private fun preview(text: String): String = text.replace('\n', ' ').let { if (it.length > 120) it.take(117) + "..." else it }
}
