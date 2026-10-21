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
}
