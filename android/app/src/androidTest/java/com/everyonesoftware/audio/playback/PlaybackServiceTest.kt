package com.everyonesoftware.audio.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlaybackServiceTest {
    @Test fun ownApplicationControllerConnectsToBackgroundPlaybackService() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        }
        val controller = future.get(15, TimeUnit.SECONDS)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                assertTrue(controller.isConnected)
                assertFalse(controller.isPlaying)
            } finally { controller.release() }
        }
    }
}
