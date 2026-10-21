package com.ntpx.alchemist.memory

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MemoryStoreTest {

    private fun store(): InMemoryMemoryStore {
        var t = 0L
        var n = 0
        return InMemoryMemoryStore(clock = { t++ }, ids = { "m${n++}" })
    }

    @Test
    fun putTrimsAndNormalisesTags() = runTest {
        val entry = store().put("  remember the forge  ", tags = setOf(" Agent Run ", "agent-run", "X"))
        assertEquals("remember the forge", entry.content)
        assertEquals(setOf("agent-run", "x"), entry.tags)
    }

    @Test
    fun observeOrdersPinnedFirstThenNewest() = runTest {
        val s = store()
        s.put("old")
        s.put("pinned", pinned = true)
        s.put("new")
        assertEquals(listOf("pinned", "new", "old"), s.observe().first().map { it.content })
    }

    @Test
    fun namespacesIsolateQueriesAndClears() = runTest {
        val s = store()
        s.put("alpha note", namespace = "work")
        s.put("alpha note", namespace = "home")
        assertEquals(1, s.search("alpha", namespace = "work").size)
        assertEquals(1, s.clear("work"))
        assertEquals(listOf("home"), s.all().map { it.namespace })
        assertEquals(1, s.clear())
        assertTrue(s.all().isEmpty())
    }

    @Test
    fun updateKeepsCreatedAtAndBumpsUpdatedAt() = runTest {
        val s = store()
        val e = s.put("draft")
        assertTrue(s.update(e.copy(content = "final", createdAt = 999)))
        val updated = s.get(e.id)!!
        assertEquals("final", updated.content)
        assertEquals(e.createdAt, updated.createdAt)
        assertTrue(updated.updatedAt > e.updatedAt)
        assertFalse(s.update(e.copy(id = "missing")))
    }

    @Test
    fun deleteReportsResult() = runTest {
        val s = store()
        val e = s.put("bye")
        assertTrue(s.delete(e.id))
        assertFalse(s.delete(e.id))
        assertNull(s.get(e.id))
    }

    @Test
    fun entryValidationRejectsBadInput() {
        listOf(
            { MemoryEntry("id", "Bad NS", "c", createdAt = 0) },
            { MemoryEntry("id", "ok", "   ", createdAt = 0) },
            { MemoryEntry("id", "ok", "c", tags = setOf("has\nnewline"), createdAt = 0) },
            { MemoryEntry("id", "ok", "x".repeat(MemoryEntry.MAX_CONTENT + 1), createdAt = 0) },
        ).forEach { build ->
            try {
                build()
                fail("expected rejection")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun exportRoundTripsAndRejectsForeignFiles() = runTest {
        val s = store()
        s.put("one", tags = setOf("t"))
        s.put("two", pinned = true)
        val text = MemoryExportCodec.encode(s.all(), exportedAt = 42)
        val decoded = MemoryExportCodec.decode(text).getOrThrow()
        assertEquals(s.all().sortedWith(MemoryExportCodec.EXPORT_ORDER), decoded.entries)
        assertTrue(MemoryExportCodec.decode("""{"format":"other","version":1,"exportedAt":0,"entries":[]}""").isFailure)
        assertTrue(MemoryExportCodec.decode("garbage").isFailure)
    }

    @Test
    fun streamingExportPagesEverythingAndDecodes() = runTest {
        val s = store()
        repeat(450) { s.put("entry $it", tags = setOf("t$it")) }
        val all = s.all().sortedWith(MemoryExportCodec.EXPORT_ORDER)
        var pagesServed = 0
        val pager = MemoryPager { after, limit ->
            pagesServed++
            val start = if (after == null) 0 else all.indexOfFirst { it.id == after.id } + 1
            all.subList(start, minOf(all.size, start + limit))
        }
        val out = StringBuilder()
        val count = MemoryExportCodec.write(out, exportedAt = 7, pager = pager, pageSize = 100)
        assertEquals(450, count)
        assertEquals(5, pagesServed)
        val decoded = MemoryExportCodec.decode(out.toString()).getOrThrow()
        assertEquals(all, decoded.entries)
        assertEquals(7L, decoded.exportedAt)
    }

    @Test
    fun emptyExportIsValid() = runTest {
        val decoded = MemoryExportCodec.decode(MemoryExportCodec.encode(emptyList(), 1)).getOrThrow()
        assertTrue(decoded.entries.isEmpty())
    }

    @Test
    fun importRejectsBadLinesTooLongLinesAndDuplicates() = runTest {
        val header = """{"format":"ntpx-alchemist-memory","version":2,"exportedAt":1}"""
        val good = """{"id":"a","namespace":"default","content":"hi","createdAt":1}"""
        suspend fun fails(text: String) = MemoryExportCodec.decode(text).isFailure
        assertTrue(fails("$header\n{oops}"))
        assertTrue(fails("$header\n$good\n$good"))
        assertTrue(fails("""{"format":"ntpx-alchemist-memory","version":1,"exportedAt":1}"""))
        assertTrue(fails(""))
        assertTrue(fails(header + "\n" + "x".repeat(MemoryExportCodec.MAX_LINE_CHARS + 1)))
        assertEquals(1, MemoryExportCodec.decode("$header\r\n\r\n$good\r\n").getOrThrow().entries.size)
    }

    @Test
    fun readStreamsInBatchesWithoutLoadingEverything() = runTest {
        val s = store()
        repeat(500) { s.put("entry $it") }
        val text = MemoryExportCodec.encode(s.all(), 1)
        val batches = mutableListOf<Int>()
        val (_, count) = MemoryExportCodec.read(text.reader(), batchSize = 128) { batches += it.size }
        assertEquals(500, count)
        assertEquals(listOf(128, 128, 128, 116), batches)
    }

    @Test
    fun summariesTruncateContentAndPinningKeepsFullText() = runTest {
        val s = store()
        val long = s.put("y".repeat(MemorySummary.PREVIEW_CHARS + 50))
        val summary = s.observeSummaries(10).first().single()
        assertTrue(summary.truncated)
        assertEquals(MemorySummary.PREVIEW_CHARS, summary.entry.content.length)
        assertTrue(s.setPinned(long.id, true))
        assertEquals(MemorySummary.PREVIEW_CHARS + 50, s.get(long.id)!!.content.length)
        assertEquals(1, s.observeCount().first())
    }

    @Test
    fun headerWithoutExplicitFormatOrVersionIsRejected() = runTest {
        val good = """{"id":"a","namespace":"default","content":"hi","createdAt":1}"""
        assertTrue(MemoryExportCodec.decode("{\"exportedAt\":1}\n$good").isFailure)
        assertTrue(MemoryExportCodec.decode("{\"format\":\"ntpx-alchemist-memory\",\"exportedAt\":1}\n$good").isFailure)
    }

    @Test
    fun largestValidEntryRoundTripsWithinLineLimit() = runTest {
        // Worst case for escaping: control characters encode as \uXXXX (6 chars each).
        val content = "a" + "\u0001".repeat(MemoryEntry.MAX_CONTENT - 1)
        val tags = (1..MemoryEntry.MAX_TAGS).map { "t$it".padEnd(MemoryEntry.MAX_TAG_LENGTH, 'x') }.toSet()
        val entry = MemoryEntry("big", "default", content, tags, createdAt = 1)
        val decoded = MemoryExportCodec.decode(MemoryExportCodec.encode(listOf(entry), 1)).getOrThrow()
        assertEquals(entry, decoded.entries.single())
    }

    @Test
    fun tooManyTagsAreRejectedAtSave() = runTest {
        try {
            store().put("c", tags = (0..MemoryEntry.MAX_TAGS).map { "t$it" }.toSet())
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
    }
}
