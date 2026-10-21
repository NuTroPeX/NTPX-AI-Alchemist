package com.ntpx.alchemist.memory

import kotlinx.serialization.Serializable
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

object MemoryExportCodec {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(entries: List<MemoryEntry>, exportedAt: Long): String =
        json.encodeToString(MemoryExport.serializer(), MemoryExport(exportedAt = exportedAt, entries = entries))

    fun decode(text: String): Result<MemoryExport> = runCatching {
        val export = json.decodeFromString(MemoryExport.serializer(), text)
        require(export.format == MemoryExport.FORMAT) { "Not an NTPX Alchemist memory export" }
        require(export.version == MemoryExport.VERSION) { "Unsupported export version ${export.version}" }
        export
    }
}
