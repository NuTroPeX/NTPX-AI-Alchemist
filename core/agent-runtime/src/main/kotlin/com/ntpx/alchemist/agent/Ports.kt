package com.ntpx.alchemist.agent

/** The model a generation step will actually use, resolved before permissions are checked. */
data class ModelRoute(
    val modelId: String,
    /** True when inference leaves the device (local network or cloud). */
    val usesNetwork: Boolean,
    /** True when inference goes to a remote provider. */
    val cloud: Boolean,
)

/**
 * Ports keep the runtime independent of concrete model, tool and memory implementations.
 * The app wires adapters to the registry, MCP client and memory store.
 */
interface ModelPort {
    /** Picks the model for a step, or throws if none is available. Must not run inference. */
    fun route(allowCloud: Boolean): ModelRoute

    /** Runs inference on exactly the model chosen by [route]. */
    suspend fun generate(route: ModelRoute, prompt: String, system: String?): String
}

interface ToolPort {
    /** Extra permissions a specific tool declares beyond [Permission.INVOKE_TOOL]. */
    fun requiredPermissions(tool: String): Set<Permission>

    suspend fun call(tool: String, arguments: Map<String, String>): String
}

interface MemoryPort {
    suspend fun recall(query: String, limit: Int): List<String>

    /** Returns the id of the stored entry. */
    suspend fun remember(content: String, tags: Set<String>): String
}
