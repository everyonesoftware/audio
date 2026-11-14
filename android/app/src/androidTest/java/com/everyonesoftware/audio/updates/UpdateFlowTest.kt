package com.everyonesoftware.audio.updates

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.everyonesoftware.audio.AudioGraph
import com.everyonesoftware.audio.BuildConfig
import com.everyonesoftware.audio.PlaybackSnapshot
import com.everyonesoftware.audio.data.Connection
import com.everyonesoftware.audio.data.Credentials
import com.everyonesoftware.audio.data.ListeningSession
import com.everyonesoftware.audio.data.Song
import com.everyonesoftware.audio.ui.AudioViewModel
import com.everyonesoftware.audio.ui.Screen
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Production UpdateClient -> UpdateManager -> PackageManager/FileProvider integration.
 * Only HTTP transport is redirected to loopback; package identities/signers are never substituted.
 *
 * The optional fixtureApk instrumentation argument names an actual higher-version, same-key APK.
 * Default: target app external files/update-fixture.apk. Only the successful-upgrade test skips
 * when no fixture exists; a supplied but invalid fixture FAILS. Never launches the installer:
 * replacing the instrumented package kills the runner. The parent runs the system upgrade probe.
 *
 * PlaybackSnapshot below is synthetic, not a claim of ExoPlayer/vehicle playback verification.
 */
@RunWith(AndroidJUnit4::class)
class UpdateFlowTest {
    private lateinit var app: Application
    private lateinit var server: MockWebServer
    private lateinit var musicServer: MockWebServer
    private lateinit var graph: AudioGraph
    private lateinit var model: AudioViewModel
    private val owner = ViewModelStore()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val extraScopes = mutableListOf<CoroutineScope>()
    private var releaseGate: CountDownLatch? = null
    private var previousSelection: String? = null
    private var preferencesBefore: Map<String, *> = emptyMap<String, Any>()
    private lateinit var backup: File
    private lateinit var directory: File
    private var cacheIsolated = false
    private lateinit var credentials: Credentials
    private lateinit var session: ListeningSession
    @Volatile private var count = 1
    @Volatile private var bytes = byteArrayOf(1, 2, 3)
    @Volatile private var hash = ""

