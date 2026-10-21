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
        val outputs = mutableListOf<String>()
        audit.append(task.agentId, task.id, AuditType.TASK_STARTED, task.goal)
        emit(TaskEvent.Started(task.id, task.goal, task.steps.size))

        if (task.steps.size > task.budget.maxSteps) {
            fail(task, -1, "Plan has ${task.steps.size} steps; budget allows ${task.budget.maxSteps}", TaskStatus.FAILED, outputs)
            return@flow
        }

        try {
            for ((index, step) in task.steps.withIndex()) {
                audit.append(task.agentId, task.id, AuditType.STEP_STARTED, "#$index ${step.label}")
                emit(TaskEvent.StepStarted(task.id, index, step.label))

                val required = try {
                    step.requiredPermissions(tools)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    fail(task, index, "Cannot resolve permissions: ${e.message}", TaskStatus.FAILED, outputs)
                    return@flow
                }
                for (permission in required.sortedBy { it.ordinal }) {
                    if (!gate.authorize(task.agentId, task.id, permission, "#$index ${step.label}")) {
                        emit(TaskEvent.StepDenied(task.id, index, permission))
                        finish(task, TaskStatus.DENIED, outputs)
                        return@flow
                    }
                }

                val output = try {
                    withTimeout(task.budget.stepTimeoutMillis) { execute(step, task.goal, outputs) }
                } catch (e: TimeoutCancellationException) {
                    fail(task, index, "Timed out after ${task.budget.stepTimeoutMillis} ms", TaskStatus.TIMED_OUT, outputs)
                    return@flow
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(task, index, e.message ?: e::class.simpleName.orEmpty(), TaskStatus.FAILED, outputs)
                    return@flow
                }

                outputs += output
                audit.append(task.agentId, task.id, AuditType.STEP_SUCCEEDED, "#$index ${preview(output)}")
                emit(TaskEvent.StepSucceeded(task.id, index, output))
            }
            finish(task, TaskStatus.COMPLETED, outputs)
        } catch (e: CancellationException) {
            audit.append(task.agentId, task.id, AuditType.TASK_CANCELLED, "after ${outputs.size} step(s)")
            throw e
        }
    }

    private suspend fun execute(step: AgentStep, goal: String, outputs: List<String>): String = when (step) {
        is AgentStep.Generate -> models.generate(
            prompt = Templates.resolve(step.prompt, goal, outputs),
            system = step.system?.let { Templates.resolve(it, goal, outputs) },
            allowCloud = step.allowCloud,
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
        outputs: List<String>,
    ) {
        audit.append(task.agentId, task.id, AuditType.STEP_FAILED, "#$index $error")
        emit(TaskEvent.StepFailed(task.id, index, error))
        finish(task, status, outputs)
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<TaskEvent>.finish(
        task: AgentTask,
        status: TaskStatus,
        outputs: List<String>,
    ) {
        audit.append(task.agentId, task.id, AuditType.TASK_FINISHED, status.name)
        emit(TaskEvent.Finished(task.id, status, outputs.toList()))
    }

    private fun preview(text: String): String = text.replace('\n', ' ').let { if (it.length > 120) it.take(117) + "..." else it }
}
