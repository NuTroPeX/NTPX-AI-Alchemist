package com.ntpx.alchemist.ui.registry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ntpx.alchemist.registry.Availability
import com.ntpx.alchemist.registry.Capability
import com.ntpx.alchemist.registry.ModelDescriptor
import com.ntpx.alchemist.registry.ModelRegistry
import com.ntpx.alchemist.registry.Placement
import com.ntpx.alchemist.ui.components.Badge
import com.ntpx.alchemist.ui.components.ChipGroup
import com.ntpx.alchemist.ui.components.EmptyState
import com.ntpx.alchemist.ui.components.GlassCard
import com.ntpx.alchemist.ui.components.SectionHeader
import com.ntpx.alchemist.ui.components.formatBytes
import com.ntpx.alchemist.ui.theme.Ntpx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class RegistryFilter(
    val text: String = "",
    val capabilities: Set<Capability> = emptySet(),
    val placements: Set<Placement> = emptySet(),
    val readyOnly: Boolean = false,
) {
    fun matches(model: ModelDescriptor): Boolean {
        if (capabilities.isNotEmpty() && model.capabilities.none { it in capabilities }) return false
        if (placements.isNotEmpty() && model.placement !in placements) return false
        if (readyOnly && model.availability != Availability.READY) return false
        if (text.isNotBlank()) {
            val needle = text.trim()
            if (listOf(model.id, model.displayName, model.provider).none { it.contains(needle, ignoreCase = true) }) return false
        }
        return true
    }
}

data class RegistryUiState(val filter: RegistryFilter = RegistryFilter(), val models: List<ModelDescriptor> = emptyList(), val total: Int = 0)

class RegistryViewModel(registry: ModelRegistry, val catalogError: String?) : ViewModel() {
    private val filter = MutableStateFlow(RegistryFilter())

    val state: StateFlow<RegistryUiState> = combine(registry.models, filter) { models, f ->
        RegistryUiState(f, models.filter(f::matches), models.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RegistryUiState())

    fun setText(text: String) = filter.update { it.copy(text = text) }
    fun toggleCapability(c: Capability) = filter.update { it.copy(capabilities = it.capabilities.toggle(c)) }
    fun togglePlacement(p: Placement) = filter.update { it.copy(placements = it.placements.toggle(p)) }
    fun setReadyOnly(value: Boolean) = filter.update { it.copy(readyOnly = value) }

    private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item
}

@Composable
fun RegistryScreen(vm: RegistryViewModel, contentPadding: PaddingValues) {
    val state by vm.state.collectAsStateWithLifecycle()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        vm.catalogError?.let { error ->
            item { GlassCard(accent = Ntpx.Danger) { Text("Catalog failed to load: $error", color = Ntpx.Danger) } }
        }
        item {
            GlassCard {
                OutlinedTextField(
                    value = state.filter.text,
                    onValueChange = vm::setText,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text("Search models, providers…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SectionHeader("Capabilities")
                ChipGroup(Capability.entries, state.filter.capabilities, ::capabilityLabel, vm::toggleCapability)
                SectionHeader("Placement")
                ChipGroup(Placement.entries, state.filter.placements, ::placementLabel, vm::togglePlacement, accent = Ntpx.Cyan)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ready to run only", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = state.filter.readyOnly, onCheckedChange = vm::setReadyOnly)
                }
            }
        }
        item {
            Text(
                "${state.models.size} of ${state.total} models",
                style = MaterialTheme.typography.labelSmall,
                color = Ntpx.Muted,
            )
        }
        if (state.models.isEmpty()) {
            item { EmptyState("No models match", "Clear a filter to see more of the registry.") }
        }
        items(state.models, key = { it.id }) { ModelCard(it) }
    }
}

@Composable
private fun ModelCard(model: ModelDescriptor) {
    val (statusText, statusColor) = when (model.availability) {
        Availability.READY -> "ready" to Ntpx.Success
        Availability.NOT_INSTALLED -> "not installed" to Ntpx.Muted
        Availability.REQUIRES_CREDENTIALS -> "needs key" to Ntpx.Warning
        Availability.UNAVAILABLE -> "unavailable" to Ntpx.Danger
    }
    GlassCard(accent = if (model.availability == Availability.READY) Ntpx.Cyan.copy(alpha = 0.6f) else Ntpx.GlassBorder) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(model.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Badge(statusText, statusColor)
        }
        Text(model.id, style = MaterialTheme.typography.bodySmall, color = Ntpx.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Badge(placementLabel(model.placement), if (model.placement == Placement.CLOUD) Ntpx.Magenta else Ntpx.Violet)
            Badge(model.backend, Ntpx.Cyan)
        }
        Text(model.capabilities.sortedBy { it.ordinal }.joinToString(" · ") { capabilityLabel(it) }, style = MaterialTheme.typography.bodyMedium)
        if (model.description.isNotBlank()) {
            Text(model.description, style = MaterialTheme.typography.bodyMedium, color = Ntpx.Muted)
        }
        Row {
            Text("ctx ${if (model.contextWindow > 0) "%,d".format(model.contextWindow) else "—"}", style = MaterialTheme.typography.labelSmall, color = Ntpx.Muted)
            Spacer(Modifier.width(16.dp))
            Text("size ${formatBytes(model.sizeBytes)}", style = MaterialTheme.typography.labelSmall, color = Ntpx.Muted)
            Spacer(Modifier.weight(1f))
            Text(model.provider, style = MaterialTheme.typography.labelSmall, color = Ntpx.Muted, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

fun capabilityLabel(c: Capability): String = when (c) {
    Capability.CHAT -> "Chat"
    Capability.TEXT_GENERATION -> "Text"
    Capability.EMBEDDINGS -> "Embeddings"
    Capability.VISION -> "Vision"
    Capability.SPEECH_TO_TEXT -> "Speech→Text"
    Capability.TEXT_TO_SPEECH -> "Text→Speech"
    Capability.IMAGE_GENERATION -> "Image gen"
    Capability.TOOL_USE -> "Tool use"
}

fun placementLabel(p: Placement): String = when (p) {
    Placement.ON_DEVICE -> "On-device"
    Placement.LOCAL_NETWORK -> "Local network"
    Placement.CLOUD -> "Cloud"
}
