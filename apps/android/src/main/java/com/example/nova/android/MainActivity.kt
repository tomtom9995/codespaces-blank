package com.example.nova.android

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
        val nova = (application as NovaApplication).nova
        setContent {
            NovaTheme {
                NovaRoot(nova)
            }
        }
    }
}
