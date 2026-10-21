package com.ntpx.alchemist

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

class AlchemistApplication : Application() {
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Built on a background thread: startup reads assets, opens Room, and restores and verifies the
     * audit journal (up to thousands of records), none of which may run on the main thread.
     */
    private lateinit var containerAsync: Deferred<AppContainer>

    override fun onCreate() {
        super.onCreate()
        containerAsync = initScope.async { AppContainer(this@AlchemistApplication) }
    }

    suspend fun awaitContainer(): AppContainer = containerAsync.await()
}
