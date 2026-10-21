package com.ntpx.alchemist.memory

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        tags.forEach { require(TAG.matches(it)) { "tag '$it' must match ${TAG.pattern}" } }
    }

    companion object {
        const val MAX_CONTENT = 20_000
        val NAMESPACE = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")
        val TAG = Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,47}$")
        const val DEFAULT_NAMESPACE = "default"
    }
}

data class ScoredMemory(val entry: MemoryEntry, val score: Double)

/** User-controlled local memory. Nothing here leaves the device. */
interface MemoryStore {
    fun observe(namespace: String? = null): Flow<List<MemoryEntry>>

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
internal fun normaliseTags(tags: Set<String>): Set<String> =
    tags.map { it.trim().replace(Regex("\\s+"), "-").lowercase() }.filter { it.isNotEmpty() }.toSet()

class InMemoryMemoryStore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String = { UUID.randomUUID().toString() },
    private val ranker: MemoryRanker = MemoryRanker(),
) : MemoryStore {
    private val state = MutableStateFlow<List<MemoryEntry>>(emptyList())
    val entries: StateFlow<List<MemoryEntry>> = state.asStateFlow()

    override fun observe(namespace: String?): Flow<List<MemoryEntry>> =
        kotlinx.coroutines.flow.flow { state.collect { list -> emit(sortForDisplay(list.filter { namespace == null || it.namespace == namespace })) } }

    override suspend fun put(content: String, namespace: String, tags: Set<String>, pinned: Boolean): MemoryEntry {
        val now = clock()
        val entry = MemoryEntry(ids(), namespace, content.trim(), normaliseTags(tags), now, now, pinned)
        state.update { it + entry }
        return entry
    }

    override suspend fun get(id: String): MemoryEntry? = state.value.firstOrNull { it.id == id }

    override suspend fun update(entry: MemoryEntry): Boolean {
        var found = false
        state.update { list ->
            list.map {
                if (it.id == entry.id) {
                    found = true
                    entry.copy(createdAt = it.createdAt, updatedAt = clock())
                } else {
                    it
                }
            }
        }
        return found
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
