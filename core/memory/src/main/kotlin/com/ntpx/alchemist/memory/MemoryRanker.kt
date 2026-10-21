package com.ntpx.alchemist.memory

import kotlin.math.ln

/**
 * Lexical relevance ranking (BM25-style term weighting + tag and phrase boosts).
 * Deterministic, fast for personal-scale stores, and needs no embedding model.
 * An embedding ranker can replace this once an on-device embeddings model is registered.
 */
class MemoryRanker(
    private val k1: Double = 1.2,
    private val b: Double = 0.75,
    private val tagBoost: Double = 1.5,
    private val phraseBoost: Double = 2.0,
    private val pinnedBoost: Double = 0.25,
) {
    fun rank(query: String, entries: List<MemoryEntry>, limit: Int): List<ScoredMemory> {
        val terms = tokenize(query).distinct()
        if (terms.isEmpty() || entries.isEmpty() || limit <= 0) return emptyList()

        val docs = entries.map { it to tokenize(it.content) }
        val avgLength = docs.sumOf { it.second.size }.toDouble() / docs.size
        val docFrequency = terms.associateWith { term -> docs.count { (_, tokens) -> term in tokens } }
        val phrase = query.trim().lowercase()

        return docs.mapNotNull { (entry, tokens) ->
            val counts = tokens.groupingBy { it }.eachCount()
            var score = 0.0
            for (term in terms) {
                val tf = counts[term] ?: 0
                if (tf == 0) continue
                val df = docFrequency.getValue(term)
                val idf = ln(1.0 + (docs.size - df + 0.5) / (df + 0.5))
                score += idf * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * tokens.size / avgLength.coerceAtLeast(1.0)))
            }
            val tagHits = entry.tags.count { tag -> tokenize(tag).any { it in terms } }
            score += tagHits * tagBoost
            if (score <= 0.0) return@mapNotNull null
            if (terms.size > 1 && entry.content.lowercase().contains(phrase)) score += phraseBoost
            if (entry.pinned) score += pinnedBoost
            ScoredMemory(entry, score)
        }
            .sortedWith(compareByDescending<ScoredMemory> { it.score }.thenByDescending { it.entry.updatedAt })
            .take(limit)
    }

    companion object {
        private val STOP_WORDS = setOf(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "in", "is", "it", "of", "on", "or",
            "that", "the", "this", "to", "was", "with",
        )

        fun tokenize(text: String): List<String> =
            text.lowercase()
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length > 1 && it !in STOP_WORDS }
    }
}
