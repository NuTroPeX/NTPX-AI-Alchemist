package com.ntpx.alchemist.memory.room

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.withTransaction
import com.ntpx.alchemist.memory.MemoryEntry
import com.ntpx.alchemist.memory.MemoryRanker
import com.ntpx.alchemist.memory.MemoryStore
import com.ntpx.alchemist.memory.MemorySummary
import com.ntpx.alchemist.memory.ScoredMemory
import com.ntpx.alchemist.memory.normaliseTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

@Entity(tableName = "memory", indices = [Index("namespace"), Index("updated_at")])
internal data class MemoryRow(
    @PrimaryKey val id: String,
    val namespace: String,
    val content: String,
    /** Tags stored newline-separated; tag syntax forbids newlines. */
    val tags: String,
    @androidx.room.ColumnInfo(name = "created_at") val createdAt: Long,
    @androidx.room.ColumnInfo(name = "updated_at") val updatedAt: Long,
    val pinned: Boolean,
) {
    fun toEntry() = MemoryEntry(
        id = id,
        namespace = namespace,
        content = content,
        tags = tags.split('\n').filter { it.isNotEmpty() }.toSet(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        pinned = pinned,
    )

    companion object {
        fun from(entry: MemoryEntry) = MemoryRow(
            entry.id, entry.namespace, entry.content, entry.tags.sorted().joinToString("\n"),
            entry.createdAt, entry.updatedAt, entry.pinned,
        )
    }
}

/** Preview projection for list screens: truncated content plus the full length. */
internal data class MemorySummaryRow(
    val id: String,
    val namespace: String,
    val content: String,
    val tags: String,
    @androidx.room.ColumnInfo(name = "created_at") val createdAt: Long,
    @androidx.room.ColumnInfo(name = "updated_at") val updatedAt: Long,
    val pinned: Boolean,
    @androidx.room.ColumnInfo(name = "content_length") val contentLength: Int,
) {
    fun toSummary() = MemorySummary(
        MemoryRow(id, namespace, content, tags, createdAt, updatedAt, pinned).toEntry(),
        contentLength,
    )
}

private const val SUMMARY_COLUMNS =
    "id, namespace, substr(content, 1, 600) AS content, tags, created_at, updated_at, pinned, length(content) AS content_length"

/** Ranking reads only a prefix of each row, which bounds search memory regardless of entry size. */
private const val SCAN_COLUMNS =
    "id, namespace, substr(content, 1, 4000) AS content, tags, created_at, updated_at, pinned"

private const val DISPLAY_ORDER = "ORDER BY pinned DESC, updated_at DESC, id ASC"

@Dao
internal interface MemoryDao {
    @Query("SELECT $SUMMARY_COLUMNS FROM memory $DISPLAY_ORDER LIMIT :limit")
    fun observeSummaries(limit: Int): Flow<List<MemorySummaryRow>>

    @Query("SELECT $SUMMARY_COLUMNS FROM memory WHERE namespace = :namespace $DISPLAY_ORDER LIMIT :limit")
    fun observeSummariesIn(namespace: String, limit: Int): Flow<List<MemorySummaryRow>>

    @Query("SELECT COUNT(*) FROM memory")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM memory WHERE namespace = :namespace")
    fun observeCountIn(namespace: String): Flow<Int>

    @Query("SELECT $SCAN_COLUMNS FROM memory $DISPLAY_ORDER LIMIT :limit")
    suspend fun scan(limit: Int): List<MemoryRow>

    @Query("SELECT $SCAN_COLUMNS FROM memory WHERE namespace = :namespace $DISPLAY_ORDER LIMIT :limit")
    suspend fun scanIn(namespace: String, limit: Int): List<MemoryRow>

    @Query("SELECT * FROM memory WHERE id IN (:ids)")
    suspend fun getMany(ids: List<String>): List<MemoryRow>

    @Query("UPDATE memory SET pinned = :pinned, updated_at = :updatedAt WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, updatedAt: Long): Int

    @Query("SELECT * FROM memory ORDER BY pinned DESC, updated_at DESC, id ASC")
    fun observeAll(): Flow<List<MemoryRow>>

    @Query("SELECT * FROM memory WHERE namespace = :namespace ORDER BY pinned DESC, updated_at DESC, id ASC")
    fun observeNamespace(namespace: String): Flow<List<MemoryRow>>

    @Query("SELECT * FROM memory ORDER BY pinned DESC, updated_at DESC, id ASC")
    suspend fun all(): List<MemoryRow>

    @Query("SELECT * FROM memory WHERE namespace = :namespace ORDER BY pinned DESC, updated_at DESC, id ASC")
    suspend fun inNamespace(namespace: String): List<MemoryRow>

    @Query("SELECT * FROM memory ORDER BY created_at ASC, id ASC LIMIT :limit")
    suspend fun firstPage(limit: Int): List<MemoryRow>

    /** Keyset pagination: stable under concurrent inserts, unlike OFFSET. */
    @Query(
        "SELECT * FROM memory WHERE created_at > :createdAt OR (created_at = :createdAt AND id > :id) " +
            "ORDER BY created_at ASC, id ASC LIMIT :limit",
    )
    suspend fun pageAfter(createdAt: Long, id: String, limit: Int): List<MemoryRow>

    @Query("SELECT * FROM memory WHERE id = :id")
    suspend fun get(id: String): MemoryRow?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: MemoryRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<MemoryRow>)

    @Update
    suspend fun update(row: MemoryRow): Int

    @Query("DELETE FROM memory WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM memory")
    suspend fun clearAll(): Int

    @Query("DELETE FROM memory WHERE namespace = :namespace")
    suspend fun clearNamespace(namespace: String): Int
}

@Database(entities = [MemoryRow::class], version = 1, exportSchema = true)
internal abstract class MemoryDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
}

