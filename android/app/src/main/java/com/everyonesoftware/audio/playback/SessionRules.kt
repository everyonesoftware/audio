package com.everyonesoftware.audio.playback

import com.everyonesoftware.audio.data.ListeningSession
import com.everyonesoftware.audio.data.Song
import kotlin.random.Random

/** Queue transitions are kept independent from Android so lifecycle rules are testable. */
object SessionRules {
    fun start(connectionId: String, playlistId: String, playlistName: String, songs: List<Song>, random: Random = Random.Default, sourceType: String = "playlist", shuffle: Boolean = true): ListeningSession {
        require(songs.isNotEmpty()) { "This collection has no playable songs." }
        require(connectionId.isNotBlank() && playlistId.isNotBlank()) { "A collection and connection are required." }
        require(sourceType in setOf("playlist", "album")) { "Unsupported playback source." }
        return ListeningSession(connectionId, playlistId, playlistName, if (shuffle) songs.shuffled(random) else songs, shuffle = shuffle,
            queueId = java.util.UUID.randomUUID().toString(), sourceType = sourceType)
    }
    fun restore(session: ListeningSession): ListeningSession {
        require(session.queue.isNotEmpty()) { "The saved queue is empty." }
        return session.copy(
            index = session.index.coerceIn(session.queue.indices),
            positionMs = session.positionMs.coerceAtLeast(0),
            repeatMode = session.repeatMode.coerceIn(0, 2),
        )
    }
    fun progress(session: ListeningSession, index: Int, positionMs: Long, repeatMode: Int): ListeningSession =
        restore(session.copy(index = index, positionMs = positionMs, repeatMode = repeatMode))
}