    @Before fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        val preferences = app.getSharedPreferences("music_updates", Context.MODE_PRIVATE)
        preferencesBefore = preferences.all.toMap()
        assertTrue(preferences.edit().clear().commit())
        directory = File(app.cacheDir, "updates")
        backup = File(app.cacheDir, "update-flow-backup-${UUID.randomUUID()}")
        if (directory.exists()) assertTrue("Preserve prior update cache", directory.renameTo(backup))
        cacheIsolated = true
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                return when (request.requestUrl?.encodedPath) {
                    API_PATH -> {
                        releaseGate?.await(15, TimeUnit.SECONDS)
                        json(publishedRelease())
                    }
                    "$ASSET_PATH/v0.1.$count/release.json" -> json(manifest())
                    "$ASSET_PATH/v0.1.$count/Music-0.1.$count.apk" ->
                        MockResponse().setBody(Buffer().write(bytes))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        musicServer = MockWebServer()
        musicServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = json(
                """{"subsonic-response":{"status":"ok","version":"1.16.1","albumList2":{"album":[]}}}"""
            )
        }
        musicServer.start()
        graph = AudioGraph(app, client())
        previousSelection = graph.store.selectedConnectionId()
        val id = "update-flow-${UUID.randomUUID()}"
        credentials = Credentials(Connection(id, "Updater fixture", musicServer.url("/").toString(),
            "update-flow-fixture-user"), "test-only-update-password")
        session = ListeningSession(id, "fixture-playlist", "Update fixture",
            listOf(Song("duplicate", "First occurrence", "Fixture", userRating = 4),
                Song("other", "Other track", "Fixture", userRating = 2),
                Song("duplicate", "Repeated occurrence", "Fixture", userRating = 4)),
            index = 2, positionMs = 34_567L, shuffle = true, repeatMode = 2,
            queueId = "update-flow-queue", sourceType = "playlist")
        graph.store.saveConnection(credentials)
        graph.store.setSelectedLibraries(id, setOf("fixture-library"))
        graph.store.saveSession(session)
        graph.activate(id)
        onMain { model = AudioViewModel(app, graph, connectController = false); owner.put("fixture", model) }
        until { !model.state.value.booting && !model.state.value.albumsRefreshing }
        // No controller/service is connected. Assert update actions preserve a nonzero synthetic
        // playing snapshot as well as the actual persisted queue/account checkpoint.
        onMain { graph.playback.value = snapshot }
        until { model.state.value.playing && model.state.value.positionMs == session.positionMs }
        configure(BuildConfig.VERSION_CODE + 1, byteArrayOf(1, 2, 3))
    }

    @After fun tearDown() = runBlocking {
        releaseGate?.countDown()
        if (::graph.isInitialized) {
            onMain { owner.clear(); graph.updates.coordinator.cancelDownload() }
            // Finish cancellation/file cleanup before restoring the cache and preferences.
            until { directory.listFiles().orEmpty().none { it.extension == "part" } }
            extraScopes.forEach { it.cancel() }
            if (::credentials.isInitialized) graph.store.removeConnection(credentials.connection.id)
            previousSelection?.takeIf { id -> graph.store.connections().any { it.id == id } }
                ?.let { graph.store.selectConnection(it) }
        }
        if (::server.isInitialized) server.shutdown()
        if (::musicServer.isInitialized) musicServer.shutdown()
        if (cacheIsolated) {
            if (directory.exists()) assertTrue("Remove only fixture cache", directory.deleteRecursively())
            if (backup.exists()) assertTrue("Restore prior update cache", backup.renameTo(directory))
        }
        if (::app.isInitialized) {
            val editor = app.getSharedPreferences("music_updates", Context.MODE_PRIVATE).edit().clear()
            preferencesBefore.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Long -> editor.putLong(key, value)
                    is Int -> editor.putInt(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            assertTrue(editor.commit())
        }
    }

    @Test fun currentStableReleaseIsNotAnUpgradeAndManualCheckPreservesListeningState() = runBlocking {
        configure(BuildConfig.VERSION_CODE, bytes)
        onMain { model.showScreen(Screen.UPDATES); model.checkForUpdates() }
        until { model.state.value.updates.lastCheckedAt != null }
        assertEquals(UpdateStage.IDLE, model.state.value.updates.stage)
        assertNull(model.state.value.updates.release)
        assertEquals(listOf(API_PATH), requests.map { it.requestUrl!!.encodedPath })
        assertPreserved()
        assertUnauthenticated()
    }

    @Test fun duplicateChecksShareOneRequestAndAutomaticChecksHonorCachedAttempt() = runBlocking {
        val gate = CountDownLatch(1)
        releaseGate = gate
        val first = withContext(Dispatchers.Main) { graph.updates.coordinator.check() }
        until { requests.size == 1 }
        val duplicate = withContext(Dispatchers.Main) {
            model.checkForUpdates()
            graph.updates.coordinator.check()
        }
        assertSame(first, duplicate)
        assertEquals(UpdateStage.CHECKING, model.state.value.updates.stage)
        assertPreserved()
        gate.countDown()
        withTimeout(30_000) { first!!.join() }
        until { model.state.value.updates.stage == UpdateStage.AVAILABLE }
        assertEquals(2, requests.size)
        val automatic = withContext(Dispatchers.Main) { graph.updates.coordinator.check(automatic = true) }
        assertNull(automatic)
        assertEquals(2, requests.size)
        assertPreserved()
        assertUnauthenticated()
    }

    @Test fun releaseMetadataAndAutomaticPreferenceRestoreWithoutAnotherNetworkRequest() = runBlocking {
        checkViaModel()
        onMain { model.setAutomaticUpdates(false) }
        val before = graph.updates.state.value
        val restored = newManager()
        assertEquals(UpdateStage.AVAILABLE, restored.state.value.stage)
        assertEquals(before.release, restored.state.value.release)
        assertEquals(before.lastCheckedAt, restored.state.value.lastCheckedAt)
        assertFalse(restored.state.value.automaticChecks)
        onMain { restored.onForeground() }
        assertEquals(2, requests.size)
        assertPreserved()
        assertUnauthenticated()
    }

    @Test fun realInstalledApkAdvertisedAsHigherVersionFailsVerificationAndRemovesDownload() = runBlocking {
        // The transport/hash are valid, but the real archive has the installed version: no
        // identity seam can accidentally turn this into a successful higher-version test.
        configure(BuildConfig.VERSION_CODE + 1, File(app.applicationInfo.sourceDir).readBytes())
        checkViaModel()
        onMain { model.downloadUpdate(); model.downloadUpdate() }
        until { model.state.value.updates.stage == UpdateStage.ERROR }
        assertEquals("The update could not be downloaded or verified. Nothing was installed. Try again.",
            model.state.value.updates.error)
        assertFalse(File(directory, "update.apk").exists())
        assertTrue(directory.listFiles().orEmpty().none { it.extension == "part" })
        assertEquals(1, requests.count { it.requestUrl!!.encodedPath.endsWith(".apk") })
        assertPreserved()
        assertUnauthenticated()
    }

    @Test fun realHigherSignedApkDownloadsVerifiesAndRestoresReadyCacheWithoutChangingListeningState() = runBlocking {
        val argument = InstrumentationRegistry.getArguments().getString("fixtureApk")
        val fixture = argument?.let(::File) ?: File(app.getExternalFilesDir(null), "update-fixture.apk")
        if (argument == null) assumeTrue("Seed a real higher signed update-fixture.apk to run upgrade path", fixture.isFile)
        assertTrue("Explicit fixtureApk must exist", fixture.isFile)
        val installed = UpdateInstaller.readIdentity(app, null)
        val archive = UpdateInstaller.readIdentity(app, fixture)
        assertEquals(app.packageName, archive.packageName)
        assertTrue("Fixture must really be newer than installed app", archive.versionCode > installed.versionCode)
        assertTrue(archive.currentSigners.isNotEmpty())
        assertEquals(installed.currentSigners.map(::hex).sorted(), archive.currentSigners.map(::hex).sorted())
        assertEquals("0.1.${archive.versionCode}", archive.versionName)
        configure(archive.versionCode.toInt(), fixture.readBytes())
        checkViaModel()
        assertPreserved()
        onMain { model.downloadUpdate(); model.downloadUpdate() }
        until { model.state.value.updates.stage in setOf(UpdateStage.READY, UpdateStage.ERROR) }
        assertEquals(model.state.value.updates.error, UpdateStage.READY, model.state.value.updates.stage)
        assertEquals(bytes.size.toLong(), model.state.value.updates.downloadedBytes)
        val candidate = File(directory, "update.apk")
        assertEquals(hash, digest(candidate.readBytes()))
        assertTrue(directory.listFiles().orEmpty().none { it.extension == "part" })
        assertPreserved()
        val release = requireNotNull(graph.updates.state.value.release)
        // This is the production installer with actual current/archive PackageManager identities.
        val intent = graph.updates.verifiedInstallIntent(candidate, release)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("${app.packageName}.updates", intent.data!!.authority)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(intent.data, intent.clipData!!.getItemAt(0).uri)
        app.contentResolver.openInputStream(intent.data!!)!!.use { assertEquals(hash, digest(it.readBytes())) }
        assertPreserved()
        // Recreate the real manager (not the process). Persisted metadata plus cache bytes must
        // pass PackageManager/hash preflight again, without a second APK request.
        val beforeRequests = requests.size
        val restored = newManager()
        assertEquals(UpdateStage.AVAILABLE, restored.state.value.stage)
        val cached = withContext(Dispatchers.Main) { restored.coordinator.download() }
        withTimeout(60_000) { requireNotNull(cached).join() }
        assertEquals(UpdateStage.READY, restored.state.value.stage)
        assertEquals(release, restored.state.value.release)
        assertEquals(beforeRequests, requests.size)
        assertEquals(1, requests.count { it.requestUrl!!.encodedPath.endsWith(".apk") })
        assertPreserved()
        assertUnauthenticated()
    }

    private suspend fun checkViaModel() {
        onMain { model.showScreen(Screen.UPDATES); model.checkForUpdates() }
        until { model.state.value.updates.stage in setOf(UpdateStage.AVAILABLE, UpdateStage.ERROR) }
        assertEquals(model.state.value.updates.error, UpdateStage.AVAILABLE, model.state.value.updates.stage)
    }

    private suspend fun assertPreserved() {
        assertEquals(credentials, graph.credentials.value)
        assertEquals(credentials, graph.store.credentials(credentials.connection.id))
        assertEquals(credentials.connection.id, graph.store.selectedConnectionId())
        assertEquals(setOf("fixture-library"), graph.store.selectedLibraries(credentials.connection.id))
        assertEquals(session, graph.session.value)
        assertEquals(session, graph.store.session(credentials.connection.id))
        assertEquals(session, model.state.value.session)
        assertEquals(credentials.connection, model.state.value.connection)
        assertEquals(snapshot, graph.playback.value)
        assertTrue("Synthetic playing state must not be paused by check/download", model.state.value.playing)
        assertEquals(session.positionMs, model.state.value.positionMs)
    }

    private fun assertUnauthenticated() {
        assertTrue(requests.isNotEmpty())
        requests.forEach { request ->
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("Proxy-Authorization"))
            assertNull(request.getHeader("Cookie"))
            assertNull(request.requestUrl!!.query)
            assertEquals("Music-Android-Updater", request.getHeader("User-Agent"))
            assertFalse(request.path.orEmpty().contains("/rest/"))
            assertFalse(request.toString().contains(credentials.password))
            assertFalse(request.headers.toString().contains(credentials.connection.username))
        }
    }

    private fun newManager(): UpdateManager {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        extraScopes.add(scope)
        return UpdateManager(app, scope, client())
    }

    private fun client() = UpdateClient(UpdateTestConfig(server.url("/")))
    private fun configure(versionCode: Int, payload: ByteArray) {
        count = versionCode
        bytes = payload
        hash = digest(payload)
    }

    private fun publishedRelease() = buildJsonObject {
        put("tag_name", "v0.1.$count"); put("draft", false); put("prerelease", false)
        put("published_at", "2026-01-01T00:00:00Z"); put("body", "Isolated integration fixture")
        put("assets", buildJsonArray {
            for (name in listOf("release.json", "Music-0.1.$count.apk")) add(buildJsonObject {
                put("name", name)
                put("browser_download_url", "https://github.com$ASSET_PATH/v0.1.$count/$name")
                put("size", if (name.endsWith(".apk")) bytes.size.toLong() else manifest().toByteArray().size.toLong())
            })
        })
    }.toString()

    private fun manifest() = buildJsonObject {
        put("count", count); put("version", "0.1.$count"); put("tag", "v0.1.$count")
        put("sha", "a".repeat(40)); put("apk", "Music-0.1.$count.apk")
        put("bytes", bytes.size.toLong()); put("sha256", hash)
    }.toString()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private suspend fun onMain(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun until(predicate: () -> Boolean) = withTimeout(60_000) {
        while (!predicate()) delay(20)
        // Let Main finish the state-emitting coroutine and propagate collectors before asserting.
        onMain { }
    }
    private fun digest(value: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(value))
    private fun hex(value: ByteArray) = value.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private val snapshot = PlaybackSnapshot(playing = true, positionMs = 34_567L, durationMs = 180_000L)
    private companion object {
        const val API_PATH = "/repos/everyonesoftware/music/releases/latest"
        const val ASSET_PATH = "/everyonesoftware/music/releases/download"
    }
}
