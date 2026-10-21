package com.ntpx.alchemist.memory

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Reader

/**
 * Portable export so the user owns their memory and can move or audit it.
 *
 * Format (JSON Lines): the first line is a [MemoryExportHeader]; every following non-blank line is
 * one [MemoryEntry]. Both writing and reading stream one entry at a time, so any export this app
 * writes can be restored by it, whatever the store's size.
 */
@Serializable
data class MemoryExportHeader(
    // No defaults: a header missing these fields must not decode as a current-format export.
    val format: String,
    val version: Int,
    val exportedAt: Long,
)

/** A fully read export; only for small exports and tests. */
data class MemoryExport(val header: MemoryExportHeader, val entries: List<MemoryEntry>) {
    val exportedAt: Long get() = header.exportedAt
}

/** Supplies entries in stable order, one page at a time. Return an empty page when done. */
fun interface MemoryPager {
    suspend fun next(after: MemoryEntry?, limit: Int): List<MemoryEntry>
}

class MemoryImportException(message: String) : IllegalArgumentException(message)

object MemoryExportCodec {
    const val FORMAT = "ntpx-alchemist-memory"
    const val VERSION = 2
    const val PAGE_SIZE = 200

    /**
     * Longest line accepted on import: a maximum-size entry with every content and tag character
     * escaped (\uXXXX is 6 chars), plus fixed metadata. Entry validation keeps every line this app
     * writes within it, so export and import limits are symmetric.
     */
    const val MAX_LINE_CHARS =
        (MemoryEntry.MAX_CONTENT + MemoryEntry.MAX_TAGS * MemoryEntry.MAX_TAG_LENGTH) * 6 + 4_096

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Oldest first, ties broken by id; matches the Room keyset query. */
    val EXPORT_ORDER: Comparator<MemoryEntry> = compareBy<MemoryEntry> { it.createdAt }.thenBy { it.id }

    /** Streams an export to [out] page by page; peak memory is one page. Returns the entry count. */
    suspend fun write(out: Appendable, exportedAt: Long, pager: MemoryPager, pageSize: Int = PAGE_SIZE): Int {
        require(pageSize > 0) { "pageSize must be > 0" }
        out.append(json.encodeToString(MemoryExportHeader.serializer(), MemoryExportHeader(format = FORMAT, version = VERSION, exportedAt = exportedAt))).append('\n')
        var count = 0
        var last: MemoryEntry? = null
        while (true) {
            val page = pager.next(last, pageSize)
            if (page.isEmpty()) break
            for (entry in page) {
                out.append(json.encodeToString(MemoryEntry.serializer(), entry)).append('\n')
                count++
            }
            last = page.last()
            if (page.size < pageSize) break
        }
        return count
    }

    /**
     * Streams an export from [reader], handing entries to [onBatch] in batches of [batchSize].
     * Validates every line; throws [MemoryImportException] on the first problem.
     * Run it once with a no-op [onBatch] to validate before importing, so a bad file changes nothing.
     */
    suspend fun read(
        reader: Reader,
        batchSize: Int = PAGE_SIZE,
        onBatch: suspend (List<MemoryEntry>) -> Unit,
    ): Pair<MemoryExportHeader, Int> {
        require(batchSize > 0) { "batchSize must be > 0" }
        val lines = BoundedLineReader(reader, MAX_LINE_CHARS)
        val headerLine = lines.next() ?: throw MemoryImportException("File is empty")
        val header = try {
            json.decodeFromString(MemoryExportHeader.serializer(), headerLine)
        } catch (e: SerializationException) {
            throw MemoryImportException("Not an NTPX Alchemist memory export")
        } catch (e: IllegalArgumentException) {
            throw MemoryImportException("Not an NTPX Alchemist memory export")
        }
        if (header.format != FORMAT) throw MemoryImportException("Not an NTPX Alchemist memory export")
        if (header.version != VERSION) throw MemoryImportException("Unsupported export version ${header.version}")

        val batch = ArrayList<MemoryEntry>(batchSize)
        val seen = HashSet<String>()
        var count = 0
        var lineNumber = 1
        while (true) {
            val line = lines.next() ?: break
            lineNumber++
            if (line.isBlank()) continue
            val entry = try {
                json.decodeFromString(MemoryEntry.serializer(), line)
            } catch (e: SerializationException) {
                throw MemoryImportException("Line $lineNumber is not a valid memory entry")
            } catch (e: IllegalArgumentException) {
                throw MemoryImportException("Line $lineNumber: ${e.message}")
            }
            if (!seen.add(entry.id)) throw MemoryImportException("Line $lineNumber repeats id ${entry.id}")
            batch += entry
            count++
            if (batch.size == batchSize) {
                onBatch(batch.toList())
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) onBatch(batch.toList())
        return header to count
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

    /** Convenience for small exports (tests): reads everything into memory. */
    suspend fun decode(text: String): Result<MemoryExport> = runCatching {
        val entries = mutableListOf<MemoryEntry>()
        val (header, _) = read(text.reader()) { entries += it }
        MemoryExport(header, entries)
    }
}

/** Line reader that refuses lines longer than [maxChars] instead of buffering them. */
internal class BoundedLineReader(private val reader: Reader, private val maxChars: Int) {
    private val buffer = CharArray(8_192)
    private var length = 0
    private var position = 0
    private var eof = false

    fun next(): String? {
        val line = StringBuilder()
        while (true) {
            if (position == length) {
                if (eof) return if (line.isEmpty()) null else line.toString()
                length = reader.read(buffer)
                position = 0
                if (length < 0) {
                    length = 0
                    eof = true
                    continue
                }
            }
            val c = buffer[position++]
            if (c == '\n') return line.toString().removeSuffix("\r")
            if (line.length >= maxChars) throw MemoryImportException("A line exceeds $maxChars characters")
            line.append(c)
        }
    }
}
