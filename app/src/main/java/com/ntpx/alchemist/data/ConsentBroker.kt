package com.ntpx.alchemist.data

import com.ntpx.alchemist.agent.ConsentDecision
import com.ntpx.alchemist.agent.ConsentProvider
import com.ntpx.alchemist.agent.ConsentRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bridges runtime consent requests to the UI. The runtime suspends until the user
 * answers the dialog; no answer within [timeoutMillis] is treated as DENY (fail closed).
 * Requests are serialised so only one dialog is ever shown.
 */
class ConsentBroker(private val timeoutMillis: Long = 60_000) : ConsentProvider {

    /** Identity matters: answers are accepted only for the exact instance the UI displayed. */
    class Pending(val request: ConsentRequest, internal val answer: CompletableDeferred<ConsentDecision>)

    private val lock = Mutex()
    private val state = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = state.asStateFlow()

    override suspend fun requestConsent(request: ConsentRequest): ConsentDecision = lock.withLock {
        val pending = Pending(request, CompletableDeferred())
        state.value = pending
        try {
            withTimeoutOrNull(timeoutMillis) { pending.answer.await() } ?: ConsentDecision.DENY
        } finally {
            state.compareAndSet(pending, null)
        }
    }

    /**
     * Answers [pending] only if it is still the current request. A late or repeated tap from a
     * dialog that has already been replaced is ignored, so it can never approve the next prompt.
     */
    fun answer(pending: Pending, decision: ConsentDecision): Boolean {
        if (state.value !== pending) return false
        return pending.answer.complete(decision)
    }
}
