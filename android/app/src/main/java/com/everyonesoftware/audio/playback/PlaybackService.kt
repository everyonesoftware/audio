package com.everyonesoftware.audio.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.session.LibraryResult
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaConstants
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import com.everyonesoftware.audio.AudioApplication
import com.everyonesoftware.audio.AudioGraph
import com.everyonesoftware.audio.BuildConfig
import com.everyonesoftware.audio.PlaybackSnapshot
import com.everyonesoftware.audio.R
import com.everyonesoftware.audio.data.Credentials
import com.everyonesoftware.audio.data.ListeningSession
import com.everyonesoftware.audio.data.NavidromeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The service, never an activity or ViewModel, owns the player and its audio focus. */
@UnstableApi
class PlaybackService : MediaLibraryService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Final progress writes may finish after the service's observers and player have stopped.
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var graph: AudioGraph
    private lateinit var player: ExoPlayer
    private var librarySession: MediaLibrarySession? = null
    private var binding: QueueBinding? = null
    private var replacingQueue = false
    private var storageError: String? = null
    private lateinit var carLibrary: CarLibrary
    private var selectionGeneration = 0L

    private data class QueueKey(val connectionId: String, val playlistId: String, val ids: List<String>, val queueId: String)
    private data class QueueBinding(val key: QueueKey, val credentials: Credentials)
    private data class Progress(val key: QueueKey, val index: Int, val positionMs: Long, val repeatMode: Int)

    private fun ListeningSession.key() = QueueKey(connectionId, playlistId, queue.map { it.id }, queueId)
    private fun trusted(controller: MediaSession.ControllerInfo) =
        (controller.packageName == packageName && controller.uid == applicationInfo.uid) || controller.isTrusted

    override fun onCreate() {
        super.onCreate()
        graph = (application as AudioApplication).graph
        carLibrary = CarLibrary(graph)
        // Owner tags never cross the session wire. Sources capture queue-specific credentials,
        // including when Media3 applies the asynchronous onSetMediaItems result itself.
        val defaultSources = DefaultMediaSourceFactory(this)
        val sources = object : MediaSource.Factory by defaultSources {
            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                val owner = mediaItem.localConfiguration?.tag as? QueueBinding
                    ?: throw IllegalArgumentException("Choose music from Audio.")
                val client = NavidromeClient(owner.credentials, allowLocalHttp = BuildConfig.DEBUG)
                return DefaultMediaSourceFactory(OkHttpDataSource.Factory(streamClient(owner, client)))
                    .createMediaSource(mediaItem)
            }
        }
        player = ExoPlayer.Builder(this).setMediaSourceFactory(sources).build().apply {
            setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            // The stored queue is already shuffled; a second shuffle would change its order.
            shuffleModeEnabled = false
            addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (replacingQueue) return
                    publishSnapshot()
                    if (events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED,
                            Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
                            Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                        persistProgress()
                    }
                }
            })
        }
        val callback = object : MediaLibrarySession.Callback {
            override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
                Futures.immediateFuture(LibraryResult.ofItem(carLibrary.root(), params))

            override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
                libraryFuture { LibraryResult.ofItemList(CarLibrary.page(carLibrary.children(parentId), page, pageSize), params) }

            override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
                libraryFuture { LibraryResult.ofItem(carLibrary.item(mediaId), null) }

            override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> =
                libraryFuture {
                    session.notifySearchResultChanged(browser, query, carLibrary.search(query).size, params)
                    LibraryResult.ofVoid()
                }

            override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
                libraryFuture { LibraryResult.ofItemList(CarLibrary.page(carLibrary.search(query), page, pageSize), params) }

            override fun onSetMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val generation = ++selectionGeneration
                return future {
                    require(mediaItems.size == 1 && mediaItems.single().localConfiguration == null) { "Choose music from Audio." }
                    val request = mediaItems.single()
                    require(request.requestMetadata.mediaUri == null) { "Choose music from Audio." }
                    val saved = selectMedia(request.mediaId, generation, request.requestMetadata.searchQuery)
                    val owner = requireNotNull(binding)
                    MediaSession.MediaItemsWithStartPosition(playbackItems(saved, owner), saved.index, saved.positionMs)
                }
            }

            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (customCommand.customAction != CarLibrary.SHUFFLE_PLAYLIST) return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                val generation = ++selectionGeneration
                return future {
                    try {
                        val id = requireNotNull(CarMediaIds.parse(args.getString(MediaConstants.EXTRA_KEY_MEDIA_ID).orEmpty()))
                        require(id.kind == "playlist")
                        selectMedia(CarMediaIds.shuffle(id.connection, id.parent), generation)
                        player.play()
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { SessionResult(SessionError.ERROR_BAD_VALUE) }
                }
            }

            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                if (!trusted(controller)) return MediaSession.ConnectionResult.reject()
                // Set goes through our ID resolver. Add/remove/reorder and arbitrary URLs remain
                // prohibited; controllers never own the queue or choose its stream references.
                val commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                    .remove(Player.COMMAND_SET_SHUFFLE_MODE)
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                        .add(SessionCommand(CarLibrary.SHUFFLE_PLAYLIST, Bundle.EMPTY)).build())
                    .setAvailablePlayerCommands(commands).build()
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int {
                // setSession() can emit immediately before controller.play(). Reconcile synchronously
                // rather than letting the collector prepare a paused queue after the play command.
                reconcileQueue()
                return SessionResult.RESULT_SUCCESS
            }
        }
        val builder = MediaLibrarySession.Builder(this, player, callback)
            .setCommandButtonsForMediaItems(listOf(CommandButton.Builder(CommandButton.ICON_SHUFFLE_ON)
                .setDisplayName("Shuffle and play")
                .setIconUri(Uri.parse("android.resource://$packageName/${R.drawable.ic_shuffle}"))
                .setSessionCommand(SessionCommand(CarLibrary.SHUFFLE_PLAYLIST, Bundle.EMPTY)).build()))
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            builder.setSessionActivity(PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        librarySession = builder.build()
        serviceScope.launch {
            try {
                graph.bootstrap()
                combine(graph.credentials, graph.session) { _, _ -> Unit }.collect { reconcileQueue() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                clearQueue()
                graph.playback.value = PlaybackSnapshot(error = "Cannot restore playback. Reopen your playlist.")
            }
        }
        serviceScope.launch {
            while (true) {
                delay(1_000)
                publishSnapshot()
                persistProgress()
            }
        }
    }

    // Legacy binding starts with a synthetic unknown-UID controller. onConnect validates the
    // actual caller before library or playback access; don't reject the initial binder lookup.
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = librarySession

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        val job = serviceScope.launch {
            try { future.set(block()) }
            catch (cancelled: CancellationException) { future.cancel(false); throw cancelled }
            catch (_: Exception) { future.setException(IllegalArgumentException("This music is unavailable. Refresh Audio and retry.")) }
        }
        future.addListener({ if (future.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        return future
    }

    private fun <T : Any> libraryFuture(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> = future<LibraryResult<T>> {
        try { return@future block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val code = when {
                graph.credentials.value == null -> SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED
                error is IllegalArgumentException -> SessionError.ERROR_BAD_VALUE
                else -> SessionError.ERROR_IO
            }
            val message = if (graph.credentials.value == null) "Open Audio on your phone and connect to Navidrome."
                else "Cannot load this music. Refresh the list or check the connection on your phone."
            return@future LibraryResult.ofError<T>(SessionError(code, message))
        }
    }

    private fun reconcileQueue() {
        val credentials = graph.credentials.value
        val saved = graph.session.value
        if (credentials == null || saved == null || saved.queue.isEmpty() ||
            saved.connectionId != credentials.connection.id) {
            clearQueue()
            return
        }
        val next = QueueBinding(saved.key(), credentials)
        if (next == binding) return // Progress/rating emissions must never replace or seek the queue.
        val previous = binding
        val keepPlaying = player.playWhenReady && (previous == null || previous.credentials == credentials)
        replacingQueue = true
        try {
            // Each queue captures its own credentials. A late old request cannot borrow the newly
            // selected account's authentication, even when both accounts share the same server.
            val mediaItems = playbackItems(saved, next)
            player.pause()
            player.stop()
            binding = next
            storageError = null
            player.shuffleModeEnabled = false
            player.repeatMode = saved.repeatMode.coerceIn(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL)
            player.setMediaItems(mediaItems, saved.index.coerceIn(saved.queue.indices), saved.positionMs.coerceAtLeast(0))
            player.prepare()
            // Cold restore is paused; an explicit play before a new queue preserves play intent.
            player.playWhenReady = keepPlaying
        } catch (_: Exception) {
            clearQueue()
            graph.playback.value = PlaybackSnapshot(error = "Cannot prepare playback. Reopen your playlist.")
        } finally {
            replacingQueue = false
            publishSnapshot()
        }
        librarySession?.notifyChildrenChanged(CarMediaIds.QUEUE, player.mediaItemCount, null)
    }

    private fun playbackItems(saved: ListeningSession, owner: QueueBinding): List<MediaItem> {
        val client = NavidromeClient(owner.credentials, allowLocalHttp = BuildConfig.DEBUG)
        return saved.queue.mapIndexed { index, song ->
            CarLibrary.songItem(CarMediaIds.queued(saved.connectionId, saved.queueId, index), song).buildUpon()
                .setUri(client.streamUri(song.id)).setTag(owner).build()
        }
    }

    private suspend fun selectMedia(mediaId: String, generation: Long, searchQuery: String? = null): ListeningSession {
        val selected = if (searchQuery != null) carLibrary.resolveSearch(searchQuery) else carLibrary.resolve(mediaId)
        require(generation == selectionGeneration) { "A newer selection is already active." }
        graph.setSession(selected.session, selected.credentials, selected.previous)
        require(generation == selectionGeneration) { "A newer selection is already active." }
        reconcileQueue()
        val saved = requireNotNull(graph.session.value)
        val owner = requireNotNull(binding)
        require(owner.key == saved.key() && owner.credentials == selected.credentials)
        return saved
    }

    private fun streamClient(owner: QueueBinding, client: NavidromeClient): OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).cache(null).retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            // A song can legitimately stream longer than an API request. Reads remain bounded.
            .callTimeout(0, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val unsigned = chain.request()
                try {
                    if (graph.credentials.value != owner.credentials || graph.session.value?.key() != owner.key) {
                        throw IOException("The active music account or queue changed.")
                    }
                    val signed = unsigned.newBuilder()
                        .url(client.authenticatedMediaUri(unsigned.url.toString()))
                        .header("Cache-Control", "no-store").build()
                    val response = chain.proceed(signed)
                    if (!response.isSuccessful) {
                        response.close()
                        throw IOException("The music server could not stream this song.")
                    }
                    // OkHttpDataSource.getUri() reads response.request.url. Restore the unsigned
                    // request so signed URLs cannot escape into session metadata, errors or events.
                    response.newBuilder().request(unsigned).build()
                } catch (_: Exception) {
                    // No credential-bearing transport exception causes may enter ExoPlayer logs.
                    throw IOException("Cannot stream this song. Check your connection and retry.")
                }
            }.build()

    private fun clearQueue() {
        if (binding == null && player.mediaItemCount == 0) return
        val wasReplacing = replacingQueue
        replacingQueue = true
        try {
            player.pause()
            player.stop()
            player.clearMediaItems()
            binding = null
            storageError = null
        } finally {
            replacingQueue = wasReplacing
        }
        graph.playback.value = PlaybackSnapshot()
        librarySession?.notifyChildrenChanged(CarMediaIds.QUEUE, 0, null)
    }

    private fun publishSnapshot() {
        val owner = binding ?: return
        if (graph.credentials.value != owner.credentials || graph.session.value?.key() != owner.key) return
        graph.playback.value = PlaybackSnapshot(
            playing = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0,
            error = if (player.playerError != null) "Cannot play this song. Check your connection and retry." else storageError,
        )
    }

    private fun captureProgress(): Progress? {
        val owner = binding ?: return null
        if (replacingQueue || player.mediaItemCount == 0 || graph.credentials.value != owner.credentials ||
            graph.session.value?.key() != owner.key) return null
        return Progress(owner.key, player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0), player.repeatMode)
    }

    private suspend fun saveProgress(progress: Progress) {
        if (graph.session.value?.key() == progress.key &&
            graph.credentials.value?.connection?.id == progress.key.connectionId) {
            graph.updateProgress(progress.index, progress.positionMs, progress.repeatMode, progress.key.connectionId,
                expectedQueueId = progress.key.queueId, expectedQueueIds = progress.key.ids)
        }
    }

    private fun persistProgress(progress: Progress? = captureProgress()) {
        if (progress == null) return
        persistenceScope.launch {
            try {
                saveProgress(progress)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                storageError = "Playback progress could not be saved."
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistProgress()
        // Active playback belongs to the foreground service, not the activity's task.
        if (!player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        val finalProgress = captureProgress()
        serviceScope.cancel()
        val pendingWrites = persistenceScope.coroutineContext[Job]?.children?.toList().orEmpty()
        // Finish prior writes and a final snapshot without blocking Android's main thread.
        persistenceScope.launch {
            try {
                pendingWrites.joinAll()
                if (finalProgress != null) saveProgress(finalProgress)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Best effort; process death falls back to the persisted one-second ticker.
            } finally {
                persistenceScope.cancel()
            }
        }
        librarySession?.release()
        librarySession = null
        binding?.let { owner ->
            if (graph.session.value?.key() == owner.key && graph.credentials.value == owner.credentials) {
                graph.playback.value = graph.playback.value.copy(playing = false)
            }
        }
        player.release()
        super.onDestroy()
    }
}
