package com.ntpx.alchemist.registry

/** A prompt sent to one model backend. */
data class GenerationRequest(
    val modelId: String,
    val prompt: String,
    val system: String? = null,
    val maxTokens: Int = 512,
    val temperature: Double = 0.7,
)

data class GenerationResult(
    val modelId: String,
    val text: String,
    val promptTokens: Int,
    val completionTokens: Int,
)

/**
 * Executes inference for models whose [ModelDescriptor.backend] matches [id].
 * Real backends (llama.cpp, MediaPipe LLM, Ollama on the LAN, cloud APIs) plug in here.
 */
interface ModelBackend {
    val id: String
    suspend fun generate(request: GenerationRequest): GenerationResult
}

/**
 * Deterministic on-device backend used to exercise routing, permissions and the
 * agent pipeline end-to-end. It does NOT perform inference; it reflects the prompt.
 */
class EchoBackend : ModelBackend {
    override val id: String = ID

    override suspend fun generate(request: GenerationRequest): GenerationResult {
        val prompt = request.prompt.trim()
        val words = prompt.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val reply = buildString {
            append("[echo:").append(request.modelId).append("] ")
            append(words.take(request.maxTokens.coerceAtLeast(1)).joinToString(" "))
        }
        return GenerationResult(
            modelId = request.modelId,
            text = reply,
            promptTokens = words.size,
            completionTokens = reply.split(' ').size,
        )
    }

    companion object {
        const val ID = "echo"
    }
}

/** Routing policy. Cloud placement is excluded unless the user has opted in. */
data class RoutingPolicy(
    val allowCloud: Boolean = false,
    val allowLocalNetwork: Boolean = true,
    val preferredModelId: String? = null,
) {
    val allowedPlacements: Set<Placement>
        get() = buildSet {
            add(Placement.ON_DEVICE)
            if (allowLocalNetwork) add(Placement.LOCAL_NETWORK)
            if (allowCloud) add(Placement.CLOUD)
        }
}

sealed interface RouteResult {
    data class Routed(val model: ModelDescriptor, val backend: ModelBackend) : RouteResult
    data class NoRoute(val reason: String) : RouteResult
}

class ModelRouter(
    private val registry: ModelRegistry,
    backends: Collection<ModelBackend>,
) {
    private val backendsById = backends.associateBy { it.id }

    fun route(capability: Capability, policy: RoutingPolicy): RouteResult {
        val candidates = registry.find(
            ModelQuery(capability = capability, allowedPlacements = policy.allowedPlacements, readyOnly = true),
        ).filter { it.backend in backendsById }

        if (candidates.isEmpty()) {
            return RouteResult.NoRoute(
                "No ready model with backend for ${capability.name.lowercase()} " +
                    "within placements ${policy.allowedPlacements.joinToString { it.name.lowercase() }}",
            )
        }
        val chosen = candidates.firstOrNull { it.id == policy.preferredModelId }
            ?: candidates.minBy { it.placement.ordinal }
        return RouteResult.Routed(chosen, backendsById.getValue(chosen.backend))
    }

    suspend fun generate(capability: Capability, policy: RoutingPolicy, prompt: String, system: String? = null): Result<GenerationResult> =
        when (val route = route(capability, policy)) {
            is RouteResult.NoRoute -> Result.failure(IllegalStateException(route.reason))
            is RouteResult.Routed -> runCatching {
                route.backend.generate(GenerationRequest(modelId = route.model.id, prompt = prompt, system = system))
            }
        }
}
