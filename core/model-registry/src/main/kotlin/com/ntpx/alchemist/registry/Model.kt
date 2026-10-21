package com.ntpx.alchemist.registry

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a model can do. Routing and UI filtering both key off this set. */
@Serializable
enum class Capability {
    @SerialName("chat") CHAT,
    @SerialName("text_generation") TEXT_GENERATION,
    @SerialName("embeddings") EMBEDDINGS,
    @SerialName("vision") VISION,
    @SerialName("speech_to_text") SPEECH_TO_TEXT,
    @SerialName("text_to_speech") TEXT_TO_SPEECH,
    @SerialName("image_generation") IMAGE_GENERATION,
    @SerialName("tool_use") TOOL_USE,
}

/** Where inference runs. Cloud models are always opt-in and never selected by default. */
@Serializable
enum class Placement {
    @SerialName("on_device") ON_DEVICE,
    @SerialName("local_network") LOCAL_NETWORK,
    @SerialName("cloud") CLOUD;

    val requiresInternet: Boolean get() = this == CLOUD
}

/** Install/availability state for a model on this device. */
@Serializable
enum class Availability {
    @SerialName("ready") READY,
    @SerialName("not_installed") NOT_INSTALLED,
    @SerialName("requires_credentials") REQUIRES_CREDENTIALS,
    @SerialName("unavailable") UNAVAILABLE,
}

@Serializable
data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val provider: String,
    val placement: Placement,
    val capabilities: Set<Capability>,
    val contextWindow: Int = 0,
    val sizeBytes: Long? = null,
    val backend: String,
    val availability: Availability = Availability.NOT_INSTALLED,
    val description: String = "",
) {
    init {
        require(id.isNotBlank()) { "Model id must not be blank" }
        require(ID_PATTERN.matches(id)) { "Model id '$id' must match ${ID_PATTERN.pattern}" }
        require(capabilities.isNotEmpty()) { "Model '$id' must declare at least one capability" }
        require(contextWindow >= 0) { "Model '$id' contextWindow must be >= 0" }
        require(sizeBytes == null || sizeBytes >= 0) { "Model '$id' sizeBytes must be >= 0" }
    }

    fun supports(capability: Capability): Boolean = capability in capabilities

    companion object {
        val ID_PATTERN = Regex("^[a-z0-9][a-z0-9._:/-]{0,127}$")
    }
}
