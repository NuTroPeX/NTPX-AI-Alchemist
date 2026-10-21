package com.ntpx.alchemist.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

enum class AuditType {
    TASK_STARTED,
    STEP_STARTED,
    STEP_SUCCEEDED,
    STEP_FAILED,
    PERMISSION_GRANTED,
    PERMISSION_DENIED,
    TASK_FINISHED,
    TASK_CANCELLED,
    SETTINGS_CHANGED,
}

/**
 * One append-only audit record. [hash] covers every other field plus [previousHash],
 * so editing or removing any earlier entry breaks verification of everything after it.
 */
data class AuditEntry(
    val sequence: Long,
    val timestampMillis: Long,
    val agentId: String,
    val taskId: String,
    val type: AuditType,
    val detail: String,
    val previousHash: String,
    val hash: String,
) {
    fun computeHash(): String = AuditHashing.hash(sequence, timestampMillis, agentId, taskId, type, detail, previousHash)
}

object AuditHashing {
    const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"

    fun hash(
        sequence: Long,
        timestampMillis: Long,
        agentId: String,
        taskId: String,
        type: AuditType,
        detail: String,
        previousHash: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Length-prefix each field so "ab"+"c" and "a"+"bc" can never collide.
        listOf(sequence.toString(), timestampMillis.toString(), agentId, taskId, type.name, detail, previousHash)
            .forEach { field ->
                val bytes = field.toByteArray(Charsets.UTF_8)
                digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
                digest.update(':'.code.toByte())
                digest.update(bytes)
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Where a retained chain must start: the first entry's sequence and previous hash. Genesis for a
 * full log; after compaction, a checkpoint recorded at compaction time. Anchoring stops a cleanly
 * removed prefix from passing as legitimate compaction.
 */
data class ChainAnchor(val sequence: Long, val previousHash: String) {
    companion object {
        val GENESIS = ChainAnchor(0, AuditHashing.GENESIS)

        fun of(first: AuditEntry) = ChainAnchor(first.sequence, first.previousHash)
    }
}

sealed interface ChainVerification {
    data object Valid : ChainVerification
    data class Broken(val atSequence: Long, val reason: String) : ChainVerification
}

interface AuditLog {
    val entries: StateFlow<List<AuditEntry>>

    fun append(agentId: String, taskId: String, type: AuditType, detail: String): AuditEntry

    /** The trusted start of [entries]; see [ChainAnchor]. */
    val anchor: ChainAnchor

    fun verify(): ChainVerification = verifyChain(entries.value, anchor)

    companion object {
        /** Verifies links and hashes; with [anchor], also that the chain starts exactly there. */
        fun verifyChain(entries: List<AuditEntry>, anchor: ChainAnchor? = null): ChainVerification {
            // An empty log is only consistent with a genesis start; an empty log under a later
            // checkpoint means every retained record was removed.
            val first = entries.firstOrNull() ?: return if (anchor == null || anchor == ChainAnchor.GENESIS) {
                ChainVerification.Valid
            } else {
                ChainVerification.Broken(anchor.sequence, "All records after checkpoint #${anchor.sequence} are missing")
            }
            if (anchor != null && ChainAnchor.of(first) != anchor) {
                return ChainVerification.Broken(first.sequence, "Chain does not start at its recorded anchor #${anchor.sequence}")
            }
            var expectedPrevious = first.previousHash
            var expectedSequence = first.sequence
            for (entry in entries) {
                if (entry.sequence != expectedSequence) {
                    return ChainVerification.Broken(entry.sequence, "Expected sequence $expectedSequence")
                }
                if (entry.previousHash != expectedPrevious) {
                    return ChainVerification.Broken(entry.sequence, "Previous-hash link mismatch")
                }
                if (entry.computeHash() != entry.hash) {
                    return ChainVerification.Broken(entry.sequence, "Entry contents do not match hash")
                }
                expectedPrevious = entry.hash
                expectedSequence += 1
            }
            return ChainVerification.Valid
        }
    }
}

/**
 * Thread-safe hash-chained audit log held in memory. [onAppend] lets the host persist
 * each entry (the app writes JSON lines to private storage) without this module knowing how.
 * [onAppend] runs under the log's lock, in sequence order, so it must be quick (e.g. enqueue to a writer thread).
 * When [capacity] is exceeded the oldest entries are dropped; the retained tail still
 * verifies because its first entry carries the previous hash.
 */
class InMemoryAuditLog(
    restored: List<AuditEntry> = emptyList(),
    private val capacity: Int = 5_000,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Trusted start of [restored] (from the journal's checkpoint); genesis when the log is complete. */
    restoredAnchor: ChainAnchor = ChainAnchor.GENESIS,
    private val onAppend: (AuditEntry) -> Unit = {},
) : AuditLog {
    init {
        require(capacity > 0) { "capacity must be > 0" }
    }

    private val lock = Any()
    private val state = MutableStateFlow(restored.takeLast(capacity))

    @Volatile
    override var anchor: ChainAnchor =
        if (restored.size > capacity) ChainAnchor.of(state.value.first()) else restoredAnchor
        private set

    override val entries: StateFlow<List<AuditEntry>> = state.asStateFlow()

    /** Reads entries and anchor under the same lock, so a concurrent trim can't pair them inconsistently. */
    override fun verify(): ChainVerification {
        val (snapshot, start) = synchronized(lock) { state.value to anchor }
        return AuditLog.verifyChain(snapshot, start)
    }

    override fun append(agentId: String, taskId: String, type: AuditType, detail: String): AuditEntry {
        // Bounded so one huge goal or output can't bloat the log or the on-disk journal.
        val detail = if (detail.length > MAX_DETAIL_CHARS) detail.take(MAX_DETAIL_CHARS - 1) + "…" else detail
        val entry = synchronized(lock) {
            val last = state.value.lastOrNull()
            val sequence = (last?.sequence ?: -1L) + 1
            val previous = last?.hash ?: AuditHashing.GENESIS
            val timestamp = clock()
            val hash = AuditHashing.hash(sequence, timestamp, agentId, taskId, type, detail, previous)
            val created = AuditEntry(sequence, timestamp, agentId, taskId, type, detail, previous, hash)
            val next = state.value + created
            if (next.size > capacity) {
                val kept = next.takeLast(capacity)
                anchor = ChainAnchor.of(kept.first()) // our own trim, so the new head is trusted
                state.value = kept
            } else {
                state.value = next
            }
            // Inside the lock so persistence sees entries in sequence order; keep callbacks fast (enqueue, don't block).
            onAppend(created)
            created
        }
        return entry
    }

    companion object {
        const val MAX_DETAIL_CHARS = 2_000
    }
}
