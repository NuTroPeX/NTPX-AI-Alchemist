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
import com.ntpx.alchemist.memory.MemoryEntry
import com.ntpx.alchemist.memory.MemoryExportCodec
import com.ntpx.alchemist.memory.room.RoomMemoryStore
import com.ntpx.alchemist.ui.components.Badge
import com.ntpx.alchemist.ui.components.EmptyState
import com.ntpx.alchemist.ui.components.GlassCard
import com.ntpx.alchemist.ui.components.SectionHeader
import com.ntpx.alchemist.ui.theme.Ntpx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val entries: List<MemoryEntry> = emptyList(),
    val query: String = "",
    val results: List<MemoryEntry>? = null,
)

class MemoryViewModel(private val store: RoomMemoryStore) : ViewModel() {
    private val query = MutableStateFlow("")
    private val results = MutableStateFlow<List<MemoryEntry>?>(null)
    private val messages = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = messages.asStateFlow()

    val state: StateFlow<MemoryUiState> = combine(store.observe(), query, results) { entries, q, r ->
        MemoryUiState(entries, q, r)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoryUiState())

    private var searchJob: Job? = null

    fun setQuery(text: String) {
        query.value = text
        searchJob?.cancel()
        if (text.isBlank()) {
            results.value = null
            return
        }
        searchJob = viewModelScope.launch { publishSearch(text) }
    }

    /** Publishes results only if [text] is still the visible query, so a slow older search can't win. */
    private suspend fun publishSearch(text: String) {
        val found = store.search(text, limit = 50).map { it.entry }
        if (query.value == text) results.value = found
    }

    /** [onSaved] runs only after the insert succeeds, so a rejected draft stays in the editor. */
    fun add(content: String, tags: String, onSaved: () -> Unit) {
        if (content.isBlank()) return
        val tagSet = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        viewModelScope.launch {
            runCatching { store.put(content, tags = tagSet) }
                .onFailure { messages.value = "Not saved: ${it.message}" }
                .onSuccess {
                    onSaved()
                    refreshSearch()
                }
        }
    }

    fun togglePin(entry: MemoryEntry) = viewModelScope.launch {
        store.update(entry.copy(pinned = !entry.pinned))
        refreshSearch()
    }

    fun delete(entry: MemoryEntry) = viewModelScope.launch {
        store.delete(entry.id)
        refreshSearch()
    }

    fun clearAll() = viewModelScope.launch {
        searchJob?.cancel()
        val removed = store.clear()
        results.value = null
        query.value = ""
        messages.value = "Deleted $removed entries"
    }

    /** Writes every entry to a user-chosen document (streamed to a file, so size is not bound by Binder limits). */
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

    fun import(context: Context, uri: Uri) = viewModelScope.launch {
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val reader = context.contentResolver.openInputStream(uri)?.bufferedReader() ?: error("Could not open file")
                val text = reader.use { readBounded(it, MAX_IMPORT_CHARS) } ?: throw IllegalArgumentException("File is too large")
                MemoryExportCodec.decode(text).getOrThrow()
            }
        }
        outcome
            .onSuccess { export ->
                messages.value = "Imported ${store.import(export.entries)} entries"
                refreshSearch()
            }
            .onFailure { messages.value = "Import failed: ${it.message}" }
    }

    fun consumeMessage() {
        messages.value = null
    }

    private suspend fun refreshSearch() {
        val q = query.value
        if (q.isNotBlank()) publishSearch(q)
    }

    companion object {
        const val MAX_IMPORT_CHARS = 20_000_000

        /** Reads at most [limit] characters; returns null if the source is longer, without loading the rest. */
        fun readBounded(reader: java.io.Reader, limit: Int): String? {
            val out = StringBuilder()
            val buffer = CharArray(8_192)
            while (true) {
                val n = reader.read(buffer)
                if (n < 0) return out.toString()
                if (out.length + n > limit) return null
                out.appendRange(buffer, 0, n)
            }
        }
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
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
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
            SectionHeader(if (state.results != null) "${visible.size} matches" else "${state.entries.size} stored") {
                Row {
                    TextButton(onClick = { exporter.launch("alchemist-memory.json") }, enabled = state.entries.isNotEmpty()) { Text("EXPORT") }
                    TextButton(onClick = { importer.launch(arrayOf("application/json", "text/plain")) }) { Text("IMPORT") }
                    TextButton(onClick = { confirmClear = true }, enabled = state.entries.isNotEmpty()) { Text("CLEAR", color = Ntpx.Danger) }
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
        items(visible, key = { it.id }) { entry ->
            MemoryCard(entry, onPin = { vm.togglePin(entry) }, onDelete = { vm.delete(entry) })
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all memory?") },
            text = { Text("This permanently removes ${state.entries.size} entries from this device. Export first if you want a copy.") },
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
private fun MemoryCard(entry: MemoryEntry, onPin: () -> Unit, onDelete: () -> Unit) {
    GlassCard(accent = if (entry.pinned) Ntpx.Magenta.copy(alpha = 0.6f) else Ntpx.GlassBorder) {
        Text(entry.content, style = MaterialTheme.typography.bodyLarge)
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
