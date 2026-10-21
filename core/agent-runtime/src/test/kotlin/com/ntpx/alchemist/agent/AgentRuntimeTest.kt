package com.ntpx.alchemist.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeTest {

    /** [cloudAvailable] false mimics a router that falls back to on-device even when cloud is allowed. */
    private class FakeModels(private val delayMillis: Long = 0, private val cloudAvailable: Boolean = true) : ModelPort {
        val prompts = mutableListOf<String>()
        override fun route(allowCloud: Boolean): ModelRoute =
            if (allowCloud && cloudAvailable) ModelRoute("cloud", usesNetwork = true, cloud = true)
            else ModelRoute("local", usesNetwork = false, cloud = false)

        override suspend fun generate(route: ModelRoute, prompt: String, system: String?): String {
            if (delayMillis > 0) delay(delayMillis)
            prompts += prompt
            return "draft for: ${prompt.lineSequence().first()}"
        }
    }

    private class FakeTools(private val extra: Map<String, Set<Permission>> = emptyMap()) : ToolPort {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        override fun requiredPermissions(tool: String): Set<Permission> = extra[tool].orEmpty()
        override suspend fun call(tool: String, arguments: Map<String, String>): String {
            calls += tool to arguments
            if (tool == "boom") error("tool exploded")
            return "words=${arguments["text"].orEmpty().split(' ').size}"
        }
    }

    private class FakeMemory : MemoryPort {
        val stored = mutableListOf<String>()
        override suspend fun recall(query: String, limit: Int): List<String> = stored.filter { it.contains(query) }.take(limit)
        override suspend fun remember(content: String, tags: Set<String>): String {
            stored += content
            return "m${stored.size}"
        }
    }

    private data class Harness(
        val runtime: AgentRuntime,
        val audit: InMemoryAuditLog,
        val grants: GrantTable,
        val models: FakeModels,
        val tools: FakeTools,
        val memory: FakeMemory,
        val consentRequests: MutableList<ConsentRequest>,
    )

    private fun harness(
        consent: ConsentDecision = ConsentDecision.ALLOW_ONCE,
        models: FakeModels = FakeModels(),
        tools: FakeTools = FakeTools(),
        consentProvider: ConsentProvider? = null,
    ): Harness {
        var clock = 0L
        val audit = InMemoryAuditLog(clock = { clock++ })
        val grants = GrantTable()
        val requests = mutableListOf<ConsentRequest>()
        val gate = PermissionGate(grants, consentProvider ?: ConsentProvider { requests += it; consent }, audit)
        val memory = FakeMemory()
        return Harness(AgentRuntime(gate, audit, models, tools, memory), audit, grants, models, tools, memory, requests)
    }

    private val planner = RecallGenerateRememberPlanner(idFactory = { "task-1" })

    @Test
    fun completesFullPipelineAndResolvesTemplates() = runTest {
        val h = harness()
        val events = h.runtime.run(planner.plan("agent", "map the forge")).toList()

        val finished = events.last() as TaskEvent.Finished
        assertEquals(TaskStatus.COMPLETED, finished.status)
        assertEquals(4, finished.outputs.size)
        assertEquals("(no matching memories)", finished.outputs[0])
        assertTrue(h.models.prompts.single().startsWith("Goal: map the forge"))
        assertEquals(finished.outputs[1], h.tools.calls.single().second["text"])
        assertTrue(h.memory.stored.single().contains("Result: draft for: Goal: map the forge"))
        assertEquals(AuditLog.verifyChain(h.audit.entries.value), ChainVerification.Valid)
    }

    @Test
    fun askPermissionsPromptOnceEachThenAllowAlwaysPersists() = runTest {
        val h = harness(consent = ConsentDecision.ALLOW_ALWAYS)
        h.runtime.run(planner.plan("agent", "first")).toList()
        assertEquals(listOf(Permission.INVOKE_TOOL, Permission.WRITE_MEMORY), h.consentRequests.map { it.permission })
        assertEquals(Grant.ALLOW, h.grants.grantFor("agent", Permission.WRITE_MEMORY))

        h.consentRequests.clear()
        h.runtime.run(planner.plan("agent", "second")).toList()
        assertTrue("No further prompts after ALLOW_ALWAYS", h.consentRequests.isEmpty())
    }

    @Test
    fun deniedConsentStopsTaskBeforeSideEffects() = runTest {
        val h = harness(consent = ConsentDecision.DENY)
        val events = h.runtime.run(planner.plan("agent", "goal")).toList()

        val denied = events.filterIsInstance<TaskEvent.StepDenied>().single()
        assertEquals(2, denied.index)
        assertEquals(Permission.INVOKE_TOOL, denied.permission)
        assertEquals(TaskStatus.DENIED, (events.last() as TaskEvent.Finished).status)
        assertTrue(h.tools.calls.isEmpty())
        assertTrue(h.memory.stored.isEmpty())
        assertTrue(h.audit.entries.value.any { it.type == AuditType.PERMISSION_DENIED })
    }

    @Test
    fun cloudGenerationIsDeniedByDefault() = runTest {
        val h = harness()
        val task = AgentTask("t", "agent", "g", listOf(AgentStep.Generate("hi", allowCloud = true)))
        val events = h.runtime.run(task).toList()
        assertEquals(Permission.INVOKE_CLOUD_MODEL, events.filterIsInstance<TaskEvent.StepDenied>().single().permission)
        assertTrue(h.consentRequests.isEmpty())
        assertTrue(h.models.prompts.isEmpty())
    }

    @Test
    fun toolDeclaredPermissionsAreEnforced() = runTest {
        val h = harness(tools = FakeTools(extra = mapOf("web.fetch" to setOf(Permission.NETWORK))))
        val task = AgentTask("t", "agent", "g", listOf(AgentStep.CallTool("web.fetch")))
        val events = h.runtime.run(task).toList()
        assertEquals(Permission.NETWORK, events.filterIsInstance<TaskEvent.StepDenied>().single().permission)
        assertTrue(h.tools.calls.isEmpty())
    }

    @Test
    fun toolErrorFailsTask() = runTest {
        val h = harness()
        val task = AgentTask("t", "agent", "g", listOf(AgentStep.CallTool("boom")))
        val events = h.runtime.run(task).toList()
        assertEquals("tool exploded", events.filterIsInstance<TaskEvent.StepFailed>().single().error)
        assertEquals(TaskStatus.FAILED, (events.last() as TaskEvent.Finished).status)
    }

    @Test
    fun slowStepTimesOut() = runTest {
        val h = harness(models = FakeModels(delayMillis = 5_000))
        val task = AgentTask("t", "agent", "g", listOf(AgentStep.Generate("hi")), Budget(stepTimeoutMillis = 100))
        val events = h.runtime.run(task).toList()
        assertEquals(TaskStatus.TIMED_OUT, (events.last() as TaskEvent.Finished).status)
    }

    @Test
    fun planLargerThanBudgetIsRejected() = runTest {
        val h = harness()
        val steps = List(3) { AgentStep.Recall("x") }
        val events = h.runtime.run(AgentTask("t", "agent", "g", steps, Budget(maxSteps = 2))).toList()
        assertEquals(TaskStatus.FAILED, (events.last() as TaskEvent.Finished).status)
        assertTrue(events.none { it is TaskEvent.StepStarted })
    }

    @Test
    fun cancellingCollectorIsAudited() = runTest {
        val h = harness()
        h.runtime.run(planner.plan("agent", "goal")).take(2).toList()
        assertEquals(AuditType.TASK_CANCELLED, h.audit.entries.value.last().type)
    }

    @Test
    fun permittingCloudDoesNotBypassDeniedLocalModel() = runTest {
        val h = harness(models = FakeModels(cloudAvailable = false))
        h.grants.set("agent", Permission.INVOKE_LOCAL_MODEL, Grant.DENY)
        h.grants.set("agent", Permission.INVOKE_CLOUD_MODEL, Grant.ALLOW)
        h.grants.set("agent", Permission.NETWORK, Grant.ALLOW)
        val task = AgentTask("t", "agent", "g", listOf(AgentStep.Generate("hi", allowCloud = true)))
        val events = h.runtime.run(task).toList()
        assertEquals(Permission.INVOKE_LOCAL_MODEL, events.filterIsInstance<TaskEvent.StepDenied>().single().permission)
        assertTrue(h.models.prompts.isEmpty())
    }

    @Test
    fun cloudRouteRequiresCloudAndNetwork() = runTest {
        val h = harness()
        h.grants.set("agent", Permission.INVOKE_CLOUD_MODEL, Grant.ALLOW)
        val task = AgentTask("t", "agent", "g", listOf(AgentStep.Generate("hi", allowCloud = true)))
        val events = h.runtime.run(task).toList()
        assertEquals(Permission.NETWORK, events.filterIsInstance<TaskEvent.StepDenied>().single().permission)
    }

    @Test
    fun stepTimeoutCoversUnansweredConsent() = runTest {
        val h = harness(consentProvider = { kotlinx.coroutines.awaitCancellation() })
        val task = AgentTask("t", "agent", "g", listOf(AgentStep.CallTool("text.stats")), Budget(stepTimeoutMillis = 100))
        val events = h.runtime.run(task).toList()
        assertEquals(TaskStatus.TIMED_OUT, (events.last() as TaskEvent.Finished).status)
        assertTrue(h.tools.calls.isEmpty())
    }
}
