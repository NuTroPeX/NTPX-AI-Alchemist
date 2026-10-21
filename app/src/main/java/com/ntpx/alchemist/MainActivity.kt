package com.ntpx.alchemist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.ntpx.alchemist.ui.AlchemistApp
import com.ntpx.alchemist.ui.StartupScreen
import com.ntpx.alchemist.ui.theme.AlchemistTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as AlchemistApplication
        setContent {
            AlchemistTheme {
                val container by produceState<AppContainer?>(initialValue = null) { value = app.awaitContainer() }
                val ready = container
                if (ready == null) StartupScreen() else AlchemistApp(ready)
            }
        }
    }
}
