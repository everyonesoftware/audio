package com.everyonesoftware.audio.playback

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaConstants
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.everyonesoftware.audio.AudioApplication
import com.everyonesoftware.audio.data.Connection
import com.everyonesoftware.audio.data.Credentials
import com.everyonesoftware.audio.data.Song
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real service/controller and local protocol/audio fixtures; no personal server or car host. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class AndroidAutoTest {
    private val app get() = ApplicationProvider.getApplicationContext<AudioApplication>()
    private val graph get() = app.graph
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val server = MockWebServer()
    private lateinit var browser: MediaBrowser
    private lateinit var credentials: Credentials
    private var previousConnectionId: String? = null
    private var noSessionConnectionId: String? = null
    @Volatile private var delayedPlaylists: java.util.concurrent.CountDownLatch? = null
    private val playlistsRequestStarted = java.util.concurrent.CountDownLatch(1)
    @Volatile private var unavailable = false
    @Volatile private var duplicatePlaylistNames = false
    @Volatile private var delayedPlaylist: java.util.concurrent.CountDownLatch? = null
    private val playlistRequestStarted = java.util.concurrent.CountDownLatch(1)
    private val changedParents = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val fixtureSongs = listOf(Song("one", "First song", "Fixture artist"), Song("two", "Second song", "Fixture artist"), Song("one", "First song", "Fixture artist"))

    private fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("{\"subsonic-response\":{\"status\":\"ok\",$body}}")

    @Before fun connectFixtureBrowser() {
        val sampleBytes = 60 * 8000 * 2
        val wav = ByteBuffer.allocate(44 + sampleBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + sampleBytes); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(sampleBytes)
        }.array()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (unavailable) return MockResponse().setResponseCode(503)
                val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
                if (url.queryParameter("u") != "auto-fixture" || url.queryParameter("t").isNullOrBlank()) return MockResponse().setResponseCode(401)
                if (url.encodedPath.endsWith("/getPlaylist.view")) delayedPlaylist?.let {
                    playlistRequestStarted.countDown()
                    check(it.await(15, TimeUnit.SECONDS))
                }
                return when (url.encodedPath.substringAfterLast('/')) {
                    "getPlaylists.view" -> {
                        delayedPlaylists?.let {
                            playlistsRequestStarted.countDown()
                            check(it.await(15, TimeUnit.SECONDS))
                        }
                        val extra = if (duplicatePlaylistNames) ",{\"id\":\"other\",\"name\":\"Drive fixture\",\"songCount\":3}" else ""
                        ok("\"playlists\":{\"playlist\":[{\"id\":\"p/with:punctuation\",\"name\":\"Drive fixture\",\"songCount\":3}$extra]}")
                    }
                    "getPlaylist.view" -> ok("\"playlist\":{\"entry\":[{\"id\":\"one\",\"title\":\"First song\",\"artist\":\"Fixture artist\",\"duration\":60},{\"id\":\"two\",\"title\":\"Second song\",\"artist\":\"Fixture artist\",\"duration\":60},{\"id\":\"one\",\"title\":\"First song\",\"artist\":\"Fixture artist\",\"duration\":60}]}")
                    "stream.view" -> MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wav))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        runBlocking {
            graph.bootstrap()
            previousConnectionId = graph.credentials.value?.connection?.id?.takeIf { id -> graph.store.connections().any { it.id == id } }
            credentials = Credentials(Connection(UUID.randomUUID().toString(), "Auto fixture", server.url("/").toString(), "auto-fixture"), "fixture-only-password")
            graph.store.saveConnection(credentials)
            graph.activate(credentials.connection.id)
            graph.setSession(SessionRules.start(credentials.connection.id, "p/with:punctuation", "Drive fixture", fixtureSongs)
                .copy(queue = fixtureSongs, positionMs = 5000, shuffle = false))
        }
        lateinit var future: ListenableFuture<MediaBrowser>
        instrumentation.runOnMainSync {
            future = MediaBrowser.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java)))
                .setListener(object : MediaBrowser.Listener {
                    override fun onChildrenChanged(browser: MediaBrowser, parentId: String, itemCount: Int, params: androidx.media3.session.MediaLibraryService.LibraryParams?) {
                        changedParents.add(parentId)
                    }
                }).buildAsync()
        }
        browser = future.get(15, TimeUnit.SECONDS)
    }

    @After fun cleanupFixture() {
        delayedPlaylists?.countDown()
        delayedPlaylist?.countDown()
        if (::browser.isInitialized) {
            onMain { browser.pause(); browser.stop() }
            // Drain the controller requests before closing its fixture server. Do not forcibly
            // stop a still-bound Media3 service during its asynchronous foreground transition.
            onMain { browser.getLibraryRoot(null) }.get(15, TimeUnit.SECONDS)
            until { !graph.playback.value.playing }
            onMain { browser.release() }
        }
        if (::credentials.isInitialized) runBlocking {
            noSessionConnectionId?.let { graph.store.removeConnection(it) }
            graph.store.removeConnection(credentials.connection.id)
            previousConnectionId?.let { graph.activate(it) }
        }
        server.shutdown()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun root(): MediaItem {
        val result = onMain { browser.getLibraryRoot(null) }.get(15, TimeUnit.SECONDS)
        assertEquals("Auto must expose a real library root", 0, result.resultCode)
        return requireNotNull(result.value)
    }
    private fun children(id: String, page: Int = 0, size: Int = 100): List<MediaItem> {
        val result = onMain { browser.getChildren(id, page, size, null) }.get(15, TimeUnit.SECONDS)
        assertEquals("Auto must serve browsable content", 0, result.resultCode)
        return requireNotNull(result.value).toList()
    }
    private fun playlist(): MediaItem {
        val folder = children(root().mediaId).single { it.mediaMetadata.title == "Playlists" }
        return children(folder.mediaId).single()
    }
    private fun until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition()) { check(System.nanoTime() < deadline) { "Auto fixture state timed out" }; Thread.sleep(25) }
    }

    @Test fun browsePlaylistWithoutReplacingTheSavedQueue() {
        val saved = graph.session.value!!
        val items = children(playlist().mediaId)
        assertEquals(4, items.size) // Shuffle action plus every occurrence, including duplicate songs.
        assertEquals("Shuffle and play", items.first().mediaMetadata.title)
        assertEquals(4, items.map { it.mediaId }.toSet().size)
        assertTrue(items.all { it.mediaMetadata.isPlayable == true && it.localConfiguration == null })
        assertEquals(saved, graph.session.value)
        assertFalse(onMain { browser.playWhenReady })
        assertEquals(items.take(2), children(playlist().mediaId, 0, 2))
        assertTrue(children(playlist().mediaId, Int.MAX_VALUE, 100).isEmpty())
    }

    @Test fun shuffleSelectionStartsSharedPlaybackAndPreservesDuplicateOccurrences() {
        val shuffle = children(playlist().mediaId).first()
        onMain { browser.setMediaItem(shuffle); browser.prepare(); browser.play() }
        until { graph.playback.value.playing }
        val saved = graph.session.value!!
        assertEquals(listOf("one", "one", "two"), saved.queue.map { it.id }.sorted())
        assertTrue(saved.shuffle)
        assertEquals(3, onMain { browser.mediaItemCount })
        onMain { browser.pause() }
        until { !graph.playback.value.playing }
        val pausedOrder = graph.session.value!!.queue.map { it.id }
        onMain { browser.play() }
        until { graph.playback.value.playing }
        assertEquals(pausedOrder, graph.session.value!!.queue.map { it.id })
    }

    @Test fun queueSelectionUsesTheExistingQueueIdentity() {
        val saved = graph.session.value!!
        val folder = children(root().mediaId).single { it.mediaMetadata.title == "Queue" }
        val queue = children(folder.mediaId)
        assertEquals(3, queue.size)
        assertNotEquals(queue[0].mediaId, queue[2].mediaId)
        onMain { browser.setMediaItem(queue[1]); browser.prepare(); browser.play() }
        until { graph.playback.value.playing && graph.session.value?.index == 1 }
        assertEquals(saved.queueId, graph.session.value!!.queueId)
        assertEquals(saved.queue, graph.session.value!!.queue)
        assertEquals(3, onMain { browser.mediaItemCount })
    }

    @Test fun arbitraryMediaUrisAndStaleQueueIdsCannotReplacePlayback() {
        val folder = children(root().mediaId).single { it.mediaMetadata.title == "Queue" }
        val stale = children(folder.mediaId).first()
        runBlocking { graph.setSession(SessionRules.start(credentials.connection.id, "p/with:punctuation", "Drive fixture", fixtureSongs)) }
        val saved = graph.session.value!!
        val malicious = MediaItem.Builder().setMediaId("https://attacker.invalid/song").setUri("https://attacker.invalid/song").build()
        onMain { browser.setMediaItem(malicious) }
        instrumentation.waitForIdleSync()
        onMain { browser.setMediaItem(stale) }
        instrumentation.waitForIdleSync()
        // A service round-trip barriers both preceding controller requests.
        onMain { browser.getLibraryRoot(null) }.get(15, TimeUnit.SECONDS)
        assertEquals(saved.queueId, graph.session.value!!.queueId)
        assertEquals(saved.queue, graph.session.value!!.queue)
        assertNotEquals("https://attacker.invalid/song", onMain { browser.currentMediaItem?.localConfiguration?.uri?.toString() })
    }

    @Test fun unavailableServerReturnsAnErrorAndTheSameRequestCanRecover() {
        val p = playlist()
        unavailable = true
        val failed = onMain { browser.getChildren(p.mediaId, 0, 100, null) }.get(15, TimeUnit.SECONDS)
        assertNotEquals(0, failed.resultCode)
        unavailable = false
        assertEquals(4, children(p.mediaId).size)
        assertFalse(onMain { browser.playWhenReady })
    }

    @Test fun playlistRowAdvertisesAndExecutesDirectShuffleAction() {
        val p = playlist()
        val action = "com.everyonesoftware.audio.SHUFFLE_PLAYLIST"
        assertTrue(p.mediaMetadata.supportedCommands.contains(action))
        val args = Bundle().apply { putString(MediaConstants.EXTRA_KEY_MEDIA_ID, p.mediaId) }
        val result = onMain { browser.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args) }.get(15, TimeUnit.SECONDS)
        assertEquals(0, result.resultCode)
        until { graph.playback.value.playing }
        assertTrue(graph.session.value!!.shuffle)
        assertEquals(listOf("one", "one", "two"), graph.session.value!!.queue.map { it.id }.sorted())
        val saved = graph.session.value!!
        val invalidArgs = Bundle().apply { putString(MediaConstants.EXTRA_KEY_MEDIA_ID, "https://attacker.invalid/playlist") }
        val rejected = onMain { browser.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), invalidArgs) }.get(15, TimeUnit.SECONDS)
        assertNotEquals(0, rejected.resultCode)
        assertEquals(saved.queueId, graph.session.value!!.queueId)
    }

    @Test fun voiceSearchSelectsAMatchingPlaylistAndBlankSearchResumes() {
        fun request(query: String) = MediaItem.Builder().setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).build()).build()
        onMain { browser.setMediaItem(request("Drive fixture")); browser.prepare(); browser.play() }
        until { graph.playback.value.playing && graph.session.value?.shuffle == true }
        onMain { browser.pause() }
        until { !graph.playback.value.playing }
        val saved = graph.session.value!!
        onMain { browser.setMediaItem(request("")); browser.prepare(); browser.play() }
        until { graph.playback.value.playing }
        assertEquals(saved.queueId, graph.session.value!!.queueId)
        assertEquals(saved.queue, graph.session.value!!.queue)
    }

    @Test fun delayedCarSelectionCannotOverwriteANewerPhoneQueue() {
        val p = playlist()
        val gate = java.util.concurrent.CountDownLatch(1)
        delayedPlaylist = gate
        val args = Bundle().apply { putString(MediaConstants.EXTRA_KEY_MEDIA_ID, p.mediaId) }
        val future = onMain { browser.sendCustomCommand(SessionCommand("com.everyonesoftware.audio.SHUFFLE_PLAYLIST", Bundle.EMPTY), args) }
        assertTrue(playlistRequestStarted.await(10, TimeUnit.SECONDS))
        val newer = SessionRules.start(credentials.connection.id, "new-phone-playlist", "New phone playlist", fixtureSongs)
        runBlocking { graph.setSession(newer) }
        gate.countDown()
        val result = future.get(15, TimeUnit.SECONDS)
        assertNotEquals("A stale car request must fail, not undo the phone selection", 0, result.resultCode)
        assertEquals(newer.queueId, graph.session.value!!.queueId)
        assertEquals(newer.queue, graph.session.value!!.queue)
    }

    @Test fun phoneQueueReplacementNotifiesSubscribedCarBrowsers() {
        val folder = children(root().mediaId).single { it.mediaMetadata.title == "Queue" }
        assertEquals(0, onMain { browser.subscribe(folder.mediaId, null) }.get(15, TimeUnit.SECONDS).resultCode)
        until { changedParents.contains(folder.mediaId) }
        changedParents.clear()
        val newer = SessionRules.start(credentials.connection.id, "phone", "Phone selection", fixtureSongs.reversed())
        runBlocking { graph.setSession(newer) }
        until { changedParents.contains(folder.mediaId) }
        val queue = children(folder.mediaId)
        assertEquals(newer.queue.map { it.title }, queue.map { it.mediaMetadata.title })
    }

    @Test fun ambiguousVoiceRequestDoesNotPickAnArbitraryPlaylist() {
        duplicatePlaylistNames = true
        val saved = graph.session.value!!
        val result = runCatching { runBlocking { CarLibrary(graph).resolveSearch("Drive fixture") } }
        assertTrue("Duplicate exact names must require a manual choice", result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(saved, graph.session.value)
    }

    @Test fun coldVoiceIntentWithSavedAccountAndNoSessionSurvivesStartupRefresh() {
        val id = UUID.randomUUID().toString()
        noSessionConnectionId = id
        runBlocking {
            graph.store.saveConnection(credentials.copy(connection = credentials.connection.copy(id = id, name = "No-session voice fixture")))
            assertNull(graph.store.session(id))
            graph.activate(id)
        }
        assertNull(graph.session.value)
        val gate = java.util.concurrent.CountDownLatch(1)
        delayedPlaylists = gate
        val intent = Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH").setPackage(app.packageName)
            .putExtra(android.app.SearchManager.QUERY, "Drive fixture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val modelRef = java.util.concurrent.atomic.AtomicReference<com.everyonesoftware.audio.ui.AudioViewModel>()
        val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
        val callback = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
            if (activity is com.everyonesoftware.audio.MainActivity && stage == androidx.test.runner.lifecycle.Stage.RESUMED) {
                modelRef.set(androidx.lifecycle.ViewModelProvider(activity)[com.everyonesoftware.audio.ui.AudioViewModel::class.java])
            }
        }
        onMain { monitor.addLifecycleCallback(callback) }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        // Release independently of ActivityScenario's idle wait: the loading indicator remains
        // animated while HTTP is gated, so launch/onActivity cannot be the response barrier.
        val releaseResponse = executor.submit {
            try {
                assertTrue("The real voice/startup playlist request must reach the fixture", playlistsRequestStarted.await(10, TimeUnit.SECONDS))
                until { modelRef.get()?.state?.value?.booting == false }
                // Barrier startup, including its automatic refresh, before releasing HTTP.
                instrumentation.runOnMainSync { }
                assertNull("Voice request must still be pending before the response", modelRef.get().state.value.error)
                assertNull("No queue may exist before the delayed response", graph.session.value)
            } finally { gate.countDown() }
        }
        try {
            androidx.test.core.app.ActivityScenario.launch<com.everyonesoftware.audio.MainActivity>(intent).use {
                releaseResponse.get(15, TimeUnit.SECONDS)
                val model = requireNotNull(modelRef.get())
                until { graph.playback.value.playing || model.state.value.error != null }
                assertNull("Voice playback must succeed after initialization", model.state.value.error)
                val session = requireNotNull(graph.session.value)
                assertEquals(id, session.connectionId)
                assertEquals("p/with:punctuation", session.playlistId)
                assertTrue(session.shuffle)
                assertEquals(listOf("one", "one", "two"), session.queue.map { it.id }.sorted())
                assertEquals(3, onMain { browser.mediaItemCount })
                assertTrue(onMain { browser.isPlaying })
                assertEquals(com.everyonesoftware.audio.ui.Screen.PLAYER, model.state.value.screen)
            }
        } finally {
            gate.countDown()
            executor.shutdownNow()
            onMain { monitor.removeLifecycleCallback(callback) }
        }
    }

    @Test fun voiceSearchIntentOpensTheNativePlayer() {
        val intent = Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH").setPackage(app.packageName)
            .putExtra(android.app.SearchManager.QUERY, "Drive fixture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertNotNull("Voice search must be discoverable", app.packageManager.resolveActivity(intent, 0))
        androidx.test.core.app.ActivityScenario.launch<com.everyonesoftware.audio.MainActivity>(intent).use { scenario ->
            until { graph.playback.value.playing && graph.session.value?.shuffle == true }
            val queueId = graph.session.value!!.queueId
            scenario.recreate()
            assertEquals("Recreation must not replay the voice selection", queueId, graph.session.value!!.queueId)
        }
    }

    @Test fun legacyPlatformBrowserCanBrowseAndPlayFromMediaId() {
        val connected = java.util.concurrent.CountDownLatch(1)
        lateinit var legacy: android.media.browse.MediaBrowser
        onMain {
            legacy = android.media.browse.MediaBrowser(app, ComponentName(app, PlaybackService::class.java),
                object : android.media.browse.MediaBrowser.ConnectionCallback() {
                    override fun onConnected() { connected.countDown() }
                }, null)
            legacy.connect()
        }
        try {
            assertTrue(connected.await(15, TimeUnit.SECONDS))
            val items = java.util.concurrent.LinkedBlockingQueue<List<android.media.browse.MediaBrowser.MediaItem>>()
            fun legacyChildren(id: String): List<android.media.browse.MediaBrowser.MediaItem> {
                onMain { legacy.subscribe(id, object : android.media.browse.MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, children: MutableList<android.media.browse.MediaBrowser.MediaItem>) { items.offer(children) }
                }) }
                return requireNotNull(items.poll(15, TimeUnit.SECONDS))
            }
            val folders = legacyChildren(onMain { legacy.root })
            val playlists = legacyChildren(folders.single { it.description.title == "Playlists" }.mediaId!!)
            val shuffle = legacyChildren(playlists.single().mediaId!!).first()
            onMain { android.media.session.MediaController(app, legacy.sessionToken).transportControls.playFromMediaId(shuffle.mediaId, null) }
            until { graph.playback.value.playing }
            assertEquals(3, graph.session.value!!.queue.size)
        } finally { onMain { legacy.disconnect() } }
    }
}
