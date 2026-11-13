package com.everyonesoftware.audio.ui

import android.app.Application
import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.everyonesoftware.audio.AudioApplication
import com.everyonesoftware.audio.AudioGraph
import com.everyonesoftware.audio.BuildConfig
import com.everyonesoftware.audio.data.Connection
import com.everyonesoftware.audio.data.Credentials
import com.everyonesoftware.audio.data.NavidromeClient
import com.everyonesoftware.audio.data.NavidromeException
import com.everyonesoftware.audio.playback.PlaybackService
import com.everyonesoftware.audio.playback.CarLibrary
import com.everyonesoftware.audio.playback.SessionRules
import com.google.common.util.concurrent.ListenableFuture
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioViewModel @JvmOverloads constructor(application: Application, private val graph: AudioGraph = (application as AudioApplication).graph, private val connectController: Boolean = true) : AndroidViewModel(application), AudioUiActions {
    private val current = MutableStateFlow(AudioUiState())
    val state = current.asStateFlow()
    private var operation: Job? = null
    private val initialization: Job
    private var generation = 0L
    private var firstLibrarySetup = false
    private val controllerFuture: ListenableFuture<MediaController>? = if (connectController) {
        MediaController.Builder(application, SessionToken(application, ComponentName(application, PlaybackService::class.java))).buildAsync()
    } else null

    init {
        initialization = viewModelScope.launch {
            try {
                graph.bootstrap()
                restoreConnection()
                val session = graph.session.value
                current.update { it.copy(booting = false, session = session, positionMs = session?.positionMs ?: 0,
                    screen = when { session != null -> Screen.PLAYER; graph.credentials.value != null -> Screen.PLAYLISTS; else -> Screen.WELCOME }) }
                // Startup refresh must not cancel an explicit request received while restoring.
                if (current.value.screen == Screen.PLAYLISTS && generation == 0L) loadPlaylists()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { current.update { it.copy(booting = false, error = safeMessage(error)) } }
        }
        viewModelScope.launch {
            graph.session.collect { session -> current.update { it.copy(session = session, positionMs = session?.positionMs ?: 0) } }
        }
        viewModelScope.launch {
            graph.playlists.state.collect { catalog ->
                current.update {
                    if (it.connection?.id != catalog.connectionId) it else it.copy(
                        playlists = catalog.playlists.sortedByDescending { playlist -> playlist.id == graph.session.value?.playlistId },
                        playlistsRefreshing = catalog.refreshing, stalePlaylistIds = catalog.staleIds, playlistError = catalog.error)
                }
            }
        }
        viewModelScope.launch {
            graph.playback.collect { player -> current.update { it.copy(playing = player.playing,
                positionMs = player.positionMs, durationMs = player.durationMs,
                error = player.error ?: it.error) } }
        }
    }

    private suspend fun restoreConnection() {
        val connection = graph.credentials.value?.connection
        val playlists = connection?.let { graph.store.cachedPlaylists(it.id) }.orEmpty()
        val selected = connection?.let { graph.store.selectedLibraries(it.id) }.orEmpty()
        val connections = graph.store.connections()
        current.update { it.copy(connection = connection, connections = connections, playlists = playlists,
            libraries = emptyList(), selectedLibraries = selected,
            playlistsRefreshing = graph.playlists.state.value.refreshing,
            stalePlaylistIds = graph.playlists.state.value.staleIds, playlistError = graph.playlists.state.value.error) }
    }

    private fun work(block: suspend () -> Unit) {
        val ticket = ++generation
        operation?.cancel()
        current.update { it.copy(loading = true, error = null) }
        operation = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (ticket == generation) current.update { it.copy(error = safeMessage(error)) } }
            finally { if (ticket == generation) current.update { it.copy(loading = false) } }
        }
    }

    private fun safeMessage(error: Exception): String = when (error) {
        is NavidromeException, is IllegalArgumentException -> error.message ?: "Check your music server and retry."
        else -> "Unable to complete that action. Check your connection and retry."
    }

    override fun connect(name: String, url: String, username: String, password: String) {
        if (current.value.loading) return
        work {
            require(username.isNotBlank() && password.isNotEmpty()) { "Enter your username and password." }
            val base = NavidromeClient.validateBaseUrl(url, allowLocalHttp = BuildConfig.DEBUG)
            val existing = graph.store.connections().firstOrNull { it.baseUrl == base && it.username == username }
            val connection = Connection(existing?.id ?: UUID.randomUUID().toString(), name.trim().ifBlank { "Navidrome" }, base, username)
            val credentials = Credentials(connection, password)
            val client = NavidromeClient(credentials, allowLocalHttp = BuildConfig.DEBUG)
            client.ping()
            val libraries = client.libraries()
            graph.store.saveConnection(credentials)
            controllerFuture?.takeIf { it.isDone && !it.isCancelled }?.get()?.pause()
            graph.activate(connection.id)
            restoreConnection()
            firstLibrarySetup = true
            current.update { it.copy(screen = Screen.LIBRARIES, libraries = libraries, selectedLibraries = libraries.map { library -> library.id }.toSet()) }
        }
    }

    override fun showScreen(screen: Screen) {
        if (current.value.screen == Screen.CONNECT && screen != Screen.CONNECT) {
            ++generation; operation?.cancel()
            current.update { it.copy(loading = false) }
        }
        when (screen) {
            Screen.PLAYLISTS -> loadPlaylists()
            Screen.LIBRARIES -> {
                firstLibrarySetup = false
                current.update { it.copy(screen = screen, error = null) }
                work {
                    val id = graph.credentials.value?.connection?.id ?: error("Connect first.")
                    val libraries = graph.client().libraries()
                    val selected = graph.store.selectedLibraries(id).intersect(libraries.map { it.id }.toSet())
                    current.update { it.copy(libraries = libraries, selectedLibraries = selected) }
                }
            }
            Screen.PLAYER, Screen.QUEUE -> {
                if (graph.session.value == null) loadPlaylists() else current.update { it.copy(screen = screen, error = null) }
            }
            else -> current.update { it.copy(screen = screen, error = null) }
        }
    }

    override fun setLibrary(id: String, selected: Boolean) {
        if (current.value.loading || current.value.libraries.none { it.id == id }) return
        current.update { it.copy(selectedLibraries = if (selected) it.selectedLibraries + id else it.selectedLibraries - id) }
    }

    override fun saveLibraries() {
        val id = graph.credentials.value?.connection?.id ?: return
        val selected = current.value.selectedLibraries.toSet()
        work {
            graph.store.setSelectedLibraries(id, selected)
            current.update { it.copy(screen = if (firstLibrarySetup) Screen.READY else Screen.SETTINGS) }
        }
    }

    override fun loadPlaylists() {
        current.update { it.copy(screen = Screen.PLAYLISTS) }
        viewModelScope.launch {
            initialization.join()
            graph.playlists.refresh()
        }
    }

    fun onForeground() {
        if (!current.value.booting && current.value.screen == Screen.PLAYLISTS) loadPlaylists()
    }

    override fun startPlaylist(id: String) {
        if (current.value.loading) return
        work {
            val credentials = requireNotNull(graph.credentials.value) { "Connect first." }
            val connection = credentials.connection
            val previous = graph.session.value
            val playlist = current.value.playlists.firstOrNull { it.id == id } ?: error("Refresh playlists and retry.")
            val songs = graph.playlists.fetchContents(credentials, id).songs
            val session = SessionRules.start(connection.id, playlist.id, playlist.name, songs)
            val controller = controller()
            graph.setSession(session, credentials, previous)
            controller.play()
            current.update { it.copy(screen = Screen.PLAYER, ratingError = null) }
        }
    }

    fun playFromSearch(query: String) {
        work {
            initialization.join()
            graph.bootstrap()
            val selected = CarLibrary(graph).resolveSearch(query)
            val controller = controller()
            graph.setSession(selected.session, selected.credentials, selected.previous)
            controller.play()
            current.update { it.copy(screen = Screen.PLAYER, ratingError = null) }
        }
    }

    private suspend fun controller(): MediaController {
        val future = controllerFuture ?: error("The player is unavailable.")
        return suspendCancellableCoroutine { continuation ->
            future.addListener({
                if (continuation.isActive) {
                    try { continuation.resume(future.get()) }
                    catch (error: Exception) { continuation.resumeWithException(error) }
                }
            }, ContextCompat.getMainExecutor(getApplication()))
        }
    }

    private fun playerCommand(action: (MediaController) -> Unit) {
        viewModelScope.launch {
            try { action(controller()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { current.update { it.copy(error = "The player could not connect. Reopen Audio and retry.") } }
        }
    }
    override fun togglePlay() = playerCommand { if (it.playWhenReady) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } }
    override fun next() = playerCommand { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    override fun previous() = playerCommand { it.seekToPrevious() }
    override fun seekTo(positionMs: Long) = playerCommand { it.seekTo(positionMs.coerceAtLeast(0)) }
    override fun toggleRepeat() = playerCommand { it.repeatMode = (it.repeatMode + 1) % 3 }

    override fun toggleShuffle() {
        if (current.value.loading) return
        work {
            val session = graph.session.value ?: return@work
            val next = if (session.shuffle) session.copy(shuffle = false) else {
                val remaining = session.queue.filterIndexed { index, _ -> index != session.index }.shuffled()
                session.copy(queue = listOf(session.queue[session.index]) + remaining, index = 0, shuffle = true)
            }
            graph.setSession(next)
        }
    }

    override fun rateSong(rating: Int) {
        val session = graph.session.value ?: return
        val song = session.queue.getOrNull(session.index) ?: return
        if (current.value.ratingBusy || rating !in 0..5) return
        val credentials = graph.credentials.value ?: return
        val client = graph.clientFor(credentials)
        current.update { it.copy(ratingBusy = true, ratingError = null) }
        viewModelScope.launch {
            try {
                val confirmed = client.rateSong(song.id, rating)
                graph.applyRating(session.connectionId, song.id, confirmed)
                graph.playlists.ratingChanged(credentials)
                if (isRatingTarget(session.connectionId, song.id) && confirmed != rating) {
                    current.update { it.copy(ratingError = "The server reports a different rating. Retry if you want to change it.") }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (isRatingTarget(session.connectionId, song.id)) current.update { it.copy(ratingError = safeMessage(error)) } }
            finally { current.update { it.copy(ratingBusy = false) } }
        }
    }

    private fun isRatingTarget(connectionId: String, songId: String): Boolean = graph.session.value?.let {
        it.connectionId == connectionId && it.queue.getOrNull(it.index)?.id == songId
    } == true

    fun artworkUrl(id: String?): String? = try { id?.let { graph.client().coverArtUri(it) } } catch (_: Exception) { null }
    override fun clearError() { current.update { it.copy(error = null, ratingError = null) } }

    override fun switchConnection(id: String) {
        if (id == graph.credentials.value?.connection?.id || current.value.loading) return
        work {
            if (connectController) {
                val controller = controller()
                controller.pause()
                graph.credentials.value?.connection?.id?.let {
                    if (controller.mediaItemCount > 0) graph.updateProgress(controller.currentMediaItemIndex, controller.currentPosition, controller.repeatMode, it)
                }
            }
            graph.activate(id)
            restoreConnection()
            val session = graph.session.value
            current.update { it.copy(session = session, positionMs = session?.positionMs ?: 0, screen = if (session != null) Screen.PLAYER else Screen.PLAYLISTS) }
            if (session == null) graph.playlists.refresh()
        }
    }

    override fun onCleared() {
        controllerFuture?.let(MediaController::releaseFuture)
        super.onCleared()
    }
}
