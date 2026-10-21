package com.ntpx.alchemist.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ntpx.alchemist.AppContainer
import com.ntpx.alchemist.BuildInfo
import com.ntpx.alchemist.agent.AuditEntry
import com.ntpx.alchemist.agent.AuditLog
import com.ntpx.alchemist.agent.AuditType
import com.ntpx.alchemist.agent.ChainVerification
import com.ntpx.alchemist.agent.Grant
import com.ntpx.alchemist.agent.Permission
import com.ntpx.alchemist.data.AppSettings
import com.ntpx.alchemist.ui.components.Badge
import com.ntpx.alchemist.ui.components.ChipGroup
import com.ntpx.alchemist.ui.components.GlassCard
import com.ntpx.alchemist.ui.components.SectionHeader
import com.ntpx.alchemist.ui.theme.Ntpx
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val grants: Map<Permission, Grant> = emptyMap(),
    val audit: List<AuditEntry> = emptyList(),
    val chain: ChainVerification = ChainVerification.Valid,
    val journalFailures: AppContainer.JournalFailures = AppContainer.JournalFailures(),
)

class SettingsViewModel(private val container: AppContainer, private val agentId: String) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        container.settings.settings,
        container.grants.overrides,
        container.audit.entries,
        container.journalWriteFailures,
    ) { settings, _, audit, failures ->
        SettingsUiState(
            settings = settings,
            grants = container.grants.snapshot(agentId),
            audit = audit.takeLast(AUDIT_VISIBLE).asReversed(),
            chain = container.audit.verify(),
            journalFailures = failures,
        )
    }
        // Re-verifying the chain hashes every retained entry; keep that off the main thread.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    val restoredChain: ChainVerification get() = container.restoredChain
    val corruptLines: Int get() = container.corruptJournalLines

    fun setAllowCloud(value: Boolean) {
        container.settings.update { it.copy(allowCloudModels = value) }
        container.recordSettingsChange("allowCloudModels=$value")
    }

    fun setAllowLan(value: Boolean) {
        container.settings.update { it.copy(allowLocalNetwork = value) }
        container.recordSettingsChange("allowLocalNetwork=$value")
    }

    fun setGrant(permission: Permission, grant: Grant) {
        container.grants.set(agentId, permission, grant)
        container.recordSettingsChange("grant $agentId ${permission.name}=${grant.name}")
    }

    fun resetGrants() {
        container.grants.reset(agentId)
        container.recordSettingsChange("grants reset for $agentId")
    }

    private companion object {
        const val AUDIT_VISIBLE = 100
    }
}

@Composable
fun SettingsScreen(vm: SettingsViewModel, contentPadding: PaddingValues) {
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
        item {
            GlassCard {
                SectionHeader("Model routing")
                ToggleRow(
                    "Allow cloud models",
                    "Off by default. Even when on, each agent also needs the Cloud models and Network permissions.",
                    state.settings.allowCloudModels,
                    vm::setAllowCloud,
                )
                ToggleRow(
                    "Allow local-network models",
                    "Models served from your own machines, e.g. Ollama on your PC.",
                    state.settings.allowLocalNetwork,
                    vm::setAllowLan,
                )
            }
        }
        item {
            GlassCard {
                SectionHeader("Agent permissions") { TextButton(onClick = vm::resetGrants) { Text("RESET") } }
                Text("Applies to the ${AppContainer.DEFAULT_AGENT_ID} agent. Ask shows a prompt each time a step needs it.", style = MaterialTheme.typography.bodyMedium, color = Ntpx.Muted)
                Permission.entries.forEach { permission ->
                    val current = state.grants[permission] ?: Grant.DENY
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(permission.label, style = MaterialTheme.typography.titleSmall)
                        Text(permission.description, style = MaterialTheme.typography.bodyMedium, color = Ntpx.Muted)
                        ChipGroup(
                            options = Grant.entries,
                            selected = setOf(current),
                            label = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                            onToggle = { vm.setGrant(permission, it) },
                            accent = when (current) {
                                Grant.ALLOW -> Ntpx.Success
                                Grant.ASK -> Ntpx.Warning
                                Grant.DENY -> Ntpx.Danger
                            },
                        )
                    }
                }
            }
        }
        item {
            GlassCard {
                SectionHeader("Audit log") { ChainBadge(state.chain) }
                Text("${state.audit.size} most recent shown · hash-chained, append-only, stored on this device.", style = MaterialTheme.typography.bodyMedium, color = Ntpx.Muted)
                if (state.journalFailures.count > 0) {
                    Text(
                        "${state.journalFailures.count} audit entr${if (state.journalFailures.count == 1) "y" else "ies"} could not be saved to storage" +
                            (state.journalFailures.lastError?.let { " ($it)" } ?: "") + ". The on-disk journal is incomplete.",
                        color = Ntpx.Danger,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (vm.restoredChain is ChainVerification.Broken || vm.corruptLines > 0) {
                    Text(
                        "The stored journal failed verification at startup" +
                            (if (vm.corruptLines > 0) " (${vm.corruptLines} unreadable lines)" else "") + ". Earlier history may have been altered.",
                        color = Ntpx.Danger,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        // Keys must stay unique even for a corrupted journal with repeated sequence numbers.
        itemsIndexed(state.audit, key = { index, entry -> "$index:${entry.hash}" }) { _, entry -> AuditRow(entry) }
        item {
            Text(
                "NTPX AI Alchemist v${BuildInfo.VERSION} · offline build (no network permission)",
                style = MaterialTheme.typography.labelSmall,
                color = Ntpx.Muted,
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Ntpx.Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChainBadge(chain: ChainVerification) = when (chain) {
    ChainVerification.Valid -> Badge("chain verified", Ntpx.Success)
    is ChainVerification.Broken -> Badge("broken at #${chain.atSequence}", Ntpx.Danger)
}

@Composable
private fun AuditRow(entry: AuditEntry) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val timeFormat = androidx.compose.runtime.remember(locale) { SimpleDateFormat("MMM d HH:mm:ss", locale) }
    val color = when (entry.type) {
        AuditType.PERMISSION_DENIED, AuditType.STEP_FAILED -> Ntpx.Danger
        AuditType.PERMISSION_GRANTED, AuditType.STEP_SUCCEEDED -> Ntpx.Success
        AuditType.TASK_CANCELLED, AuditType.SETTINGS_CHANGED -> Ntpx.Warning
        else -> Ntpx.Cyan
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Badge(entry.type.name.replace('_', ' '), color)
            Text("#${entry.sequence} · ${timeFormat.format(Date(entry.timestampMillis))}", style = MaterialTheme.typography.labelSmall, color = Ntpx.Muted)
        }
        Text(entry.detail, style = MaterialTheme.typography.bodySmall, maxLines = 3)
        Text(entry.hash.take(16) + "…", style = MaterialTheme.typography.labelSmall, color = Ntpx.Muted)
    }
}
