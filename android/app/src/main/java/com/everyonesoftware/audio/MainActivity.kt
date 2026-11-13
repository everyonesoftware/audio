package com.everyonesoftware.audio

import android.graphics.Color
import android.content.Intent
import android.app.SearchManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
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
        if (savedInstanceState == null) handleVoiceIntent(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.installIntents.collect { request ->
                    if (model.allowInstallerLaunch(request)) {
                        try { startActivity(request) }
                        catch (_: android.content.ActivityNotFoundException) { model.installerLaunchFailed() }
                        catch (_: SecurityException) { model.installerLaunchFailed() }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVoiceIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        model.onForeground()
    }

    override fun onStop() {
        model.onBackground()
        super.onStop()
    }

    private fun handleVoiceIntent(intent: Intent) {
        if (intent.action != "android.media.action.MEDIA_PLAY_FROM_SEARCH") return
        model.playFromSearch(intent.getStringExtra(SearchManager.QUERY).orEmpty())
    }
}
