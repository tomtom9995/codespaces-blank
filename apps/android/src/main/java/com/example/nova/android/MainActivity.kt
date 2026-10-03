package com.example.nova.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.nova.android.ui.NovaRoot
import com.example.nova.android.ui.NovaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as NovaApplication
        handleIntent(intent)
        setContent {
            NovaTheme {
                NovaRoot(app.nova, app.centralLoginCallback)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "nova" && data.host == "auth") {
            (application as NovaApplication).centralLoginCallback.value = data.toString()
        }
    }
}
