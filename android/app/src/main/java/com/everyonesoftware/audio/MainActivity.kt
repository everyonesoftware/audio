package com.everyonesoftware.audio

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.everyonesoftware.audio.ui.AudioApp
import com.everyonesoftware.audio.ui.AudioTheme
import com.everyonesoftware.audio.ui.AudioViewModel

class MainActivity : ComponentActivity() {
    private val model: AudioViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val artwork = remember(state.connection) { { id: String? -> model.artworkUrl(id) } }
            AudioTheme { AudioApp(state, model, artwork) }
        }
    }
}
