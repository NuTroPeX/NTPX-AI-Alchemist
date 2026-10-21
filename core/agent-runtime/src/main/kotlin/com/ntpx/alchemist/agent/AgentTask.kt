package com.ntpx.alchemist.agent

/** Limits that bound every task so a runaway plan cannot loop or hang forever. */
data class Budget(
    val maxSteps: Int = 32,
    /** Covers permission prompts as well as execution, so it includes time the user spends deciding. */
    val stepTimeoutMillis: Long = 120_000,
) {
    init {
        require(maxSteps in 1..256) { "maxSteps must be in 1..256" }
        require(stepTimeoutMillis > 0) { "stepTimeoutMillis must be > 0" }
    }
}

data class AgentTask(
    val id: String,
    val agentId: String,
    val goal: String,
    val steps: List<AgentStep>,
    val budget: Budget = Budget(),
) {
    init {
        require(id.isNotBlank()) { "Task id must not be blank" }
        require(agentId.isNotBlank()) { "Agent id must not be blank" }
        require(steps.isNotEmpty()) { "Task must have at least one step" }
    }
}

/**
 * One unit of work. Text fields may use templates resolved at run time:
 * `{{goal}}` the task goal, `{{last}}` the previous step's output, `{{step:N}}` step N's output (0-based).
 */
sealed interface AgentStep {
    val label: String

    /**
     * Permissions that must all be granted before the step runs. [modelRoute] is the
     * resolved model for [Generate] steps (null for every other step).
     */
    fun requiredPermissions(tools: ToolPort, modelRoute: ModelRoute?): Set<Permission>

    data class Generate(
        val prompt: String,
        val system: String? = null,
        val allowCloud: Boolean = false,
        override val label: String = "Generate",
    ) : AgentStep {
        /** Gated on the route actually selected, so permitting cloud never smuggles in a denied local model. */
        override fun requiredPermissions(tools: ToolPort, modelRoute: ModelRoute?): Set<Permission> {
            val route = requireNotNull(modelRoute) { "Generate step requires a resolved model route" }
            return buildSet {
                add(if (route.cloud) Permission.INVOKE_CLOUD_MODEL else Permission.INVOKE_LOCAL_MODEL)
                if (route.usesNetwork || route.cloud) add(Permission.NETWORK)
            }
        }
    }

    data class CallTool(
        val tool: String,
        val arguments: Map<String, String> = emptyMap(),
        override val label: String = "Tool: $tool",
    ) : AgentStep {
        override fun requiredPermissions(tools: ToolPort, modelRoute: ModelRoute?): Set<Permission> =
            setOf(Permission.INVOKE_TOOL) + tools.requiredPermissions(tool)
    }

    data class Recall(
        val query: String,
        val limit: Int = 5,
        override val label: String = "Recall memory",
    ) : AgentStep {
        override fun requiredPermissions(tools: ToolPort, modelRoute: ModelRoute?): Set<Permission> = setOf(Permission.READ_MEMORY)
    }

    data class Remember(
        val content: String,
        val tags: Set<String> = emptySet(),
        override val label: String = "Write memory",
    ) : AgentStep {
        override fun requiredPermissions(tools: ToolPort, modelRoute: ModelRoute?): Set<Permission> = setOf(Permission.WRITE_MEMORY)
    }
}

internal object Templates {
    private val pattern = Regex("\\{\\{\\s*(goal|last|step:(\\d+))\\s*}}")

    fun resolve(template: String, goal: String, outputs: List<String>): String =
        pattern.replace(template) { match ->
            when {
                match.groupValues[1] == "goal" -> goal
                match.groupValues[1] == "last" -> outputs.lastOrNull().orEmpty()
                else -> outputs.getOrNull(match.groupValues[2].toInt()).orEmpty()
            }
        }
}
