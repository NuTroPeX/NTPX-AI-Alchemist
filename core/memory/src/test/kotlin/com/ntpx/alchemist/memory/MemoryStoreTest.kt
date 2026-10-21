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
        val entry = store().put("  remember the forge  ", tags = setOf(" Agent Run ", "x"))
        assertEquals("remember the forge", entry.content)
        assertEquals(setOf("Agent-Run", "x"), entry.tags)
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
        assertEquals(s.all(), decoded.entries)
        assertTrue(MemoryExportCodec.decode("""{"format":"other","version":1,"exportedAt":0,"entries":[]}""").isFailure)
        assertTrue(MemoryExportCodec.decode("garbage").isFailure)
    }
}
