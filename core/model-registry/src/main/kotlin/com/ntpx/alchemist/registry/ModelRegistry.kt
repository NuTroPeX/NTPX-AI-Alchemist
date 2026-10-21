package com.ntpx.alchemist.registry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Single source of truth for which models exist and what they can do. */
interface ModelRegistry {
    val models: StateFlow<List<ModelDescriptor>>

    /** Adds or replaces a model by id. */
    fun register(model: ModelDescriptor)

    fun registerAll(models: Collection<ModelDescriptor>) = models.forEach(::register)

    fun unregister(id: String): Boolean

    fun get(id: String): ModelDescriptor?

    fun setAvailability(id: String, availability: Availability): Boolean

    fun find(query: ModelQuery = ModelQuery()): List<ModelDescriptor> = models.value.filter(query::matches)
}

data class ModelQuery(
    val capability: Capability? = null,
    val allowedPlacements: Set<Placement> = Placement.entries.toSet(),
    val readyOnly: Boolean = false,
    val text: String = "",
) {
    fun matches(model: ModelDescriptor): Boolean {
        if (capability != null && !model.supports(capability)) return false
        if (model.placement !in allowedPlacements) return false
        if (readyOnly && model.availability != Availability.READY) return false
        if (text.isNotBlank()) {
            val needle = text.trim()
            val haystack = listOf(model.id, model.displayName, model.provider, model.description)
            if (haystack.none { it.contains(needle, ignoreCase = true) }) return false
        }
        return true
    }
}

class InMemoryModelRegistry(initial: Collection<ModelDescriptor> = emptyList()) : ModelRegistry {
    private val state = MutableStateFlow(sorted(initial.associateBy { it.id }.values))

    override val models: StateFlow<List<ModelDescriptor>> = state.asStateFlow()

    override fun register(model: ModelDescriptor) {
        state.update { current -> sorted(current.filterNot { it.id == model.id } + model) }
    }

    /** Result reflects the update that actually committed, even under concurrent modification. */
    override fun unregister(id: String): Boolean {
        while (true) {
            val current = state.value
            if (current.none { it.id == id }) return false
            if (state.compareAndSet(current, current.filterNot { it.id == id })) return true
        }
    }

    override fun get(id: String): ModelDescriptor? = state.value.firstOrNull { it.id == id }

    override fun setAvailability(id: String, availability: Availability): Boolean {
        while (true) {
            val current = state.value
            if (current.none { it.id == id }) return false
            val next = current.map { if (it.id == id) it.copy(availability = availability) else it }
            if (state.compareAndSet(current, next)) return true
        }
    }

    private fun sorted(models: Collection<ModelDescriptor>): List<ModelDescriptor> =
        models.sortedWith(compareBy<ModelDescriptor> { it.placement.ordinal }.thenBy { it.displayName.lowercase() })
}
