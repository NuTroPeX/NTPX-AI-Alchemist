package com.ntpx.alchemist.agent

/**
 * Ports keep the runtime independent of concrete model, tool and memory implementations.
 * The app wires adapters to the registry, MCP client and memory store.
 */
interface ModelPort {
    suspend fun generate(prompt: String, system: String?, allowCloud: Boolean): String
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
