package com.everyonesoftware.audio

import android.content.Context
import com.everyonesoftware.audio.data.AudioStore
import com.everyonesoftware.audio.data.Credentials
import com.everyonesoftware.audio.data.ListeningSession
import com.everyonesoftware.audio.data.NavidromeClient
import com.everyonesoftware.audio.playback.SessionRules
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackSnapshot(val playing: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0, val error: String? = null)

class AudioGraph(context: Context) {
    val store = AudioStore(context)
    private val currentCredentials = MutableStateFlow<Credentials?>(null)
    private val currentSession = MutableStateFlow<ListeningSession?>(null)
    val credentials = currentCredentials.asStateFlow()
    val session = currentSession.asStateFlow()
    val playback = MutableStateFlow(PlaybackSnapshot())
    private val lock = Mutex()
    private var initialized = false
    suspend fun bootstrap() = lock.withLock {
        if (initialized) return@withLock
        val connections = store.connections()
        val selected = store.selectedConnectionId()?.takeIf { id -> connections.any { it.id == id } }
            ?: connections.firstOrNull()?.id
        if (selected != null) activateLocked(selected)
        initialized = true
    }
    suspend fun activate(id: String) = lock.withLock {
        activateLocked(id)
        initialized = true
    }
    private suspend fun activateLocked(id: String) {
        val credentials = store.credentials(id) ?: error("Reconnect this Navidrome account to continue.")
        val restored = store.session(id)?.takeIf { it.queue.isNotEmpty() }?.let(SessionRules::restore)
        store.selectConnection(id)
        currentCredentials.value = credentials
        currentSession.value = restored
        playback.value = PlaybackSnapshot(positionMs = restored?.positionMs ?: 0)
    }
    fun client(): NavidromeClient = NavidromeClient(
        requireNotNull(credentials.value) { "Connect to Navidrome first." },
        allowLocalHttp = BuildConfig.DEBUG,
    )
    suspend fun setSession(value: ListeningSession, expectedCredentials: Credentials? = null, expectedSession: ListeningSession? = null) = lock.withLock {
        require(value.connectionId == credentials.value?.connection?.id) { "The active connection changed. Choose the playlist again." }
        if (expectedCredentials != null) {
            require(currentCredentials.value == expectedCredentials && currentSession.value?.queueId == expectedSession?.queueId &&
                currentSession.value?.queue?.map { it.id } == expectedSession?.queue?.map { it.id }) {
                "The listening session changed. Choose the music again."
            }
        }
        // A queue jump changes position only, not a rating/repeat update committed during resolution.
        val current = currentSession.value
        val merged = if (expectedCredentials != null && current != null && current.queueId == value.queueId)
            current.copy(index = value.index, positionMs = value.positionMs) else value
        val safe = SessionRules.restore(merged)
        store.saveSession(safe)
        currentSession.value = safe
    }
    suspend fun updateProgress(index: Int, positionMs: Long, repeatMode: Int, expectedConnectionId: String? = null, expectedQueueId: String? = null, expectedQueueIds: List<String>? = null) = lock.withLock {
        val current = currentSession.value ?: return@withLock
        if (expectedConnectionId != null && current.connectionId != expectedConnectionId) return@withLock
        if (expectedQueueId != null && current.queueId != expectedQueueId) return@withLock
        if (expectedQueueIds != null && current.queue.map { it.id } != expectedQueueIds) return@withLock
        val next = SessionRules.progress(current, index, positionMs, repeatMode)
        store.saveSession(next)
        currentSession.value = next
    }
    suspend fun applyRating(connectionId: String, songId: String, rating: Int) = lock.withLock {
        require(rating in 0..5)
        val current = if (currentSession.value?.connectionId == connectionId) currentSession.value else store.session(connectionId)
        if (current == null) return@withLock
        val next = current.copy(queue = current.queue.map { if (it.id == songId) it.copy(userRating = rating) else it })
        store.saveSession(next)
        if (currentSession.value?.connectionId == connectionId) currentSession.value = next
    }
}
