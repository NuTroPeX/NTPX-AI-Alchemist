package com.ntpx.alchemist.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ntpx.alchemist.agent.AgentRuntime
import com.ntpx.alchemist.agent.Planner
import com.ntpx.alchemist.agent.TaskEvent
import com.ntpx.alchemist.agent.TaskStatus
import com.ntpx.alchemist.data.McpToolPort
import com.ntpx.alchemist.mcp.ToolDefinition
import com.ntpx.alchemist.ui.components.Badge
import com.ntpx.alchemist.ui.components.EmptyState
import com.ntpx.alchemist.ui.components.GlassCard
import com.ntpx.alchemist.ui.components.SectionHeader
import com.ntpx.alchemist.ui.theme.Ntpx
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class StepState { PENDING, RUNNING, DONE, DENIED, FAILED }

data class StepView(val index: Int, val label: String, val state: StepState, val detail: String? = null)

data class AgentsUiState(
    val goal: String = "",
    val running: Boolean = false,
    val steps: List<StepView> = emptyList(),
    val status: TaskStatus? = null,
    val cancelled: Boolean = false,
    val error: String? = null,
    val tools: List<ToolDefinition> = emptyList(),
)

class AgentsViewModel(
    private val runtime: AgentRuntime,
    private val planner: Planner,
    private val tools: McpToolPort,
    private val agentId: String,
) : ViewModel() {
    private val state = MutableStateFlow(AgentsUiState())
    val ui: StateFlow<AgentsUiState> = state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch { refreshTools() }
    }

    fun setGoal(goal: String) = state.update { it.copy(goal = goal) }

    fun run() {
        if (state.value.running) return
        val goal = state.value.goal
        val task = try {
            planner.plan(agentId, goal)
        } catch (e: IllegalArgumentException) {
            state.update { it.copy(error = e.message) }
            return
        }
        state.update {
            it.copy(
                running = true,
                status = null,
                cancelled = false,
                error = null,
                steps = task.steps.mapIndexed { i, s -> StepView(i, s.label, StepState.PENDING) },
            )
        }
        job = viewModelScope.launch {
            refreshTools()
            try {
                runtime.run(task).collect(::onEvent)
            } finally {
                state.update { s ->
                    s.copy(
                        running = false,
                        cancelled = s.status == null,
                        steps = if (s.status == null) s.steps.map { if (it.state == StepState.RUNNING) it.copy(state = StepState.FAILED, detail = "Cancelled") else it } else s.steps,
                    )
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun refreshTools() {
        runCatching { tools.refresh() }
            .onSuccess { list -> state.update { it.copy(tools = list) } }
            .onFailure { e -> state.update { it.copy(error = "Tool discovery failed: ${e.message}") } }
    }

    private fun onEvent(event: TaskEvent) {
        state.update { s ->
            when (event) {
                is TaskEvent.Started -> s
                is TaskEvent.StepStarted -> s.withStep(event.index) { it.copy(state = StepState.RUNNING) }
                is TaskEvent.StepSucceeded -> s.withStep(event.index) { it.copy(state = StepState.DONE, detail = event.output) }
                is TaskEvent.StepDenied -> s.withStep(event.index) { it.copy(state = StepState.DENIED, detail = "Denied: ${event.permission.label}") }
                is TaskEvent.StepFailed -> if (event.index < 0) s.copy(error = event.error) else s.withStep(event.index) { it.copy(state = StepState.FAILED, detail = event.error) }
                is TaskEvent.Finished -> s.copy(status = event.status)
            }
        }
    }

    private fun AgentsUiState.withStep(index: Int, f: (StepView) -> StepView) =
        copy(steps = steps.map { if (it.index == index) f(it) else it })
}

@Composable
fun AgentsScreen(vm: AgentsViewModel, contentPadding: PaddingValues) {
    val state by vm.ui.collectAsStateWithLifecycle()
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
                SectionHeader("Goal")
                OutlinedTextField(
                    value = state.goal,
                    onValueChange = vm::setGoal,
                    placeholder = { Text("What should the agent work on?") },
                    enabled = !state.running,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Plan: recall memories → draft with a routed model → analyse with a tool → save the outcome. " +
                        "Each step is permission-checked and written to the audit log.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ntpx.Muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = vm::run,
                        enabled = !state.running && state.goal.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Ntpx.Cyan, contentColor = Ntpx.Void),
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Text("RUN")
                    }
                    OutlinedButton(onClick = vm::cancel, enabled = state.running) {
                        Icon(Icons.Outlined.Stop, contentDescription = null)
                        Text("CANCEL")
                    }
                }
                state.error?.let { Text(it, color = Ntpx.Danger, style = MaterialTheme.typography.bodyMedium) }
            }
        }

        if (state.steps.isNotEmpty()) {
            item {
                SectionHeader("Run") {
                    when {
                        state.running -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ntpx.Cyan)
                        state.cancelled -> Badge("cancelled", Ntpx.Warning)
                        state.status != null -> Badge(state.status!!.name.replace('_', ' '), statusColor(state.status!!))
                    }
                }
            }
            items(state.steps, key = { it.index }) { StepRow(it) }
        } else {
            item { EmptyState("No runs yet", "Enter a goal and press RUN.") }
        }

        item {
            GlassCard {
                SectionHeader("MCP tools") { Badge("${state.tools.size} loaded", Ntpx.Violet) }
                state.tools.forEach { tool ->
                    Text(tool.name, style = MaterialTheme.typography.bodySmall, color = Ntpx.Cyan)
                    Text(tool.description, style = MaterialTheme.typography.bodyMedium, color = Ntpx.Muted)
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: StepView) {
    val (icon, tint) = when (step.state) {
        StepState.PENDING -> Icons.Outlined.RadioButtonUnchecked to Ntpx.Muted
        StepState.RUNNING -> Icons.Outlined.PlayArrow to Ntpx.Cyan
        StepState.DONE -> Icons.Outlined.CheckCircle to Ntpx.Success
        StepState.DENIED -> Icons.Outlined.Block to Ntpx.Warning
        StepState.FAILED -> Icons.Outlined.ErrorOutline to Ntpx.Danger
    }
    GlassCard(accent = tint.copy(alpha = 0.5f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = step.state.name, tint = tint)
            Text("${step.index + 1}. ${step.label}", style = MaterialTheme.typography.titleSmall)
        }
        step.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (step.state == StepState.DONE) Ntpx.Text else tint) }
    }
}

private fun statusColor(status: TaskStatus): Color = when (status) {
    TaskStatus.COMPLETED -> Ntpx.Success
    TaskStatus.DENIED -> Ntpx.Warning
    TaskStatus.FAILED, TaskStatus.TIMED_OUT -> Ntpx.Danger
}
