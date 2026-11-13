package com.everyonesoftware.audio.playback

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadata
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.media3.common.Player
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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Service + modern/platform controllers, not a Bluetooth stack or vehicle simulation. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SessionArtworkTest {
    private val app get() = ApplicationProvider.getApplicationContext<AudioApplication>()
    private val graph get() = app.graph
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val server = MockWebServer()
    private lateinit var browser: MediaBrowser
    private lateinit var credentials: Credentials
    private var previousConnection: String? = null
    private val extraConnections = mutableListOf<String>()
    private val coverRequests = AtomicInteger()
    private val coverStarted = CountDownLatch(1)
    @Volatile private var coverGate: CountDownLatch? = null
    @Volatile private var failures = 0
    @Volatile private var invalidCover = false
    @Volatile private var coverColor = Color.RED
    @Volatile private var imageWidth = 1200
    @Volatile private var imageHeight = 900
    private val songs = listOf(
        Song("one", "First", "Fixture", coverArtId = "shared-cover"),
        Song("two", "Second", "Fixture", coverArtId = "second-cover"),
        Song("none", "No cover", "Fixture"),
        Song("one", "First", "Fixture", coverArtId = "shared-cover"),
    )

    @Before fun connect() {
        val samples = 60 * 8000 * 2
        val wav = ByteBuffer.allocate(44 + samples).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples)
        }.array()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                if (url.queryParameter("u") != "art-fixture" || url.queryParameter("t").isNullOrBlank()) return MockResponse().setResponseCode(401)
                return when (url.encodedPath.substringAfterLast('/')) {
                    "stream.view" -> MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wav))
                    "getCoverArt.view" -> {
                        val count = coverRequests.incrementAndGet()
                        // Capture before waiting so a late old account really returns old pixels.
                        val color = coverColor
                        coverStarted.countDown()
                        coverGate?.await(15, TimeUnit.SECONDS)
                        if (count <= failures) MockResponse().setResponseCode(503)
                        else if (invalidCover) MockResponse().setBody("not an image")
                        else MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(image(color)))
                    }
                    "getPlaylists.view" -> MockResponse().setBody("{\"subsonic-response\":{\"status\":\"ok\",\"playlists\":{\"playlist\":[]}}}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        runBlocking {
            graph.bootstrap()
            previousConnection = graph.credentials.value?.connection?.id?.takeIf { id -> graph.store.connections().any { it.id == id } }
            credentials = Credentials(Connection(UUID.randomUUID().toString(), "Artwork fixture", server.url("/").toString(), "art-fixture"), "fixture-only")
            graph.store.saveConnection(credentials)
            graph.activate(credentials.connection.id)
            graph.setSession(SessionRules.start(credentials.connection.id, "art", "Artwork", songs).copy(queue = songs, index = 0, shuffle = false))
        }
        lateinit var future: ListenableFuture<MediaBrowser>
        onMain { future = MediaBrowser.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
        browser = future.get(15, TimeUnit.SECONDS)
        until { onMain { browser.mediaItemCount == songs.size } }
    }

    @After fun cleanup() {
        coverGate?.countDown()
        if (::browser.isInitialized) {
            onMain { browser.pause(); browser.stop() }
            onMain { browser.getLibraryRoot(null) }.get(15, TimeUnit.SECONDS)
            onMain { browser.release() }
        }
        if (::credentials.isInitialized) runBlocking {
            graph.store.removeConnection(credentials.connection.id)
            extraConnections.forEach { graph.store.removeConnection(it) }
            previousConnection?.let { graph.activate(it) }
        }
        server.shutdown()
    }

    private fun image(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
        } finally { bitmap.recycle() }
    }
    private fun <T> onMain(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition()) { check(System.nanoTime() < deadline) { "Artwork fixture state timed out" }; Thread.sleep(25) }
    }
    private fun artwork() = onMain { browser.mediaMetadata.artworkData }
    private fun currentColor(): Int {
        val bytes = requireNotNull(artwork())
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        return try { bitmap.getPixel(0, 0) } finally { bitmap.recycle() }
    }
    private fun assertMostlyRed() { val color = currentColor(); assertTrue(Color.red(color) > 200); assertTrue(Color.blue(color) < 40) }

    @Test fun publishesBoundedBytesToModernAndLegacyControllersWithoutChangingPlayback() {
        onMain { browser.play() }
        until { graph.playback.value.playing }
        val before = graph.session.value!!
        until { artwork() != null }
        val bytes = requireNotNull(artwork())
        assertTrue(bytes.size <= 128 * 1024)
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try { assertTrue(bitmap.width <= 256); assertTrue(bitmap.height <= 256) } finally { bitmap.recycle() }
        assertMostlyRed()
        val fullUri = requireNotNull(onMain { browser.mediaMetadata.artworkUri }) { "Car hosts need a local high-resolution artwork URI" }
        assertEquals("content", fullUri.scheme)
        val fullBytes = requireNotNull(app.contentResolver.openInputStream(fullUri)).use { it.readBytes() }
        val fullBitmap = requireNotNull(BitmapFactory.decodeByteArray(fullBytes, 0, fullBytes.size))
        try { assertEquals(1024, fullBitmap.width); assertEquals(768, fullBitmap.height) } finally { fullBitmap.recycle() }
        assertEquals("Both versions must share a single artwork download", 1, coverRequests.get())
        onMain { assertTrue(browser.isPlaying); assertNull(browser.playerError) }
        val connected = CountDownLatch(1)
        lateinit var legacyBrowser: android.media.browse.MediaBrowser
        onMain {
            legacyBrowser = android.media.browse.MediaBrowser(app, ComponentName(app, PlaybackService::class.java),
                object : android.media.browse.MediaBrowser.ConnectionCallback() { override fun onConnected() { connected.countDown() } }, null)
            legacyBrowser.connect()
        }
        try {
            assertTrue(connected.await(15, TimeUnit.SECONDS))
            val legacy = onMain { android.media.session.MediaController(app, legacyBrowser.sessionToken) }
            until { onMain { legacy.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null } }
            onMain {
                val metadata = requireNotNull(legacy.metadata)
                assertEquals("First", metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
                assertEquals(fullUri.toString(), metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI))
                val fallback = requireNotNull(metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
                assertTrue(fallback.width <= 256 && fallback.height <= 256)
            }
        } finally { onMain { legacyBrowser.disconnect() } }
        assertEquals(before.queueId, graph.session.value!!.queueId)
        assertEquals(before.queue, graph.session.value!!.queue)
        assertEquals(0, graph.session.value!!.index)
    }

    @Test fun publishingBothImagesDoesNotResetPositionOrRepeatMode() {
        until { artwork() != null }
        val firstUri = onMain { browser.mediaMetadata.artworkUri }
        val gate = CountDownLatch(1)
        coverGate = gate
        onMain { browser.repeatMode = Player.REPEAT_MODE_ALL; browser.seekToNextMediaItem(); browser.play() }
        until { coverRequests.get() >= 2 && onMain { browser.isPlaying && browser.currentPosition >= 1500 } }
        val position = onMain { browser.currentPosition }
        gate.countDown()
        until { onMain { browser.mediaMetadata.artworkUri != null && browser.mediaMetadata.artworkUri != firstUri } }
        onMain {
            assertTrue("Metadata publication must not restart the track", browser.currentPosition >= position)
            assertEquals(Player.REPEAT_MODE_ALL, browser.repeatMode)
            assertEquals(1, browser.currentMediaItemIndex)
            assertTrue(browser.isPlaying)
            assertNull(browser.playerError)
        }
    }

    @Test fun smallSourceIsNotUpscaledForTheHighResolutionUri() {
        until { artwork() != null }
        val firstUri = onMain { browser.mediaMetadata.artworkUri }
        imageWidth = 96
        imageHeight = 64
        onMain { browser.seekToNextMediaItem() }
        until { onMain { browser.mediaMetadata.artworkUri != null && browser.mediaMetadata.artworkUri != firstUri } }
        val bytes = requireNotNull(app.contentResolver.openInputStream(requireNotNull(onMain { browser.mediaMetadata.artworkUri }))).use { it.readBytes() }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try { assertEquals(96, bitmap.width); assertEquals(64, bitmap.height) } finally { bitmap.recycle() }
    }

    @Test fun excessiveSourceDimensionsNeverEnterSessionMetadata() {
        until { artwork() != null }
        imageWidth = 16385
        imageHeight = 1
        onMain { browser.seekToNextMediaItem(); browser.play() }
        until { coverRequests.get() >= 3 && graph.playback.value.playing }
        Thread.sleep(250)
        assertNull(artwork())
        onMain { assertNull(browser.mediaMetadata.artworkUri); assertNull(browser.playerError); assertTrue(browser.isPlaying) }
    }

    @Test fun delayedCoverNeverBlocksPlaybackOrAppearsOnAnotherTrack() {
        // The first may already have loaded during setup; gate the second track explicitly.
        until { artwork() != null }
        val gate = CountDownLatch(1)
        coverGate = gate
        onMain { browser.seekToNextMediaItem(); browser.play() }
        until { coverRequests.get() >= 2 && graph.playback.value.playing }
        onMain { browser.seekToNextMediaItem() }
        until { onMain { browser.mediaMetadata.title == "No cover" } }
        gate.countDown()
        Thread.sleep(500)
        assertNull(artwork())
        onMain { assertNull(browser.mediaMetadata.artworkUri); assertTrue(browser.isPlaying); assertNull(browser.playerError) }
        assertEquals(2, graph.session.value!!.index)
    }

    @Test fun cacheReusesCoverAndDoesNotAttachImagesToTheWholeQueue() {
        until { artwork() != null }
        assertEquals(1, coverRequests.get())
        onMain { browser.seekToDefaultPosition(3) }
        until { onMain { browser.currentMediaItemIndex == 3 } && artwork() != null }
        Thread.sleep(200)
        assertEquals(1, coverRequests.get())
        onMain {
            assertNull(browser.getMediaItemAt(0).mediaMetadata.artworkData)
            assertNull(browser.getMediaItemAt(0).mediaMetadata.artworkUri)
            assertNotNull(browser.getMediaItemAt(3).mediaMetadata.artworkData)
            assertNotNull(browser.getMediaItemAt(3).mediaMetadata.artworkUri)
        }
    }

    @Test fun transientFailureRetriesWithoutFailingAudio() {
        // Use a fresh cover after setting failure behavior (first cover already requested).
        until { artwork() != null }
        failures = coverRequests.get() + 1
        onMain { browser.seekToNextMediaItem(); browser.play() }
        until { coverRequests.get() >= 3 && artwork() != null && onMain { browser.mediaMetadata.title == "Second" && browser.isPlaying } }
        assertTrue(coverRequests.get() >= 3)
        onMain { assertNull(browser.playerError); assertTrue(browser.isPlaying) }
    }

    @Test fun selectingCurrentQueueOccurrenceKeepsCachedArtwork() {
        until { artwork() != null }
        val saved = graph.session.value!!
        val items = onMain { browser.getChildren(CarMediaIds.QUEUE, 0, 100, null) }.get(15, TimeUnit.SECONDS)
        val first = requireNotNull(items.value).first()
        repeat(2) {
            onMain { browser.setMediaItem(first); browser.prepare(); browser.play() }
            until { graph.playback.value.playing && artwork() != null }
            Thread.sleep(250)
            assertNotNull(artwork())
        }
        assertEquals(1, coverRequests.get())
        assertEquals(saved.queueId, graph.session.value!!.queueId)
    }

    @Test fun invalidImageLeavesPlaybackHealthy() {
        until { artwork() != null }
        invalidCover = true
        val before = coverRequests.get()
        onMain { browser.seekToNextMediaItem(); browser.play() }
        until { coverRequests.get() >= before + 2 && graph.playback.value.playing }
        Thread.sleep(250)
        assertNull(artwork())
        onMain { assertNull(browser.playerError); assertTrue(browser.isPlaying) }
    }

    @Test fun lateOldAccountCoverCannotOverwriteNewAccount() {
        until { artwork() != null }
        val gate = CountDownLatch(1)
        coverGate = gate
        onMain { browser.seekToNextMediaItem() }
        until { coverRequests.get() >= 2 }
        coverColor = Color.BLUE
        coverGate = null
        val replacement = credentials.copy(connection = credentials.connection.copy(id = UUID.randomUUID().toString()))
        extraConnections.add(replacement.connection.id)
        runBlocking {
            graph.store.saveConnection(replacement)
            graph.activate(replacement.connection.id)
            graph.setSession(SessionRules.start(replacement.connection.id, "art", "Artwork", songs).copy(queue = songs, index = 1, shuffle = false))
        }
        until { artwork() != null && Color.blue(currentColor()) > 200 }
        gate.countDown()
        Thread.sleep(500)
        assertTrue(Color.blue(currentColor()) > 200)
        assertTrue(Color.red(currentColor()) < 40)
        assertEquals(replacement.connection.id, graph.session.value!!.connectionId)
    }

    @Test fun accountSwitchDoesNotReuseOldAccountsCover() {
        until { artwork() != null }
        assertMostlyRed()
        coverColor = Color.BLUE
        val replacement = credentials.copy(connection = credentials.connection.copy(id = UUID.randomUUID().toString()))
        extraConnections.add(replacement.connection.id)
        runBlocking {
            graph.store.saveConnection(replacement)
            graph.activate(replacement.connection.id)
            graph.setSession(SessionRules.start(replacement.connection.id, "art", "Artwork", songs).copy(queue = songs, index = 0, shuffle = false))
        }
        until { artwork() != null && Color.blue(currentColor()) > 200 }
        assertTrue(Color.red(currentColor()) < 40)
        assertTrue(coverRequests.get() >= 2)
    }
}
