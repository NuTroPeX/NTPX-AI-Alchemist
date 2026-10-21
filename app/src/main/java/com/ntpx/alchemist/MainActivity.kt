package com.ntpx.alchemist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ntpx.alchemist.ui.AlchemistApp
import com.ntpx.alchemist.ui.theme.AlchemistTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as AlchemistApplication).container
        setContent {
            AlchemistTheme { AlchemistApp(container) }
        }
    }
}
