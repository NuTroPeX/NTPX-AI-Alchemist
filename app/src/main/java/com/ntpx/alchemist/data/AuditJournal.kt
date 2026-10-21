package com.ntpx.alchemist.data

import com.ntpx.alchemist.agent.AuditEntry
import com.ntpx.alchemist.agent.AuditHashing
import com.ntpx.alchemist.agent.ChainAnchor
import com.ntpx.alchemist.agent.AuditType
import org.json.JSONObject
import java.io.File

/**
 * Append-only JSON-lines journal for the audit chain in app-private storage.
 * Lines that fail to parse are skipped and counted; chain verification then reports the gap.
 */
class AuditJournal(private val file: File, private val maxLines: Int = 5_000) {

    /** Set false when the loaded chain failed verification, so no compaction can re-anchor a tampered head. */
    @Volatile
    var compactionAllowed: Boolean = true

    /** [anchor] is where [entries] must start: genesis, or the checkpoint written by the last compaction. */
    data class Loaded(val entries: List<AuditEntry>, val corruptLines: Int, val anchor: ChainAnchor)

    /** Lines currently on disk; compaction keeps this at most [maxLines] + [slack]. */
    private var lineCount = 0
    private val slack = (maxLines / 5).coerceAtLeast(1)

    /** Largest number of entries the file can hold between compactions. */
    val capacity: Int get() = maxLines + slack

    private val anchorFile = File(file.parentFile, file.name + ".anchor")

    /** Reads the journal (bounded by compaction) and its anchor checkpoint. */
    @Synchronized
    fun load(): Loaded {
        val anchor = readAnchor()
        if (!file.exists()) return Loaded(emptyList(), 0, anchor)
        val tail = ArrayDeque<String>(capacity)
        var total = 0
        file.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { line ->
                total++
                if (tail.size == capacity) tail.removeFirst()
                tail.addLast(line)
            }
        }
        lineCount = total
        var corrupt = 0
        val entries = tail.mapNotNull { line ->
            decode(line) ?: run {
                corrupt++
                null
            }
        }
        // More lines than compaction ever leaves means the file grew outside this class.
        if (total > capacity) corrupt += total - capacity
        return Loaded(entries, corrupt, anchor)
    }

    private fun readAnchor(): ChainAnchor {
        if (!anchorFile.exists()) return ChainAnchor.GENESIS
        val parts = anchorFile.readText().trim().split('\t')
        val sequence = parts.getOrNull(0)?.toLongOrNull()
        val hash = parts.getOrNull(1)
        // An unreadable checkpoint can't vouch for anything; fall back to requiring the full chain.
        return if (sequence != null && hash != null && hash.length == AuditHashing.GENESIS.length) ChainAnchor(sequence, hash) else ChainAnchor.GENESIS
    }

    /** Appends one entry and compacts once the file outgrows its bound, so disk use stays bounded. */
    @Synchronized
    fun append(entry: AuditEntry) {
        file.parentFile?.mkdirs()
        file.appendText(encode(entry) + "\n")
        lineCount++
        if (compactionAllowed && lineCount > maxLines + slack) compact()
    }

    /** Rewrites the file keeping only the newest [maxLines] entries. Call off the main thread. */
    @Synchronized
    fun compact() {
        if (!file.exists()) return
        val tail = ArrayDeque<String>(maxLines)
        file.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { line ->
                if (tail.size == maxLines) tail.removeFirst()
                tail.addLast(line)
            }
        }
        if (tail.size == lineCount && tail.size <= maxLines) return
        val newHead = tail.firstOrNull()?.let(::decode)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(tail.joinToString("\n", postfix = if (tail.isEmpty()) "" else "\n"))
        // Record the checkpoint first: a crash between the two writes leaves a journal that fails
        // verification (visible) rather than one that silently passes with a dropped prefix.
        if (newHead != null) anchorFile.writeText("${newHead.sequence}\t${newHead.previousHash}\n")
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
        lineCount = tail.size
    }

    private fun encode(e: AuditEntry): String = JSONObject()
        .put("seq", e.sequence)
        .put("ts", e.timestampMillis)
        .put("agent", e.agentId)
        .put("task", e.taskId)
        .put("type", e.type.name)
        .put("detail", e.detail)
        .put("prev", e.previousHash)
        .put("hash", e.hash)
        .toString()

    private fun decode(line: String): AuditEntry? = runCatching {
        val o = JSONObject(line)
        AuditEntry(
            sequence = o.getLong("seq"),
            timestampMillis = o.getLong("ts"),
            agentId = o.getString("agent"),
            taskId = o.getString("task"),
            type = AuditType.valueOf(o.getString("type")),
            detail = o.getString("detail"),
            previousHash = o.getString("prev"),
            hash = o.getString("hash"),
        )
    }.getOrNull()
}
