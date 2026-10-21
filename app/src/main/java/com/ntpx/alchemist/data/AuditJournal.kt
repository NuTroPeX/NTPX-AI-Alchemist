package com.ntpx.alchemist.data

import com.ntpx.alchemist.agent.AuditEntry
import com.ntpx.alchemist.agent.AuditType
import org.json.JSONObject
import java.io.File

/**
 * Append-only JSON-lines journal for the audit chain in app-private storage.
 * Lines that fail to parse are skipped and counted; chain verification then reports the gap.
 */
class AuditJournal(private val file: File, private val maxLines: Int = 5_000) {

    data class Loaded(val entries: List<AuditEntry>, val corruptLines: Int)

    /** Lines currently on disk; compaction keeps this at most [maxLines] + [slack]. */
    private var lineCount = 0
    private val slack = (maxLines / 5).coerceAtLeast(1)

    /** Reads the journal, keeping only the newest [maxLines] lines in memory while scanning. */
    @Synchronized
    fun load(): Loaded {
        if (!file.exists()) return Loaded(emptyList(), 0)
        val tail = ArrayDeque<String>(maxLines)
        var total = 0
        file.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { line ->
                total++
                if (tail.size == maxLines) tail.removeFirst()
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
        return Loaded(entries, corrupt)
    }

    /** Appends one entry and compacts once the file outgrows its bound, so disk use stays bounded. */
    @Synchronized
    fun append(entry: AuditEntry) {
        file.parentFile?.mkdirs()
        file.appendText(encode(entry) + "\n")
        lineCount++
        if (lineCount > maxLines + slack) compact()
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
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(tail.joinToString("\n", postfix = if (tail.isEmpty()) "" else "\n"))
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
