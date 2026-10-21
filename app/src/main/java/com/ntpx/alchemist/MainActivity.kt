package com.ntpx.alchemist

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "NTPX AI Alchemist\nAndroid foundation v0.1.0\nAI runtime not yet connected"
            textSize = 20f
            setPadding(32, 48, 32, 32)
        })
    }
}
