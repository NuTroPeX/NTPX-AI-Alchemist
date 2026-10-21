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

    @Synchronized
    fun load(): Loaded {
        if (!file.exists()) return Loaded(emptyList(), 0)
        var corrupt = 0
        val entries = file.readLines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            decode(line) ?: run {
                corrupt++
                null
            }
        }
        return Loaded(entries.takeLast(maxLines), corrupt)
    }

    @Synchronized
    fun append(entry: AuditEntry) {
        file.parentFile?.mkdirs()
        file.appendText(encode(entry) + "\n")
    }

    /** Rewrites the file keeping only the newest [maxLines] entries. Call off the main thread. */
    @Synchronized
    fun compact() {
        if (!file.exists()) return
        val lines = file.readLines().filter { it.isNotBlank() }
        if (lines.size <= maxLines) return
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(lines.takeLast(maxLines).joinToString("\n", postfix = "\n"))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
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
