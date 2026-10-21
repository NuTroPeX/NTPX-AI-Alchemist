package com.ntpx.alchemist

import android.app.Application

class AlchemistApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
