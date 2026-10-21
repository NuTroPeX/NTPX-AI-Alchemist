package com.ntpx.alchemist

import android.content.Context
import com.ntpx.alchemist.agent.AgentRuntime
import com.ntpx.alchemist.agent.AuditLog
import com.ntpx.alchemist.agent.AuditType
import com.ntpx.alchemist.agent.ChainVerification
import com.ntpx.alchemist.agent.GrantTable
import com.ntpx.alchemist.agent.InMemoryAuditLog
import com.ntpx.alchemist.agent.PermissionGate
import com.ntpx.alchemist.agent.Planner
import com.ntpx.alchemist.agent.RecallGenerateRememberPlanner
import com.ntpx.alchemist.data.AuditJournal
import com.ntpx.alchemist.data.ConsentBroker
import com.ntpx.alchemist.data.McpToolPort
import com.ntpx.alchemist.data.RegistryModelPort
import com.ntpx.alchemist.data.SettingsRepository
import com.ntpx.alchemist.data.StoreMemoryPort
import com.ntpx.alchemist.mcp.Implementation
import com.ntpx.alchemist.mcp.LoopbackTransport
import com.ntpx.alchemist.mcp.McpClient
import com.ntpx.alchemist.mcp.McpServer
import com.ntpx.alchemist.mcp.builtinTools
import com.ntpx.alchemist.memory.room.RoomMemoryStore
import com.ntpx.alchemist.registry.CatalogResult
import com.ntpx.alchemist.registry.EchoBackend
import com.ntpx.alchemist.registry.InMemoryModelRegistry
import com.ntpx.alchemist.registry.ModelCatalogParser
import com.ntpx.alchemist.registry.ModelRegistry
import com.ntpx.alchemist.registry.ModelRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

/** Manual dependency graph. One instance per process, owned by [AlchemistApplication]. */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ioWriter = Executors.newSingleThreadExecutor { r -> Thread(r, "audit-journal").apply { isDaemon = true } }

    val settings = SettingsRepository(app)

    val registry: ModelRegistry = InMemoryModelRegistry()
    val catalogError: String?

    init {
        val text = app.assets.open(CATALOG_ASSET).bufferedReader().use { it.readText() }
        catalogError = when (val result = ModelCatalogParser.parse(text)) {
            is CatalogResult.Success -> {
                registry.registerAll(result.catalog.models)
                null
            }
            is CatalogResult.Failure -> result.reason
        }
    }

    val router = ModelRouter(registry, listOf(EchoBackend()))

    private val journal = AuditJournal(File(app.filesDir, "audit/audit.jsonl"))
    private val restored = journal.load()

    val audit: AuditLog = InMemoryAuditLog(
        restored = restored.entries,
        capacity = journal.capacity,
        restoredAnchor = restored.anchor,
        onAppend = { entry -> ioWriter.execute { runCatching { journal.append(entry) } } },
    )

    /** Integrity of the journal as loaded at startup. */
    val restoredChain: ChainVerification = AuditLog.verifyChain(restored.entries, restored.anchor)
    val corruptJournalLines: Int = restored.corruptLines

    val grants: GrantTable = settings.grantTable()
    val consent = ConsentBroker()
    private val gate = PermissionGate(grants, consent, audit)

    val memory: RoomMemoryStore = RoomMemoryStore.create(app)

    val mcpServer = McpServer(
        info = Implementation("ntpx-alchemist-builtin", BuildInfo.VERSION),
        providers = builtinTools(),
        instructions = "Built-in offline tools hosted inside the Alchemist app.",
    )
    val mcpClient = McpClient(LoopbackTransport(mcpServer), Implementation("ntpx-alchemist", BuildInfo.VERSION))
    val tools = McpToolPort(mcpClient)

    val runtime = AgentRuntime(
        gate = gate,
        audit = audit,
        models = RegistryModelPort(router, settings.settings),
        tools = tools,
        memory = StoreMemoryPort(memory),
    )

    val planner: Planner = RecallGenerateRememberPlanner()

    init {
        appScope.launch { runCatching { tools.refresh() } }
        appScope.launch { grants.overrides.drop(1).collect(settings::saveGrants) }
        ioWriter.execute { runCatching { journal.compact() } }
    }

    fun recordSettingsChange(detail: String) {
        audit.append(agentId = "user", taskId = "settings", type = AuditType.SETTINGS_CHANGED, detail = detail)
    }

    companion object {
        const val CATALOG_ASSET = "models.json"
        const val DEFAULT_AGENT_ID = "alchemist"
    }
}

object BuildInfo {
    const val VERSION = "0.2.0"
}
