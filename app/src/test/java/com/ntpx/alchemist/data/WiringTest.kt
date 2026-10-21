package com.ntpx.alchemist.data

import com.ntpx.alchemist.agent.AgentRuntime
import com.ntpx.alchemist.agent.AgentStep
import com.ntpx.alchemist.agent.AgentTask
import com.ntpx.alchemist.agent.ChainVerification
import com.ntpx.alchemist.agent.ConsentDecision
import com.ntpx.alchemist.agent.GrantTable
import com.ntpx.alchemist.agent.InMemoryAuditLog
import com.ntpx.alchemist.agent.Permission
import com.ntpx.alchemist.agent.PermissionGate
import com.ntpx.alchemist.agent.RecallGenerateRememberPlanner
import com.ntpx.alchemist.agent.TaskEvent
import com.ntpx.alchemist.agent.TaskStatus
import com.ntpx.alchemist.mcp.Implementation
import com.ntpx.alchemist.mcp.LoopbackTransport
import com.ntpx.alchemist.mcp.McpClient
import com.ntpx.alchemist.mcp.McpServer
import com.ntpx.alchemist.mcp.builtinTools
import com.ntpx.alchemist.memory.InMemoryMemoryStore
import com.ntpx.alchemist.registry.CatalogResult
import com.ntpx.alchemist.registry.EchoBackend
import com.ntpx.alchemist.registry.InMemoryModelRegistry
import com.ntpx.alchemist.registry.ModelCatalogParser
import com.ntpx.alchemist.registry.ModelRouter
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Exercises the same adapters and catalog the app wires together, without Android. */
class WiringTest {

    private val catalogText = File("src/main/assets/models.json").readText()

    private fun registry() = InMemoryModelRegistry(
        (ModelCatalogParser.parse(catalogText) as CatalogResult.Success).catalog.models,
    )

    private suspend fun stack(consent: ConsentBroker = ConsentBroker(), settings: AppSettings = AppSettings()): Stack {
        val audit = InMemoryAuditLog()
        val grants = GrantTable()
        val memory = InMemoryMemoryStore()
        val server = McpServer(Implementation("t", "1"), builtinTools())
        val tools = McpToolPort(McpClient(LoopbackTransport(server), Implementation("c", "1"))).also { it.refresh() }
        val runtime = AgentRuntime(
            PermissionGate(grants, consent, audit),
            audit,
            RegistryModelPort(ModelRouter(registry(), listOf(EchoBackend())), MutableStateFlow(settings)),
            tools,
            StoreMemoryPort(memory),
        )
        return Stack(runtime, audit, memory, grants)
    }

    private data class Stack(val runtime: AgentRuntime, val audit: InMemoryAuditLog, val memory: InMemoryMemoryStore, val grants: GrantTable)

    @Test
    fun shippedCatalogParsesAndOnlyEchoIsRoutable() {
        val models = registry().models.value
        assertEquals(6, models.size)
        val router = ModelRouter(registry(), listOf(EchoBackend()))
        val route = router.route(com.ntpx.alchemist.registry.Capability.CHAT, AppSettings().routingPolicy)
        assertEquals("ntpx/echo-local", (route as com.ntpx.alchemist.registry.RouteResult.Routed).model.id)
    }

    @Test
    fun fullPipelineRunsThroughRealAdaptersWithConsent() = runTest {
        val broker = ConsentBroker()
        val s = stack(broker)
        s.grants.set("alchemist", Permission.WRITE_MEMORY, com.ntpx.alchemist.agent.Grant.ALLOW)

        val task = RecallGenerateRememberPlanner().plan("alchemist", "forge the registry")
        val events = async { s.runtime.run(task).toList() }

        val pending = broker.pending.filterNotNull().first()
        assertEquals(Permission.INVOKE_TOOL, pending.request.permission)
        broker.answer(ConsentDecision.ALLOW_ONCE)

        val result = events.await()
        val finished = result.last() as TaskEvent.Finished
        assertEquals(TaskStatus.COMPLETED, finished.status)
        assertTrue(finished.outputs[1].startsWith("[echo:ntpx/echo-local]"))
        assertTrue(finished.outputs[2].startsWith("words="))
        assertEquals(1, s.memory.all().size)
        assertTrue("agent-run" in s.memory.all().single().tags)
        assertNull(broker.pending.value)
        assertEquals(ChainVerification.Valid, s.audit.verify())
    }

    @Test
    fun secondRunRecallsFirstRunOutcome() = runTest {
        val s = stack()
        s.grants.set("alchemist", Permission.WRITE_MEMORY, com.ntpx.alchemist.agent.Grant.ALLOW)
        s.grants.set("alchemist", Permission.INVOKE_TOOL, com.ntpx.alchemist.agent.Grant.ALLOW)
        val planner = RecallGenerateRememberPlanner()
        s.runtime.run(planner.plan("alchemist", "quantum forge")).toList()
        val second = s.runtime.run(planner.plan("alchemist", "quantum forge")).toList().last() as TaskEvent.Finished
        assertTrue(second.outputs[0].contains("quantum forge"))
    }

    @Test
    fun cloudStepFailsEvenWhenPermittedBecauseSettingIsOff() = runTest {
        val s = stack()
        s.grants.set("alchemist", Permission.INVOKE_CLOUD_MODEL, com.ntpx.alchemist.agent.Grant.ALLOW)
        s.grants.set("alchemist", Permission.NETWORK, com.ntpx.alchemist.agent.Grant.ALLOW)
        val task = AgentTask("t", "alchemist", "g", listOf(AgentStep.Generate("hello", allowCloud = true)))
        val finished = s.runtime.run(task).toList().last() as TaskEvent.Finished
        // Policy strips cloud, so it routes on-device instead of reaching a cloud model.
        assertEquals(TaskStatus.COMPLETED, finished.status)
        assertTrue(finished.outputs.single().startsWith("[echo:ntpx/echo-local]"))
    }

    @Test
    fun unknownToolFailsClosed() = runTest {
        val s = stack()
        val task = AgentTask("t", "alchemist", "g", listOf(AgentStep.CallTool("does.not.exist")))
        val finished = s.runtime.run(task).toList().last() as TaskEvent.Finished
        assertEquals(TaskStatus.FAILED, finished.status)
    }

    @Test
    fun unansweredConsentTimesOutToDeny() = runTest {
        val broker = ConsentBroker(timeoutMillis = 1_000)
        val decision = broker.requestConsent(com.ntpx.alchemist.agent.ConsentRequest("a", "t", Permission.INVOKE_TOOL, "r"))
        assertEquals(ConsentDecision.DENY, decision)
        assertNull(broker.pending.value)
    }

    @Test
    fun deniedLocalModelBlocksCloudEnabledStepThatRoutesOnDevice() = runTest {
        val s = stack()
        s.grants.set("alchemist", Permission.INVOKE_LOCAL_MODEL, com.ntpx.alchemist.agent.Grant.DENY)
        s.grants.set("alchemist", Permission.INVOKE_CLOUD_MODEL, com.ntpx.alchemist.agent.Grant.ALLOW)
        s.grants.set("alchemist", Permission.NETWORK, com.ntpx.alchemist.agent.Grant.ALLOW)
        val task = AgentTask("t", "alchemist", "g", listOf(AgentStep.Generate("hello", allowCloud = true)))
        val events = s.runtime.run(task).toList()
        assertEquals(Permission.INVOKE_LOCAL_MODEL, events.filterIsInstance<TaskEvent.StepDenied>().single().permission)
    }
}
