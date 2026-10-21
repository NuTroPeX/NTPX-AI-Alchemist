package com.ntpx.alchemist.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditLogTest {

    private fun log(capacity: Int = 100): InMemoryAuditLog {
        var t = 1_000L
        return InMemoryAuditLog(capacity = capacity, clock = { t++ })
    }

    @Test
    fun chainVerifiesAndLinksEntries() {
        val log = log()
        repeat(5) { log.append("a", "t", AuditType.STEP_STARTED, "step $it") }
        val entries = log.entries.value
        assertEquals(AuditHashing.GENESIS, entries.first().previousHash)
        entries.zipWithNext().forEach { (prev, next) -> assertEquals(prev.hash, next.previousHash) }
        assertEquals(ChainVerification.Valid, log.verify())
    }

    @Test
    fun tamperingWithDetailIsDetected() {
        val log = log()
        repeat(3) { log.append("a", "t", AuditType.STEP_STARTED, "step $it") }
        val tampered = log.entries.value.toMutableList().also { it[1] = it[1].copy(detail = "edited") }
        val result = AuditLog.verifyChain(tampered)
        assertTrue(result is ChainVerification.Broken)
        assertEquals(1L, (result as ChainVerification.Broken).atSequence)
    }

    @Test
    fun removingAnEntryIsDetected() {
        val log = log()
        repeat(4) { log.append("a", "t", AuditType.STEP_STARTED, "step $it") }
        val withGap = log.entries.value.filterIndexed { index, _ -> index != 2 }
        assertTrue(AuditLog.verifyChain(withGap) is ChainVerification.Broken)
    }

    @Test
    fun capacityTrimsOldestButTailStillVerifies() {
        val log = log(capacity = 3)
        repeat(10) { log.append("a", "t", AuditType.STEP_STARTED, "step $it") }
        assertEquals(listOf(7L, 8L, 9L), log.entries.value.map { it.sequence })
        assertEquals(ChainVerification.Valid, log.verify())
    }

    @Test
    fun restoredLogContinuesSequenceAndChain() {
        val first = log()
        repeat(2) { first.append("a", "t", AuditType.STEP_STARTED, "s$it") }
        val persisted = mutableListOf<AuditEntry>()
        val second = InMemoryAuditLog(restored = first.entries.value, onAppend = { persisted += it })
        val appended = second.append("a", "t", AuditType.TASK_FINISHED, "done")
        assertEquals(2L, appended.sequence)
        assertEquals(first.entries.value.last().hash, appended.previousHash)
        assertEquals(listOf(appended), persisted)
        assertEquals(ChainVerification.Valid, second.verify())
    }

    @Test
    fun fieldBoundariesCannotCollide() {
        val a = AuditHashing.hash(0, 0, "ab", "c", AuditType.STEP_STARTED, "", AuditHashing.GENESIS)
        val b = AuditHashing.hash(0, 0, "a", "bc", AuditType.STEP_STARTED, "", AuditHashing.GENESIS)
        assertTrue(a != b)
    }

    @Test
    fun concurrentAppendsReachCallbackInSequenceOrder() {
        val persisted = java.util.Collections.synchronizedList(mutableListOf<AuditEntry>())
        val log = InMemoryAuditLog(capacity = 10_000, onAppend = {
            Thread.yield() // widen the race window
            persisted += it
        })
        val threads = List(8) { t -> Thread { repeat(250) { log.append("a$t", "t", AuditType.STEP_STARTED, "$it") } } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertEquals((0L until 2_000L).toList(), persisted.map { it.sequence })
        assertEquals(ChainVerification.Valid, AuditLog.verifyChain(persisted.toList()))
    }

    @Test
    fun oversizedDetailIsTruncatedBeforeHashing() {
        val log = log()
        val entry = log.append("a", "t", AuditType.TASK_STARTED, "x".repeat(10_000))
        assertEquals(InMemoryAuditLog.MAX_DETAIL_CHARS, entry.detail.length)
        assertEquals(ChainVerification.Valid, log.verify())
    }

    @Test
    fun removedPrefixFailsAgainstAnchor() {
        val log = log()
        repeat(5) { log.append("a", "t", AuditType.STEP_STARTED, "s$it") }
        val withoutPrefix = log.entries.value.drop(2)
        // Self-consistent on its own…
        assertEquals(ChainVerification.Valid, AuditLog.verifyChain(withoutPrefix))
        // …but not from where the chain is recorded to start.
        assertTrue(AuditLog.verifyChain(withoutPrefix, ChainAnchor.GENESIS) is ChainVerification.Broken)
        val restored = InMemoryAuditLog(restored = withoutPrefix, restoredAnchor = ChainAnchor.GENESIS)
        assertTrue(restored.verify() is ChainVerification.Broken)
    }

    @Test
    fun emptyLogIsValidOnlyFromGenesis() {
        assertEquals(ChainVerification.Valid, AuditLog.verifyChain(emptyList(), ChainAnchor.GENESIS))
        assertTrue(AuditLog.verifyChain(emptyList(), ChainAnchor(42, AuditHashing.GENESIS)) is ChainVerification.Broken)
    }

    @Test
    fun ownTrimMovesAnchorAndStaysValid() {
        val log = log(capacity = 3)
        repeat(10) { log.append("a", "t", AuditType.STEP_STARTED, "s$it") }
        assertEquals(ChainAnchor.of(log.entries.value.first()), log.anchor)
        assertEquals(ChainVerification.Valid, log.verify())
    }
}
