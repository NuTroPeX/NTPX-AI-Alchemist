package com.ntpx.alchemist.memory

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class MemoryEntry(
    val id: String,
    val namespace: String,
    val content: String,
    val tags: Set<String> = emptySet(),
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val pinned: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(NAMESPACE.matches(namespace)) { "namespace '$namespace' must match ${NAMESPACE.pattern}" }
        require(content.isNotBlank()) { "content must not be blank" }
        require(content.length <= MAX_CONTENT) { "content longer than $MAX_CONTENT characters" }
        require(tags.size <= MAX_TAGS) { "at most $MAX_TAGS tags per memory" }
        tags.forEach { require(TAG.matches(it)) { "tag '$it' must match ${TAG.pattern}" } }
    }

    companion object {
        const val MAX_CONTENT = 20_000
        const val MAX_TAGS = 32
        const val MAX_TAG_LENGTH = 48
        val NAMESPACE = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")
        val TAG = Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,47}$")
        const val DEFAULT_NAMESPACE = "default"
    }
}

data class ScoredMemory(val entry: MemoryEntry, val score: Double)

/**
 * A list row for display: [entry] carries at most [PREVIEW_CHARS] of content, so a screen of
 * summaries stays small however large individual memories are. Never write a summary back.
 */
data class MemorySummary(val entry: MemoryEntry, val contentLength: Int) {
    val truncated: Boolean get() = contentLength > entry.content.length

    companion object {
        const val PREVIEW_CHARS = 600
    }
}

/** User-controlled local memory. Nothing here leaves the device. */
interface MemoryStore {
    fun observe(namespace: String? = null): Flow<List<MemoryEntry>>

    /** The newest/pinned [limit] entries as previews, for list screens. */
    fun observeSummaries(limit: Int, namespace: String? = null): Flow<List<MemorySummary>>

    /** Entry count; re-emits on every change to the store, so it doubles as a change signal. */
    fun observeCount(namespace: String? = null): Flow<Int>

    suspend fun setPinned(id: String, pinned: Boolean): Boolean

    suspend fun put(content: String, namespace: String = MemoryEntry.DEFAULT_NAMESPACE, tags: Set<String> = emptySet(), pinned: Boolean = false): MemoryEntry

    suspend fun get(id: String): MemoryEntry?

    suspend fun update(entry: MemoryEntry): Boolean

    suspend fun search(query: String, namespace: String? = null, limit: Int = 10): List<ScoredMemory>

    suspend fun delete(id: String): Boolean

    /** Deletes every entry in [namespace], or everything when null. Returns the number removed. */
    suspend fun clear(namespace: String? = null): Int

    suspend fun all(namespace: String? = null): List<MemoryEntry>
}

/** Normalises tags so "Agent Run" and "agent-run" don't become two tags. */
fun normaliseTags(tags: Set<String>): Set<String> =
    tags.map { it.trim().replace(Regex("\\s+"), "-").lowercase() }.filter { it.isNotEmpty() }.toSet()

fun MemoryEntry.toSummary(): MemorySummary =
    MemorySummary(copy(content = content.take(MemorySummary.PREVIEW_CHARS)), content.length)

class InMemoryMemoryStore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String = { UUID.randomUUID().toString() },
    private val ranker: MemoryRanker = MemoryRanker(),
) : MemoryStore {
    private val state = MutableStateFlow<List<MemoryEntry>>(emptyList())
    val entries: StateFlow<List<MemoryEntry>> = state.asStateFlow()

    override fun observe(namespace: String?): Flow<List<MemoryEntry>> =
        kotlinx.coroutines.flow.flow { state.collect { list -> emit(sortForDisplay(list.filter { namespace == null || it.namespace == namespace })) } }

    override fun observeSummaries(limit: Int, namespace: String?): Flow<List<MemorySummary>> =
        observe(namespace).map { list -> list.take(limit).map { it.toSummary() } }

    override fun observeCount(namespace: String?): Flow<Int> = observe(namespace).map { it.size }

    override suspend fun setPinned(id: String, pinned: Boolean): Boolean {
        val existing = get(id) ?: return false
        return update(existing.copy(pinned = pinned))
    }

    override suspend fun put(content: String, namespace: String, tags: Set<String>, pinned: Boolean): MemoryEntry {
        val now = clock()
        val entry = MemoryEntry(ids(), namespace, content.trim(), normaliseTags(tags), now, now, pinned)
        state.update { it + entry }
        return entry
    }

    override suspend fun get(id: String): MemoryEntry? = state.value.firstOrNull { it.id == id }

    /** Result comes from the compare-and-set that actually committed. */
    override suspend fun update(entry: MemoryEntry): Boolean {
        while (true) {
            val list = state.value
            val existing = list.firstOrNull { it.id == entry.id } ?: return false
            val updated = entry.copy(tags = normaliseTags(entry.tags), createdAt = existing.createdAt, updatedAt = clock())
            if (state.compareAndSet(list, list.map { if (it.id == entry.id) updated else it })) return true
        }
    }

    override suspend fun search(query: String, namespace: String?, limit: Int): List<ScoredMemory> =
        ranker.rank(query, all(namespace), limit)

    override suspend fun delete(id: String): Boolean {
        var removed = false
        state.update { list -> list.filterNot { it.id == id }.also { removed = it.size != list.size } }
        return removed
    }

    override suspend fun clear(namespace: String?): Int {
        var removed = 0
        state.update { list ->
            val kept = list.filterNot { namespace == null || it.namespace == namespace }
            removed = list.size - kept.size
            kept
        }
        return removed
    }

    override suspend fun all(namespace: String?): List<MemoryEntry> =
        sortForDisplay(state.value.filter { namespace == null || it.namespace == namespace })
}

internal fun sortForDisplay(list: List<MemoryEntry>): List<MemoryEntry> =
    list.sortedWith(compareByDescending<MemoryEntry> { it.pinned }.thenByDescending { it.updatedAt }.thenBy { it.id })
