package com.ntpx.alchemist.ui.memory

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ntpx.alchemist.memory.MemorySummary
import com.ntpx.alchemist.memory.toSummary
import com.ntpx.alchemist.memory.MemoryExportCodec
import com.ntpx.alchemist.memory.room.RoomMemoryStore
import com.ntpx.alchemist.ui.components.Badge
import com.ntpx.alchemist.ui.components.EmptyState
import com.ntpx.alchemist.ui.components.GlassCard
import com.ntpx.alchemist.ui.components.SectionHeader
import com.ntpx.alchemist.ui.theme.Ntpx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

data class MemoryUiState(
    val entries: List<MemorySummary> = emptyList(),
    val total: Int = 0,
    val query: String = "",
    val results: List<MemorySummary>? = null,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MemoryViewModel(private val store: RoomMemoryStore) : ViewModel() {
    private val query = MutableStateFlow("")
    private val messages = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = messages.asStateFlow()

    /**
     * Search results derive from the query AND store changes, so writes from anywhere (the agent,
     * imports, other screens) refresh them. mapLatest cancels a stale search when either changes.
     */
    private val results: Flow<List<MemorySummary>?> =
        combine(query.debounce { if (it.isBlank()) 0 else SEARCH_DEBOUNCE_MS }, store.observeCount()) { q, _ -> q }
            .mapLatest { q -> if (q.isBlank()) null else store.search(q, limit = SEARCH_LIMIT).map { it.entry.toSummary() } }
            .onStart { emit(null) }

    val state: StateFlow<MemoryUiState> = combine(
        store.observeSummaries(LIST_LIMIT),
        store.observeCount(),
        query,
        results,
    ) { entries, total, q, r -> MemoryUiState(entries, total, q, if (q.isBlank()) null else r) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoryUiState())

    fun setQuery(text: String) {
        query.value = text
    }

    /** [onSaved] runs only after the insert succeeds, so a rejected draft stays in the editor. */
    fun add(content: String, tags: String, onSaved: () -> Unit) {
        if (content.isBlank()) return
        val tagSet = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        viewModelScope.launch {
            runCatching { store.put(content, tags = tagSet) }
                .onFailure { messages.value = "Not saved: ${it.message}" }
                .onSuccess { onSaved() }
        }
    }

    /** Uses an id-based update so a truncated preview can never overwrite stored content. */
    fun togglePin(item: MemorySummary) = viewModelScope.launch {
        store.setPinned(item.entry.id, !item.entry.pinned)
    }

    fun delete(item: MemorySummary) = viewModelScope.launch {
        store.delete(item.entry.id)
    }

    fun clearAll() = viewModelScope.launch {
        val removed = store.clear()
        query.value = ""
        messages.value = "Deleted $removed entries"
    }

    /** Streams every entry to a user-chosen document, one page at a time. */
    fun exportTo(context: Context, uri: Uri) = viewModelScope.launch {
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                (context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open file")).bufferedWriter().use { writer ->
                    MemoryExportCodec.write(writer, System.currentTimeMillis(), store.exportPager)
                }
            }
        }
        messages.value = outcome.fold({ "Exported $it entries" }, { "Export failed: ${it.message}" })
    }

    /**
     * Streams the file twice: once to validate every line, then to import in batches inside a
     * single transaction. A bad file, or any failure mid-import, changes nothing; and any export
     * this app writes can be restored regardless of size.
     */
    fun import(context: Context, uri: Uri) = viewModelScope.launch {
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                fun open() = context.contentResolver.openInputStream(uri)?.bufferedReader() ?: error("Could not open file")
                open().use { reader -> MemoryExportCodec.read(reader) { } }
                store.importAtomically { sink ->
                    open().use { reader -> MemoryExportCodec.read(reader) { batch -> sink(batch) } }.second
                }
            }
        }
        messages.value = outcome.fold({ "Imported $it entries" }, { "Import failed: ${it.message}" })
    }

    fun consumeMessage() {
        messages.value = null
    }

    companion object {
        /** Rows kept in the on-screen list; search reaches everything. */
        const val LIST_LIMIT = 200
        const val SEARCH_LIMIT = 50
        const val SEARCH_DEBOUNCE_MS = 150L
        const val EXPORT_FILE_NAME = "alchemist-memory.jsonl"
        const val EXPORT_MIME_TYPE = "application/x-ndjson"
    }
}

@Composable
fun MemoryScreen(vm: MemoryViewModel, contentPadding: PaddingValues) {
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var draft by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MemoryViewModel.EXPORT_MIME_TYPE)) { uri ->
        if (uri != null) vm.exportTo(context.applicationContext, uri)
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(context.applicationContext, uri)
    }

    val visible = state.results ?: state.entries

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
                SectionHeader("New memory")
                OutlinedTextField(draft, { draft = it }, placeholder = { Text("Something worth remembering") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(tags, { tags = it }, placeholder = { Text("tags, comma separated") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        vm.add(draft, tags) {
                            draft = ""
                            tags = ""
                        }
                    },
                    enabled = draft.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Ntpx.Violet),
                ) { Text("SAVE") }
            }
        }
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("Search memory") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            SectionHeader(
                when {
                    state.results != null -> "${visible.size} matches"
                    state.total > state.entries.size -> "${state.entries.size} of ${state.total} shown"
                    else -> "${state.total} stored"
                },
            ) {
                Row {
                    TextButton(onClick = { exporter.launch(MemoryViewModel.EXPORT_FILE_NAME) }, enabled = state.total > 0) { Text("EXPORT") }
                    TextButton(onClick = { importer.launch(arrayOf(MemoryViewModel.EXPORT_MIME_TYPE, "application/json", "text/plain", "application/octet-stream")) }) { Text("IMPORT") }
                    TextButton(onClick = { confirmClear = true }, enabled = state.total > 0) { Text("CLEAR", color = Ntpx.Danger) }
                }
            }
        }
        message?.let { text ->
            item {
                GlassCard(accent = Ntpx.Cyan) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text, modifier = Modifier.weight(1f))
                        TextButton(onClick = vm::consumeMessage) { Text("OK") }
                    }
                }
            }
        }
        if (visible.isEmpty()) {
            item {
                if (state.results != null) EmptyState("No matches", "Try different words or a tag.")
                else EmptyState("Memory is empty", "Saved notes and agent outcomes appear here. Everything stays on this device.")
            }
        }
        items(visible, key = { it.entry.id }) { item ->
            MemoryCard(item, onPin = { vm.togglePin(item) }, onDelete = { vm.delete(item) })
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all memory?") },
            text = { Text("This permanently removes ${state.total} entries from this device. Export first if you want a copy.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAll()
                    confirmClear = false
                }) { Text("DELETE ALL", color = Ntpx.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("CANCEL") } },
        )
    }
}

@Composable
private fun MemoryCard(item: MemorySummary, onPin: () -> Unit, onDelete: () -> Unit) {
    val entry = item.entry
    GlassCard(accent = if (entry.pinned) Ntpx.Magenta.copy(alpha = 0.6f) else Ntpx.GlassBorder) {
        Text(if (item.truncated) entry.content + "…" else entry.content, style = MaterialTheme.typography.bodyLarge)
        if (entry.tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { entry.tags.sorted().take(4).forEach { Badge(it, Ntpx.Violet) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.updatedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = Ntpx.Muted,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onPin) {
                Icon(Icons.Outlined.PushPin, contentDescription = if (entry.pinned) "Unpin" else "Pin", tint = if (entry.pinned) Ntpx.Magenta else Ntpx.Muted)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = Ntpx.Muted) }
        }
    }
}
