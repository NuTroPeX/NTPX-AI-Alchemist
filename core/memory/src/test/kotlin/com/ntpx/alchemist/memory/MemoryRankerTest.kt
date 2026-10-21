package com.ntpx.alchemist.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRankerTest {

    private var n = 0
    private fun entry(content: String, tags: Set<String> = emptySet(), pinned: Boolean = false, updatedAt: Long = 0) =
        MemoryEntry("e${n++}", "default", content, tags, createdAt = 0, updatedAt = updatedAt, pinned = pinned)

    private val ranker = MemoryRanker()

    @Test
    fun tokenizerDropsStopWordsPunctuationAndSingleChars() {
        assertEquals(listOf("forge", "core", "v2"), MemoryRanker.tokenize("The forge, a core-v2!"))
    }

    @Test
    fun rareTermsOutweighCommonOnes() {
        val entries = listOf(
            entry("android build android build"),
            entry("android release"),
            entry("android kotlin"),
            entry("quantum android"),
        )
        val top = ranker.rank("quantum android", entries, 4).first()
        assertEquals("quantum android", top.entry.content)
    }

    @Test
    fun nonMatchingEntriesAreExcluded() {
        val result = ranker.rank("satellite", listOf(entry("ground station"), entry("orbit")), 10)
        assertTrue(result.isEmpty())
    }

    @Test
    fun tagsAndPhrasesBoost() {
        val plain = entry("notes about model registry design")
        val tagged = entry("notes about design", tags = setOf("registry"))
        val phrase = entry("the model registry is ready")
        val ranked = ranker.rank("model registry", listOf(plain, tagged, phrase), 3).map { it.entry.id }
        assertEquals(phrase.id, ranked.first())
        assertTrue(ranked.containsAll(listOf(plain.id, tagged.id)))
    }

    @Test
    fun respectsLimitAndBlankQuery() {
        val entries = List(20) { entry("match $it") }
        assertEquals(5, ranker.rank("match", entries, 5).size)
        assertTrue(ranker.rank("  ", entries, 5).isEmpty())
        assertTrue(ranker.rank("match", entries, 0).isEmpty())
    }

    @Test
    fun tiesBreakByRecency() {
        val older = entry("same words", updatedAt = 1)
        val newer = entry("same words", updatedAt = 5)
        assertEquals(newer.id, ranker.rank("same words", listOf(older, newer), 2).first().entry.id)
    }
}
