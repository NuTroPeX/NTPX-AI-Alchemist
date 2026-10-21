package com.ntpx.alchemist.data

import com.ntpx.alchemist.agent.MemoryPort
import com.ntpx.alchemist.agent.ModelRoute
import com.ntpx.alchemist.agent.ModelPort
import com.ntpx.alchemist.agent.Permission
import com.ntpx.alchemist.agent.ToolPort
import com.ntpx.alchemist.mcp.McpClient
import com.ntpx.alchemist.mcp.ToolDefinition
import com.ntpx.alchemist.memory.MemoryStore
import com.ntpx.alchemist.registry.Capability
import com.ntpx.alchemist.registry.ModelRouter
import com.ntpx.alchemist.registry.Placement
import com.ntpx.alchemist.registry.RouteResult
import com.ntpx.alchemist.registry.RoutingPolicy
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Routes generation through the registry. Cloud requires BOTH the step flag and the user setting. */
class RegistryModelPort(
    private val router: ModelRouter,
    private val settings: StateFlow<AppSettings>,
) : ModelPort {
    private fun policy(allowCloud: Boolean): RoutingPolicy {
        val base = settings.value.routingPolicy
        return base.copy(allowCloud = base.allowCloud && allowCloud)
    }

    override fun route(allowCloud: Boolean): ModelRoute = when (val route = router.route(Capability.CHAT, policy(allowCloud))) {
        is RouteResult.NoRoute -> throw IllegalStateException(route.reason)
        is RouteResult.Routed -> ModelRoute(
            modelId = route.model.id,
            usesNetwork = route.model.placement != Placement.ON_DEVICE,
            cloud = route.model.placement == Placement.CLOUD,
        )
    }

    override suspend fun generate(route: ModelRoute, prompt: String, system: String?): String =
        router.generate(route.modelId, policy(allowCloud = route.cloud), prompt, system).getOrThrow().text
}

/** Exposes MCP tools to agents and maps `_meta["ntpx/permissions"]` to runtime permissions. */
class McpToolPort(private val client: McpClient) : ToolPort {
    private val lock = Mutex()

    @Volatile
    private var definitions: Map<String, ToolDefinition> = emptyMap()

    suspend fun refresh(): List<ToolDefinition> = lock.withLock {
        client.listTools().also { list -> definitions = list.associateBy { it.name } }
    }

    override fun requiredPermissions(tool: String): Set<Permission> {
        val definition = definitions[tool] ?: throw IllegalArgumentException("Unknown tool '$tool'")
        return definition.declaredPermissions.map { name ->
            Permission.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: throw IllegalArgumentException("Tool '$tool' declares unknown permission '$name'")
        }.toSet()
    }

    override suspend fun call(tool: String, arguments: Map<String, String>): String {
        val result = client.callTool(tool, arguments)
        if (result.isError) throw IllegalStateException(result.text)
        return result.text
    }
}

class StoreMemoryPort(private val store: MemoryStore, private val namespace: String? = null) : MemoryPort {
    override suspend fun recall(query: String, limit: Int): List<String> =
        store.search(query, namespace, limit).map { it.entry.content }

    override suspend fun remember(content: String, tags: Set<String>): String =
        store.put(content.take(com.ntpx.alchemist.memory.MemoryEntry.MAX_CONTENT), tags = tags).id
}
