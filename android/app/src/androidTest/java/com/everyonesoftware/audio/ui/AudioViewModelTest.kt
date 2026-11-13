package com.everyonesoftware.audio.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.everyonesoftware.audio.AudioGraph
import com.everyonesoftware.audio.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioViewModelTest {
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun until(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(12)
        while (!predicate()) { check(System.nanoTime() < deadline) { "Timed out waiting for view-model state" }; Thread.sleep(20) }
    }
    private fun ok(content: String = "") = MockResponse().setHeader("Content-Type", "application/json").setBody("{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\"$content}}")

    @Test fun connectionChecksServerThenSavesCredentialsAndSelectedLibraries() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val graph = AudioGraph(app)
        val server = MockWebServer()
        server.enqueue(ok())
        server.enqueue(ok(",\"musicFolders\":{\"musicFolder\":[{\"id\":\"1\",\"name\":\"Music\"},{\"id\":\"2\",\"name\":\"Archive\"}]}"))
        server.start()
        val baseUrl = server.url("/").toString()
        val owner = ViewModelStore()
        lateinit var model: AudioViewModel
        try {
            onMain { model = AudioViewModel(app, graph, false); owner.put("fixture", model) }
            until { !model.state.value.booting }
            onMain { model.connect("Fixture", baseUrl, "vm-fixture-user", "test-only-password") }
            until { model.state.value.screen == Screen.LIBRARIES || model.state.value.error != null }
            assertNull(model.state.value.error)
            assertEquals(setOf("1", "2"), model.state.value.selectedLibraries)
            assertEquals("vm-fixture-user", graph.credentials.value?.connection?.username)
            onMain { model.setLibrary("2", false); model.saveLibraries() }
            until { model.state.value.screen == Screen.READY || model.state.value.error != null }
            runBlocking { assertEquals(setOf("1"), graph.store.selectedLibraries(graph.credentials.value!!.connection.id)) }
        } finally {
            onMain { owner.clear() }
            runBlocking { graph.store.connections().filter { it.username == "vm-fixture-user" }.forEach { graph.store.removeConnection(it.id) } }
            server.shutdown()
        }
    }

    @Test fun rejectedCredentialsNeverBecomeASavedConnection() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val graph = AudioGraph(app)
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()
        val baseUrl = server.url("/").toString()
        val owner = ViewModelStore()
        lateinit var model: AudioViewModel
        try {
            onMain { model = AudioViewModel(app, graph, false); owner.put("fixture", model) }
            until { !model.state.value.booting }
            onMain { model.connect("Rejected", baseUrl, "vm-rejected-user", "test-only-password") }
            until { model.state.value.error != null }
            assertFalse(model.state.value.loading)
            assertEquals(1, server.requestCount)
            runBlocking { assertFalse(graph.store.connections().any { it.username == "vm-rejected-user" }) }
            assertFalse(model.state.value.error!!.contains("test-only-password"))
        } finally { onMain { owner.clear() }; server.shutdown() }
    }

    @Test fun returningListenerLandsInFullPlayerWithExactPersistedQueue() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val graph = AudioGraph(app)
        val id = "vm-returning-fixture"
        val owner = ViewModelStore()
        try {
            graph.store.saveConnection(Credentials(Connection(id, "Fixture", "https://example.invalid/", "vm-returning-user"), "test-only-password"))
            graph.store.selectConnection(id)
            val session = ListeningSession(id, "p1", "Routine", listOf(Song("second", "Second", "Fixture"), Song("first", "First", "Fixture")), 1, 34567, true, 2)
            graph.store.saveSession(session)
            lateinit var model: AudioViewModel
            onMain { model = AudioViewModel(app, graph, false); owner.put("fixture", model) }
            until { !model.state.value.booting }
            assertEquals(Screen.PLAYER, model.state.value.screen)
            assertEquals(session, model.state.value.session)
            assertEquals(34567L, model.state.value.positionMs)
            assertFalse(model.state.value.playing)
        } finally { onMain { owner.clear() }; graph.store.removeConnection(id) }
    }

    @Test fun playlistRefreshUsesContentsNotStaleSummaryAndPreservesQueue() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val graph = AudioGraph(app)
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                request.path!!.contains("getPlaylists.view") -> ok(",\"playlists\":{\"playlist\":[{\"id\":\"smart\",\"name\":\"Rated\",\"songCount\":0}]}")
                request.path!!.contains("getPlaylist.view") -> ok(",\"playlist\":{\"id\":\"smart\",\"name\":\"Rated\",\"entry\":[{\"id\":\"s1\",\"title\":\"One\"},{\"id\":\"s1\",\"title\":\"One again\"}]}")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val id = "vm-playlist-count-fixture"
        val owner = ViewModelStore()
        try {
            graph.store.saveConnection(Credentials(Connection(id, "Fixture", server.url("/").toString(), "vm-count-user"), "test-only-password"))
            graph.store.selectConnection(id)
            val session = ListeningSession(id, "smart", "Rated", listOf(Song("old", "In progress", "Fixture")), positionMs = 1234L)
            graph.store.saveSession(session)
            lateinit var model: AudioViewModel
            onMain { model = AudioViewModel(app, graph, false); owner.put("fixture", model) }
            until { !model.state.value.booting }
            onMain { model.loadPlaylists() }
            until { model.state.value.playlists.singleOrNull()?.songCount == 2 && !model.state.value.playlistsRefreshing }
            assertEquals(session, graph.session.value)
            assertEquals(2, graph.store.cachedPlaylists(id).single().songCount)
        } finally {
            onMain { owner.clear() }
            graph.store.removeConnection(id)
            server.shutdown()
        }
    }
}
