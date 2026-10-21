package com.ntpx.alchemist.agent

import java.util.UUID

/** Turns a goal into a concrete plan. Model-driven planners can replace this later. */
fun interface Planner {
    fun plan(agentId: String, goal: String): AgentTask
}

/**
 * Fixed four-step pipeline that exercises every port and permission class:
 * recall related memories → generate with that context → analyse the output with a tool → store a summary.
 */
class RecallGenerateRememberPlanner(
    private val analysisTool: String = "text.stats",
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : Planner {
    override fun plan(agentId: String, goal: String): AgentTask {
        val trimmed = goal.trim()
        require(trimmed.isNotEmpty()) { "Goal must not be blank" }
        return AgentTask(
            id = idFactory(),
            agentId = agentId,
            goal = trimmed,
            steps = listOf(
                AgentStep.Recall(query = "{{goal}}", label = "Recall related memories"),
                AgentStep.Generate(
                    system = "You are NTPX Alchemist. Use the context if relevant.",
                    prompt = "Goal: {{goal}}\nContext:\n{{step:0}}",
                    label = "Draft response",
                ),
                AgentStep.CallTool(analysisTool, mapOf("text" to "{{step:1}}"), label = "Analyse draft"),
                AgentStep.Remember(
                    content = "Goal: {{goal}}\nResult: {{step:1}}",
                    tags = setOf("agent-run", agentId),
                    label = "Save outcome",
                ),
            ),
        )
    }
}
