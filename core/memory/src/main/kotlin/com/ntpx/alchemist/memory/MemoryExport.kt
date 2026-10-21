package com.ntpx.alchemist.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Portable JSON export so the user owns their memory and can move or audit it. */
@Serializable
data class MemoryExport(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: Long,
    val entries: List<MemoryEntry>,
) {
    companion object {
        const val FORMAT = "ntpx-alchemist-memory"
        const val VERSION = 1
    }
}

/** Supplies entries in stable order, one page at a time. Return an empty page when done. */
fun interface MemoryPager {
    suspend fun next(after: MemoryEntry?, limit: Int): List<MemoryEntry>
}

object MemoryExportCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    const val PAGE_SIZE = 200

    /**
     * Streams an export to [out] page by page, so peak memory is one page of entries rather than
     * the whole store. The output is the same document [decode] reads.
     */
    suspend fun write(out: Appendable, exportedAt: Long, pager: MemoryPager, pageSize: Int = PAGE_SIZE): Int {
        require(pageSize > 0) { "pageSize must be > 0" }
        out.append("{\"format\":").append(json.encodeToString(String.serializer(), MemoryExport.FORMAT))
        out.append(",\"version\":").append(MemoryExport.VERSION.toString())
        out.append(",\"exportedAt\":").append(exportedAt.toString())
        out.append(",\"entries\":[\n")
        var count = 0
        var last: MemoryEntry? = null
        while (true) {
            val page = pager.next(last, pageSize)
            if (page.isEmpty()) break
            for (entry in page) {
                if (count > 0) out.append(",\n")
                out.append(json.encodeToString(MemoryEntry.serializer(), entry))
                count++
            }
            last = page.last()
            if (page.size < pageSize) break
        }
        out.append("\n]}\n")
        return count
    }

    /** Convenience for small, already-loaded lists (tests, in-memory stores). */
    suspend fun encode(entries: List<MemoryEntry>, exportedAt: Long): String {
        val sorted = entries.sortedWith(EXPORT_ORDER)
        return buildString {
            write(this, exportedAt, { after, limit ->
                val start = if (after == null) 0 else sorted.indexOfFirst { EXPORT_ORDER.compare(it, after) > 0 }.let { if (it < 0) sorted.size else it }
                sorted.subList(start, minOf(sorted.size, start + limit))
            })
        }
    }

    /** Oldest first, ties broken by id; matches the Room keyset query. */
    val EXPORT_ORDER: Comparator<MemoryEntry> = compareBy<MemoryEntry> { it.createdAt }.thenBy { it.id }

    fun decode(text: String): Result<MemoryExport> = runCatching {
        val export = json.decodeFromString(MemoryExport.serializer(), text)
        require(export.format == MemoryExport.FORMAT) { "Not an NTPX Alchemist memory export" }
        require(export.version == MemoryExport.VERSION) { "Unsupported export version ${export.version}" }
        export
    }
}
