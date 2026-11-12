package com.everyonesoftware.audio

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.everyonesoftware.audio.playback.PlaybackService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.everyonesoftware.audio.data.ListeningSession
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.*
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** All network, account and audio data are local fixtures. This exercises the production activity and service. */
@RunWith(AndroidJUnit4::class)
class NativeFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val requests = ConcurrentLinkedQueue<RecordedRequest>()
    private val ratings = ConcurrentHashMap<String, Int>()
    private val server = MockWebServer()
    private val app get() = ApplicationProvider.getApplicationContext<AudioApplication>()
    private val graph get() = app.graph
    private val coldProbe get() = InstrumentationRegistry.getArguments().getString("coldRestoreOnly") == "true"
    private val preserveFixture get() = InstrumentationRegistry.getArguments().getString("preserveSession") == "true"
    private fun capture(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(400, 5000)
        // Capture the actual tested Compose root. UiAutomation can return an old display buffer
        // immediately after fast fixture-driven routing, despite up-to-date semantics.
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = java.io.File(app.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        java.io.File(directory, "$name.png").outputStream().use { output ->
            check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
        }
        image.recycle()
    }
    private fun ok(content: String = "") = MockResponse().setHeader("Content-Type", "application/json").setBody("{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\"$content}}")
    private fun song(id: String) = "{\"id\":\"$id\",\"title\":\"Fixture $id\",\"artist\":\"Audio test\",\"album\":\"Local fixture\",\"duration\":30,\"userRating\":${ratings[id] ?: 0}}"
    private fun until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
        while (!condition()) { check(System.nanoTime() < deadline) { "Native fixture state timed out" }; Thread.sleep(25) }
    }

    @Before fun serveIsolatedMusicFixture() {
        if (coldProbe) return
        val samples = 30 * 8000
        val bytes = samples * 2
        val wav = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + bytes); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(bytes)
        }.array()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
                if (url.queryParameter("u") != "native-fixture-user" || url.queryParameter("t").isNullOrBlank() || url.queryParameter("p") != null) return MockResponse().setResponseCode(401)
                return when (url.encodedPath.substringAfterLast('/')) {
                    "ping.view" -> ok()
                    "getMusicFolders.view" -> ok(",\"musicFolders\":{\"musicFolder\":[{\"id\":\"main\",\"name\":\"Fixture library\"}]}")
                    "getPlaylists.view" -> ok(",\"playlists\":{\"playlist\":[{\"id\":\"routine\",\"name\":\"Fixture routine\",\"songCount\":3}]}")
                    "getPlaylist.view" -> ok(",\"playlist\":{\"id\":\"routine\",\"name\":\"Fixture routine\",\"entry\":[${listOf("s1", "s2", "s3").joinToString(",") { song(it) }}]}")
                    "setRating.view" -> { ratings[url.queryParameter("id")!!] = url.queryParameter("rating")!!.toInt(); ok() }
                    "getSong.view" -> ok(",\"song\":${song(url.queryParameter("id")!!)}")
                    "stream.view" -> MockResponse().setHeader("Content-Type", "audio/wav").setHeader("Cache-Control", "no-store").setBody(Buffer().write(wav))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun removeOnlyFixtureAccount() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            app.stopService(Intent(app, PlaybackService::class.java))
        }
        if (!preserveFixture) runBlocking { graph.store.connections().filter { it.username == "native-fixture-user" }.forEach { graph.store.removeConnection(it.id) } }
        if (!coldProbe) server.shutdown()
    }

    @Test fun realConnectionShuffleRatingBackgroundPlaybackAndActivityReturn() {
        if (coldProbe) {
            val expected = Json.decodeFromString<ListeningSession>(java.io.File(app.getExternalFilesDir(null), "evidence/expected-session.json").readText())
            compose.waitUntil(15_000) { graph.session.value != null }
            compose.onNodeWithTag("player-toggle").assertIsDisplayed()
            Assert.assertEquals(expected, graph.session.value)
            Assert.assertFalse(graph.playback.value.playing)
            capture("native-cold-restored")
            return
        }
        val baseUrl = server.url("/").toString()
        compose.onNodeWithText("Connect to Navidrome").assertIsDisplayed()
        capture("native-welcome")
        compose.onNodeWithText("Connect to Navidrome").performClick()
        compose.onNodeWithTag("connect-name").performTextInput("Local fixture")
        compose.onNodeWithTag("connect-server-url").performTextInput(baseUrl)
        compose.onNodeWithTag("connect-username").performTextInput("native-fixture-user")
        compose.onNodeWithTag("connect-password").performTextInput("test-only-password")
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = compose.activity
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        compose.onNodeWithTag("connect-submit").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("library-continue").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library-continue").assertIsDisplayed()
        capture("native-libraries")
        compose.onNodeWithTag("library-continue").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Choose a playlist").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Choose a playlist").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("playlist-shuffle-routine").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("playlist-shuffle-routine").performClick()
        compose.waitUntil(20_000) { graph.playback.value.playing }
        val original = graph.session.value!!
        Assert.assertEquals(setOf("s1", "s2", "s3"), original.queue.map { it.id }.toSet())
        Assert.assertTrue(requests.any { it.requestUrl?.encodedPath?.endsWith("/stream.view") == true })
        compose.onNodeWithTag("player-rating").performClick()
        compose.onNodeWithTag("rating-4").performClick()
        compose.waitUntil(15_000) { graph.session.value?.queue?.getOrNull(graph.session.value!!.index)?.userRating == 4 }
        compose.onNodeWithText("Your rating: 4 of 5 stars").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        capture("native-player")
        Assert.assertEquals(4, ratings[original.queue[original.index].id])

        val positionBeforeBackground = graph.playback.value.positionMs
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        until { graph.playback.value.playing && graph.playback.value.positionMs > positionBeforeBackground + 1200 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("player-toggle").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { !graph.playback.value.playing }
        compose.onNodeWithTag("player-seek").performSemanticsAction(SemanticsActions.SetProgress) { it(5000f) }
        compose.waitUntil(10_000) { graph.session.value?.positionMs in 4900L..5100L }
        val paused = graph.session.value!!
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("player-toggle").assertIsDisplayed()
        compose.waitUntil(10_000) { graph.session.value?.positionMs == paused.positionMs }
        Assert.assertFalse(graph.playback.value.playing)
        Assert.assertEquals(original.queue.map { it.id }, graph.session.value!!.queue.map { it.id })
        Assert.assertEquals(original.index, graph.session.value!!.index)
        runBlocking {
            val persisted = graph.store.session(original.connectionId)!!
            Assert.assertEquals(paused.positionMs, persisted.positionMs)
            Assert.assertEquals(original.queue.map { it.id }, persisted.queue.map { it.id })
            Assert.assertEquals(4, persisted.queue[original.index].userRating)
        }
        Assert.assertTrue(requests.any { it.requestUrl?.encodedPath?.endsWith("/setRating.view") == true })
        Assert.assertTrue(requests.any { it.requestUrl?.encodedPath?.endsWith("/getSong.view") == true })
        if (preserveFixture) {
            java.io.File(app.getExternalFilesDir(null), "evidence/expected-session.json")
                .writeText(Json.encodeToString(graph.session.value!!))
        }
    }
}
