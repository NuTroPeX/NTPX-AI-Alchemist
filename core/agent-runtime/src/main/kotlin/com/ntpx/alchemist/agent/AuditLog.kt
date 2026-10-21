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

sealed interface ChainVerification {
    data object Valid : ChainVerification
    data class Broken(val atSequence: Long, val reason: String) : ChainVerification
}

interface AuditLog {
    val entries: StateFlow<List<AuditEntry>>

    fun append(agentId: String, taskId: String, type: AuditType, detail: String): AuditEntry

    fun verify(): ChainVerification = verifyChain(entries.value)

    companion object {
        fun verifyChain(entries: List<AuditEntry>): ChainVerification {
            var expectedPrevious = entries.firstOrNull()?.previousHash ?: return ChainVerification.Valid
            var expectedSequence = entries.first().sequence
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
 * When [capacity] is exceeded the oldest entries are dropped; the retained tail still
 * verifies because its first entry carries the previous hash.
 */
class InMemoryAuditLog(
    restored: List<AuditEntry> = emptyList(),
    private val capacity: Int = 5_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onAppend: (AuditEntry) -> Unit = {},
) : AuditLog {
    init {
        require(capacity > 0) { "capacity must be > 0" }
    }

    private val lock = Any()
    private val state = MutableStateFlow(restored.takeLast(capacity))

    override val entries: StateFlow<List<AuditEntry>> = state.asStateFlow()

    override fun append(agentId: String, taskId: String, type: AuditType, detail: String): AuditEntry {
        val entry = synchronized(lock) {
            val last = state.value.lastOrNull()
            val sequence = (last?.sequence ?: -1L) + 1
            val previous = last?.hash ?: AuditHashing.GENESIS
            val timestamp = clock()
            val hash = AuditHashing.hash(sequence, timestamp, agentId, taskId, type, detail, previous)
            val created = AuditEntry(sequence, timestamp, agentId, taskId, type, detail, previous, hash)
            state.value = (state.value + created).takeLast(capacity)
            created
        }
        onAppend(entry)
        return entry
    }
}
