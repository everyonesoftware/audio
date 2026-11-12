package com.everyonesoftware.audio.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import com.everyonesoftware.audio.AudioApplication
import com.everyonesoftware.audio.AudioGraph
import com.everyonesoftware.audio.BuildConfig
import com.everyonesoftware.audio.PlaybackSnapshot
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

    private data class QueueKey(val connectionId: String, val playlistId: String, val ids: List<String>, val queueId: String)
    private data class QueueBinding(val key: QueueKey, val credentials: Credentials)
    private data class Progress(val key: QueueKey, val index: Int, val positionMs: Long, val repeatMode: Int)

    private fun ListeningSession.key() = QueueKey(connectionId, playlistId, queue.map { it.id }, queueId)
    private fun trusted(controller: MediaSession.ControllerInfo) =
        (controller.packageName == packageName && controller.uid == applicationInfo.uid) || controller.isTrusted

    override fun onCreate() {
        super.onCreate()
        graph = (application as AudioApplication).graph
        player = ExoPlayer.Builder(this).build().apply {
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
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                if (!trusted(controller)) return MediaSession.ConnectionResult.reject()
                // Controllers transport playback commands, not arbitrary URLs or replacement queues.
                val commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                    .remove(Player.COMMAND_SET_MEDIA_ITEM)
                    .remove(Player.COMMAND_SET_SHUFFLE_MODE)
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        librarySession?.takeIf { trusted(controllerInfo) }

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
            val client = NavidromeClient(credentials, allowLocalHttp = BuildConfig.DEBUG)
            val sources = DefaultMediaSourceFactory(OkHttpDataSource.Factory(streamClient(next, client)))
            val mediaSources = saved.queue.map { song ->
                sources.createMediaSource(MediaItem.Builder()
                    .setMediaId(song.id).setUri(client.streamUri(song.id))
                    .setMediaMetadata(MediaMetadata.Builder()
                        .setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).setIsPlayable(true)
                        .setIsBrowsable(false).build()).build())
            }
            player.pause()
            player.stop()
            binding = next
            storageError = null
            player.shuffleModeEnabled = false
            player.repeatMode = saved.repeatMode.coerceIn(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL)
            player.setMediaSources(mediaSources, saved.index.coerceIn(saved.queue.indices), saved.positionMs.coerceAtLeast(0))
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
