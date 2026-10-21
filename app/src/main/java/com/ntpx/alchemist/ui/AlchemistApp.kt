package com.ntpx.alchemist.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ntpx.alchemist.AppContainer
import com.ntpx.alchemist.agent.ConsentDecision
import com.ntpx.alchemist.ui.agents.AgentsScreen
import com.ntpx.alchemist.ui.agents.AgentsViewModel
import com.ntpx.alchemist.ui.components.NeonBackdrop
import com.ntpx.alchemist.ui.memory.MemoryScreen
import com.ntpx.alchemist.ui.memory.MemoryViewModel
import com.ntpx.alchemist.ui.registry.RegistryScreen
import com.ntpx.alchemist.ui.registry.RegistryViewModel
import com.ntpx.alchemist.ui.settings.SettingsScreen
import com.ntpx.alchemist.ui.settings.SettingsViewModel
import com.ntpx.alchemist.ui.theme.Ntpx

enum class Tab(val label: String, val icon: ImageVector) {
    REGISTRY("Registry", Icons.Outlined.Hub),
    AGENTS("Agents", Icons.Outlined.AutoAwesome),
    MEMORY("Memory", Icons.Outlined.Memory),
    SETTINGS("Security", Icons.Outlined.Shield),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlchemistApp(container: AppContainer) {
    var tab by rememberSaveable { mutableStateOf(Tab.AGENTS) }

    val registryVm = viewModel { RegistryViewModel(container.registry, container.catalogError) }
    val agentsVm = viewModel { AgentsViewModel(container.runtime, container.planner, container.tools, AppContainer.DEFAULT_AGENT_ID) }
    val memoryVm = viewModel { MemoryViewModel(container.memory) }
    val settingsVm = viewModel { SettingsViewModel(container, AppContainer.DEFAULT_AGENT_ID) }

    NeonBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = Ntpx.Cyan)) { append("NTPX") }
                                withStyle(SpanStyle(color = Ntpx.Muted)) { append(" // ") }
                                withStyle(SpanStyle(color = Ntpx.Text)) { append("ALCHEMIST") }
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Ntpx.Void.copy(alpha = 0.7f)),
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Ntpx.Navy.copy(alpha = 0.92f)) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label, style = MaterialTheme.typography.labelMedium) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Ntpx.Cyan,
                                selectedTextColor = Ntpx.Cyan,
                                indicatorColor = Ntpx.Violet.copy(alpha = 0.25f),
                                unselectedIconColor = Ntpx.Muted,
                                unselectedTextColor = Ntpx.Muted,
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            when (tab) {
                Tab.REGISTRY -> RegistryScreen(registryVm, padding)
                Tab.AGENTS -> AgentsScreen(agentsVm, padding)
                Tab.MEMORY -> MemoryScreen(memoryVm, padding)
                Tab.SETTINGS -> SettingsScreen(settingsVm, padding)
            }
        }
        ConsentDialog(container)
    }
}

@Composable
private fun ConsentDialog(container: AppContainer) {
    val pending by container.consent.pending.collectAsStateWithLifecycle()
    val current = pending ?: return
    val request = current.request
    AlertDialog(
        onDismissRequest = { container.consent.answer(current, ConsentDecision.DENY) },
        title = { Text("Allow ${request.permission.label}?") },
        text = {
            Column {
                Text("Agent '${request.agentId}' wants to: ${request.permission.description.lowercase()}.")
                Text("Step ${request.reason}", style = MaterialTheme.typography.bodySmall, color = Ntpx.Muted)
            }
        },
        confirmButton = {
            Column {
                TextButton(onClick = { container.consent.answer(current, ConsentDecision.ALLOW_ONCE) }) { Text("ALLOW ONCE") }
                TextButton(onClick = { container.consent.answer(current, ConsentDecision.ALLOW_ALWAYS) }) { Text("ALWAYS ALLOW") }
            }
        },
        dismissButton = {
            TextButton(onClick = { container.consent.answer(current, ConsentDecision.DENY) }) { Text("DENY", color = Ntpx.Danger) }
        },
    )
}
