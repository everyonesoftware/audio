package com.everyonesoftware.audio.playback

import android.content.ComponentName
import android.content.Intent
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.everyonesoftware.audio.AudioApplication
import com.everyonesoftware.audio.data.Connection
import com.everyonesoftware.audio.data.Credentials
import com.everyonesoftware.audio.data.ListeningSession
import com.everyonesoftware.audio.data.Song
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Can be run in two processes with seedAutoRestore=true / verifyAutoRestore=true. */
@RunWith(AndroidJUnit4::class)
class AutoRestoreTest {
    @Test fun browserRestoresTheExactPausedQueueAndPosition() {
        val app = ApplicationProvider.getApplicationContext<AudioApplication>()
        val graph = app.graph
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val preserve = args.getString("seedAutoRestore") == "true"
        val verifyCold = args.getString("verifyAutoRestore") == "true"
        val id = "auto-cold-restore-fixture"
        val expected = ListeningSession(id, "drive", "Restore fixture",
            listOf(Song("b", "Second", "Fixture"), Song("a", "First", "Fixture"), Song("a", "First", "Fixture")),
            index = 1, positionMs = 5200, shuffle = true, repeatMode = 2, queueId = "auto-cold-restore-queue")
        runBlocking {
            if (!verifyCold) {
                graph.store.saveConnection(Credentials(Connection(id, "Restore fixture", "https://restore-fixture.invalid/", "auto-restore-fixture"), "fixture-only-password"))
                graph.store.saveSession(expected)
                graph.activate(id)
            } else {
                assertNotNull("The seed process must have persisted its fixture", graph.store.session(id))
                graph.bootstrap()
            }
        }
        lateinit var future: ListenableFuture<MediaBrowser>
        instrumentation.runOnMainSync {
            future = MediaBrowser.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        }
        val browser = future.get(15, TimeUnit.SECONDS)
        try {
            assertEquals(expected, graph.session.value)
            instrumentation.runOnMainSync {
                assertFalse(browser.playWhenReady)
                assertFalse(browser.isPlaying)
                assertEquals(3, browser.mediaItemCount)
                assertEquals(1, browser.currentMediaItemIndex)
                assertEquals(5200L, browser.currentPosition)
            }
            lateinit var items: ListenableFuture<androidx.media3.session.LibraryResult<com.google.common.collect.ImmutableList<androidx.media3.common.MediaItem>>>
            instrumentation.runOnMainSync { items = browser.getChildren("audio:queue", 0, 10, null) }
            val result = items.get(15, TimeUnit.SECONDS)
            assertEquals(0, result.resultCode)
            assertEquals(listOf("Second", "First", "First"), result.value!!.map { it.mediaMetadata.title })
            assertEquals(3, result.value!!.map { it.mediaId }.toSet().size)
            assertEquals(expected, runBlocking { graph.store.session(id) })
        } finally {
            instrumentation.runOnMainSync { browser.release(); app.stopService(Intent(app, PlaybackService::class.java)) }
            if (!preserve) runBlocking { graph.store.removeConnection(id) }
        }
    }
}
