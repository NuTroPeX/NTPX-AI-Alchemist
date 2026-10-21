package com.ntpx.alchemist.registry

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelRegistryTest {

    private fun model(
        id: String,
        placement: Placement = Placement.ON_DEVICE,
        caps: Set<Capability> = setOf(Capability.CHAT),
        availability: Availability = Availability.READY,
        backend: String = EchoBackend.ID,
    ) = ModelDescriptor(
        id = id,
        displayName = id.uppercase(),
        provider = "test",
        placement = placement,
        capabilities = caps,
        backend = backend,
        availability = availability,
    )

    @Test
    fun registerReplacesById() {
        val registry = InMemoryModelRegistry()
        registry.register(model("a"))
        registry.register(model("a", availability = Availability.NOT_INSTALLED))
        assertEquals(1, registry.models.value.size)
        assertEquals(Availability.NOT_INSTALLED, registry.get("a")?.availability)
    }

    @Test
    fun unregisterReportsWhetherRemoved() {
        val registry = InMemoryModelRegistry(listOf(model("a")))
        assertTrue(registry.unregister("a"))
        assertFalse(registry.unregister("a"))
        assertNull(registry.get("a"))
    }

    @Test
    fun modelsSortOnDeviceBeforeCloud() {
        val registry = InMemoryModelRegistry(
            listOf(model("z-cloud", Placement.CLOUD), model("b-lan", Placement.LOCAL_NETWORK), model("y-local")),
        )
        assertEquals(listOf("y-local", "b-lan", "z-cloud"), registry.models.value.map { it.id })
    }

    @Test
    fun queryFiltersByCapabilityPlacementReadinessAndText() {
        val registry = InMemoryModelRegistry(
            listOf(
                model("chat-local"),
                model("embed-local", caps = setOf(Capability.EMBEDDINGS)),
                model("chat-cloud", Placement.CLOUD),
                model("chat-missing", availability = Availability.NOT_INSTALLED),
            ),
        )
        val result = registry.find(
            ModelQuery(capability = Capability.CHAT, allowedPlacements = setOf(Placement.ON_DEVICE), readyOnly = true),
        )
        assertEquals(listOf("chat-local"), result.map { it.id })
        assertEquals(listOf("embed-local"), registry.find(ModelQuery(text = "EMBED")).map { it.id })
    }

    @Test
    fun setAvailabilityUpdatesExistingOnly() {
        val registry = InMemoryModelRegistry(listOf(model("a", availability = Availability.NOT_INSTALLED)))
        assertTrue(registry.setAvailability("a", Availability.READY))
        assertFalse(registry.setAvailability("missing", Availability.READY))
        assertEquals(Availability.READY, registry.get("a")?.availability)
    }

    @Test
    fun descriptorRejectsInvalidIdsAndEmptyCapabilities() {
        listOf("", "Has Space", "UPPER").forEach { bad ->
            try {
                model(bad)
                fail("Expected '$bad' to be rejected")
            } catch (_: IllegalArgumentException) {
            }
        }
        try {
            model("ok", caps = emptySet())
            fail("Expected empty capabilities to be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun routerExcludesCloudUnlessOptedIn() = runTest {
        val registry = InMemoryModelRegistry(listOf(model("cloud-only", Placement.CLOUD)))
        val router = ModelRouter(registry, listOf(EchoBackend()))
        assertTrue(router.route(Capability.CHAT, RoutingPolicy()) is RouteResult.NoRoute)
        val routed = router.route(Capability.CHAT, RoutingPolicy(allowCloud = true))
        assertEquals("cloud-only", (routed as RouteResult.Routed).model.id)
    }

    @Test
    fun routerPrefersOnDeviceThenPreferredId() = runTest {
        val registry = InMemoryModelRegistry(
            listOf(model("lan", Placement.LOCAL_NETWORK), model("device"), model("device-2")),
        )
        val router = ModelRouter(registry, listOf(EchoBackend()))
        assertEquals("device", (router.route(Capability.CHAT, RoutingPolicy()) as RouteResult.Routed).model.id)
        val preferred = router.route(Capability.CHAT, RoutingPolicy(preferredModelId = "lan")) as RouteResult.Routed
        assertEquals("lan", preferred.model.id)
    }

    @Test
    fun routerSkipsModelsWithoutRegisteredBackend() = runTest {
        val registry = InMemoryModelRegistry(listOf(model("needs-llama", backend = "llama.cpp")))
        val router = ModelRouter(registry, listOf(EchoBackend()))
        val result = router.generate(Capability.CHAT, RoutingPolicy(), "hello")
        assertTrue(result.isFailure)
    }

    @Test
    fun echoBackendReflectsPromptDeterministically() = runTest {
        val result = EchoBackend().generate(GenerationRequest(modelId = "m", prompt = "  hello   alchemist  "))
        assertEquals("[echo:m] hello alchemist", result.text)
        assertEquals(2, result.promptTokens)
    }
}
