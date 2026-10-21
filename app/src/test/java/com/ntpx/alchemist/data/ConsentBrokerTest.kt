package com.ntpx.alchemist.data

import com.ntpx.alchemist.agent.ConsentDecision
import com.ntpx.alchemist.agent.ConsentRequest
import com.ntpx.alchemist.agent.Permission
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentBrokerTest {

    private fun request(p: Permission) = ConsentRequest("agent", "task", p, "step")

    @Test
    fun lateTapFromPreviousDialogCannotApproveNextRequest() = runTest {
        val broker = ConsentBroker()
        val first = async { broker.requestConsent(request(Permission.INVOKE_TOOL)) }
        val firstPending = broker.pending.filterNotNull().first()
        assertTrue(broker.answer(firstPending, ConsentDecision.ALLOW_ONCE))
        assertEquals(ConsentDecision.ALLOW_ONCE, first.await())

        val second = async { broker.requestConsent(request(Permission.WRITE_MEMORY)) }
        val secondPending = broker.pending.filterNotNull().first { it !== firstPending }
        // A repeated tap on the first (now gone) dialog must be ignored.
        assertFalse(broker.answer(firstPending, ConsentDecision.ALLOW_ALWAYS))
        assertFalse(second.isCompleted)

        assertTrue(broker.answer(secondPending, ConsentDecision.DENY))
        assertEquals(ConsentDecision.DENY, second.await())
    }

    @Test
    fun identicalRequestsAreStillDistinguished() = runTest {
        val broker = ConsentBroker()
        val a = async { broker.requestConsent(request(Permission.INVOKE_TOOL)) }
        val pendingA = broker.pending.filterNotNull().first()
        broker.answer(pendingA, ConsentDecision.DENY)
        a.await()
        val b = async { broker.requestConsent(request(Permission.INVOKE_TOOL)) }
        broker.pending.filterNotNull().first { it !== pendingA }
        assertFalse(broker.answer(pendingA, ConsentDecision.ALLOW_ALWAYS))
        assertFalse(b.isCompleted)
        b.cancel()
    }
}
