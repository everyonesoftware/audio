package com.everyonesoftware.audio.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
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
import com.everyonesoftware.audio.data.AlbumContents
import com.everyonesoftware.audio.data.Credentials
import com.everyonesoftware.audio.data.NavidromeClient
import com.everyonesoftware.audio.data.NavidromeException
import com.everyonesoftware.audio.playback.PlaybackService
import com.everyonesoftware.audio.playback.CarLibrary
import com.everyonesoftware.audio.playback.CarMediaIds
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioViewModel @JvmOverloads constructor(application: Application, private val graph: AudioGraph = (application as AudioApplication).graph, private val connectController: Boolean = true) : AndroidViewModel(application), AudioUiActions {
    private val current = MutableStateFlow(AudioUiState())
    val state = current.asStateFlow()
    private var operation: Job? = null
    private var albumsOperation: Job? = null
    private var albumDetailOperation: Job? = null
    private val initialization: Job
    private var generation = 0L
    private var firstLibrarySetup = false
    private var installOperation: Job? = null
    private val installRequests = Channel<Intent>(Channel.RENDEZVOUS)
    val installIntents = installRequests.receiveAsFlow()
    private val controllerFuture: ListenableFuture<MediaController>? = if (connectController) {
        MediaController.Builder(application, SessionToken(application, ComponentName(application, PlaybackService::class.java))).buildAsync()
    } else null

    init {
        viewModelScope.launch {
            graph.updates.state.collect { updates -> current.update { it.copy(updates = updates) } }
        }
        initialization = viewModelScope.launch {
            try {
                graph.bootstrap()
                restoreConnection()
                val session = graph.session.value
                current.update { it.copy(booting = false, session = session, positionMs = session?.positionMs ?: 0,
                    screen = when { session != null -> Screen.PLAYER; graph.credentials.value != null -> Screen.PLAYLISTS; else -> Screen.WELCOME }) }
                if (graph.credentials.value != null) loadAlbums()
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
        albumsOperation?.cancel()
        albumDetailOperation?.cancel()
        val connection = graph.credentials.value?.connection
        val playlists = connection?.let { graph.store.cachedPlaylists(it.id) }.orEmpty()
        val selected = connection?.let { graph.store.selectedLibraries(it.id) }.orEmpty()
        val connections = graph.store.connections()
        current.update { it.copy(connection = connection, connections = connections, playlists = playlists, albums = emptyList(), albumsError = null, selectedAlbum = null, albumSongs = emptyList(), albumError = null,
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
        if (screen == Screen.UPDATES && current.value.screen != Screen.UPDATES) {
            current.update { it.copy(updatesReturnScreen = it.screen) }
        }
        if (current.value.screen == Screen.CONNECT && screen != Screen.CONNECT) {
            ++generation; operation?.cancel()
            current.update { it.copy(loading = false) }
        }
        when (screen) {
            Screen.PLAYLISTS -> loadPlaylists()
            Screen.SEARCH -> {
                current.update { it.copy(screen = screen, error = null) }
                loadAlbums()
            }
            Screen.ALBUM -> current.update { it.copy(screen = screen, error = null) }
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
        loadAlbums()
        viewModelScope.launch {
            initialization.join()
            graph.playlists.refresh()
        }
    }

    override fun loadAlbums() {
        val credentials = graph.credentials.value ?: return
        if (current.value.albumsRefreshing && current.value.connection?.id == credentials.connection.id) return
        albumsOperation?.cancel()
        current.update { it.copy(albumsRefreshing = true, albumsError = null) }
        albumsOperation = viewModelScope.launch {
            try {
                val albums = graph.clientFor(credentials).albums()
                if (graph.credentials.value == credentials) current.update { it.copy(albums = albums, albumsError = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (graph.credentials.value == credentials) current.update { it.copy(albumsError = safeMessage(error)) }
            } finally {
                if (graph.credentials.value == credentials) current.update { it.copy(albumsRefreshing = false) }
            }
        }
    }

    override fun openAlbum(id: String) {
        val credentials = graph.credentials.value ?: return
        val album = current.value.albums.firstOrNull { it.id == id } ?: return
        albumDetailOperation?.cancel()
        current.update { it.copy(screen = Screen.ALBUM, selectedAlbum = album, albumSongs = emptyList(), albumLoading = true, albumError = null) }
        albumDetailOperation = viewModelScope.launch {
            try {
                val result = graph.clientFor(credentials).album(id)
                if (graph.credentials.value == credentials && current.value.selectedAlbum?.id == id) {
                    current.update { it.copy(selectedAlbum = result.album, albumSongs = result.songs, albumLoading = false) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (graph.credentials.value == credentials && current.value.selectedAlbum?.id == id) {
                    current.update { it.copy(albumLoading = false, albumError = safeMessage(error)) }
                }
            }
        }
    }

    override fun playAlbum(id: String) {
        if (current.value.loading) return
        work {
            val credentials = requireNotNull(graph.credentials.value) { "Connect first." }
            val result = if (current.value.selectedAlbum?.id == id && current.value.albumSongs.isNotEmpty())
                AlbumContents(requireNotNull(current.value.selectedAlbum), current.value.albumSongs)
            else graph.clientFor(credentials).album(id)
            val album = result.album
            val previous = graph.session.value
            val session = SessionRules.start(credentials.connection.id, album.id, album.name, result.songs, sourceType = "album", shuffle = false)
            val controller = controller()
            graph.setSession(session, credentials, previous)
            controller.play()
            current.update { it.copy(screen = Screen.PLAYER, selectedAlbum = album, albumSongs = result.songs, ratingError = null) }
        }
    }

    fun onForeground() {
        if (connectController) graph.updates.onForeground()
        if (!current.value.booting && current.value.screen == Screen.PLAYLISTS) loadPlaylists()
    }

    fun onBackground() { installOperation?.cancel() }

    override fun checkForUpdates() { graph.updates.coordinator.check() }
    override fun setAutomaticUpdates(enabled: Boolean) { graph.updates.coordinator.setAutomaticChecks(enabled) }
    override fun downloadUpdate() { graph.updates.coordinator.download() }
    override fun cancelUpdateDownload() { graph.updates.coordinator.cancelDownload() }
    override fun pauseForUpdate() = playerCommand { it.pause() }

    /** A separate, freshly confirmed action; no check or download ever sends a player command. */
    override fun installUpdate() {
        if (installOperation?.isActive == true) return
        installOperation = viewModelScope.launch {
            val updater = graph.updates.coordinator
            var handedOff = false
            try {
                if (current.value.session != null) {
                    val player = controller()
                    if (player.playWhenReady || player.isPlaying) return@launch
                }
                if (!graph.updates.canInstall()) {
                    updater.permissionRequired(true)
                    installRequests.send(graph.updates.permissionIntent())
                    return@launch
                }
                val (file, release) = updater.beginInstall() ?: return@launch
                val intent = graph.updates.verifiedInstallIntent(file, release)
                // Recheck after IO: a car/controller may have resumed or changed the queue.
                val session = graph.session.value
                if (session != null) {
                    val player = controller()
                    if (player.playWhenReady || player.isPlaying) { updater.installFailed(); return@launch }
                    if (player.mediaItemCount > 0) {
                        val index = player.currentMediaItemIndex
                        check(index in session.queue.indices && player.currentMediaItem?.mediaId ==
                            CarMediaIds.queued(session.connectionId, session.queueId, index))
                        graph.updateProgress(index, player.currentPosition, player.repeatMode,
                            session.connectionId, session.queueId, session.queue.map { it.id })
                        check(graph.session.value?.queueId == session.queueId &&
                            graph.session.value?.connectionId == session.connectionId)
                    }
                }
                installRequests.send(intent)
                handedOff = true
            } catch (cancelled: CancellationException) {
                if (!handedOff) updater.installerReturned()
                throw cancelled
            } catch (_: Exception) { updater.installFailed() }
        }
    }

    /** Main-thread check immediately before the activity hands control to Android. */
    fun allowInstallerLaunch(intent: Intent): Boolean {
        if (intent.type != "application/vnd.android.package-archive") return true
        val player = controllerFuture?.takeIf { it.isDone && !it.isCancelled }?.let {
            try { it.get() } catch (_: Exception) { null }
        }
        val allowed = !current.value.playing && (current.value.session == null ||
            (player != null && !player.playWhenReady && !player.isPlaying))
        if (!allowed) graph.updates.coordinator.installFailed()
        return allowed
    }

    fun installerLaunchFailed() { graph.updates.coordinator.installFailed() }

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
            catch (_: Exception) { current.update { it.copy(error = "The player could not connect. Reopen Music and retry.") } }
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