/** Room-backed [MemoryStore] in app-private storage (excluded from backup, see app manifest). */
class RoomMemoryStore private constructor(
    private val database: MemoryDatabase,
    private val clock: () -> Long,
    private val ranker: MemoryRanker,
) : MemoryStore {
    private val dao = database.memoryDao()

    override fun observe(namespace: String?): Flow<List<MemoryEntry>> =
        (if (namespace == null) dao.observeAll() else dao.observeNamespace(namespace)).map { rows -> rows.map(MemoryRow::toEntry) }

    override fun observeSummaries(limit: Int, namespace: String?): Flow<List<MemorySummary>> =
        (if (namespace == null) dao.observeSummaries(limit) else dao.observeSummariesIn(namespace, limit))
            .map { rows -> rows.map(MemorySummaryRow::toSummary) }

    override fun observeCount(namespace: String?): Flow<Int> =
        if (namespace == null) dao.observeCount() else dao.observeCountIn(namespace)

    override suspend fun setPinned(id: String, pinned: Boolean): Boolean = dao.setPinned(id, pinned, clock()) > 0

    override suspend fun put(content: String, namespace: String, tags: Set<String>, pinned: Boolean): MemoryEntry {
        val now = clock()
        val entry = MemoryEntry(UUID.randomUUID().toString(), namespace, content.trim(), normaliseTags(tags), now, now, pinned)
        dao.insert(MemoryRow.from(entry))
        return entry
    }

    override suspend fun get(id: String): MemoryEntry? = dao.get(id)?.toEntry()

    override suspend fun update(entry: MemoryEntry): Boolean {
        val existing = dao.get(entry.id) ?: return false
        return dao.update(MemoryRow.from(entry.copy(createdAt = existing.createdAt, updatedAt = clock()))) > 0
    }

    /**
     * Ranks a content prefix of the newest [SCAN_LIMIT] rows on a background dispatcher, then
     * loads full content for the winners only. The caps bound search cost, never [all] or export.
     */
    override suspend fun search(query: String, namespace: String?, limit: Int): List<ScoredMemory> {
        val candidates = if (namespace == null) dao.scan(SCAN_LIMIT) else dao.scanIn(namespace, SCAN_LIMIT)
        val ranked = withContext(Dispatchers.Default) { ranker.rank(query, candidates.map(MemoryRow::toEntry), limit) }
        if (ranked.isEmpty()) return emptyList()
        val full = dao.getMany(ranked.map { it.entry.id }).associateBy { it.id }
        return ranked.mapNotNull { scored -> full[scored.entry.id]?.let { ScoredMemory(it.toEntry(), scored.score) } }
    }

    override suspend fun delete(id: String): Boolean = dao.delete(id) > 0

    override suspend fun clear(namespace: String?): Int = if (namespace == null) dao.clearAll() else dao.clearNamespace(namespace)

    override suspend fun all(namespace: String?): List<MemoryEntry> =
        (if (namespace == null) dao.all() else dao.inNamespace(namespace)).map(MemoryRow::toEntry)

    /** Pages every entry oldest-first for streaming export. */
    val exportPager = com.ntpx.alchemist.memory.MemoryPager { after, limit ->
        (if (after == null) dao.firstPage(limit) else dao.pageAfter(after.createdAt, after.id, limit)).map(MemoryRow::toEntry)
    }

    /** Restores entries from an export, replacing any with the same id. Tags are normalised like new entries. */
    suspend fun import(entries: List<MemoryEntry>): Int {
        dao.upsertAll(entries.map(::importRow))
        return entries.size
    }

    /**
     * Runs [block] inside one database transaction and hands it a sink for batches of entries.
     * Every batch commits together, or none does if [block] throws (I/O error, bad line, disk full),
     * so a failed restore never leaves the store half-overwritten.
     */
    suspend fun <T> importAtomically(block: suspend (sink: suspend (List<MemoryEntry>) -> Unit) -> T): T =
        database.withTransaction { block { batch -> dao.upsertAll(batch.map(::importRow)) } }

    private fun importRow(entry: MemoryEntry) = MemoryRow.from(entry.copy(tags = normaliseTags(entry.tags)))

    fun close() = database.close()

    companion object {
        /** Upper bound on rows scanned per search; ample for a personal store. */
        const val SCAN_LIMIT = 2_000
        const val DATABASE_NAME = "alchemist-memory.db"

        fun create(context: Context, clock: () -> Long = System::currentTimeMillis, ranker: MemoryRanker = MemoryRanker()): RoomMemoryStore =
            RoomMemoryStore(
                Room.databaseBuilder(context.applicationContext, MemoryDatabase::class.java, DATABASE_NAME).build(),
                clock,
                ranker,
            )
    }
}
