package com.everyonesoftware.audio.playback

import com.everyonesoftware.audio.data.ListeningSession
import com.everyonesoftware.audio.data.Song
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class SessionRulesTest {
    private val songs = (1..8).map { Song("song-$it", "Track $it", "Test artist") }

    @Test fun `explicit restart has a new queue identity even for a single song`() {
        val first = SessionRules.start("server-a", "playlist-a", "One song", songs.take(1))
        val restarted = SessionRules.start("server-a", "playlist-a", "One song", songs.take(1))
        assertNotEquals(first.queueId, restarted.queueId)
        assertEquals(first.queue, restarted.queue)
        assertEquals(first.queueId, SessionRules.restore(first).queueId)
    }

    @Test fun `shuffle preserves every playlist occurrence including repeated songs`() {
        val original = songs + songs.first()
        val session = SessionRules.start("server-a", "playlist-a", "Test playlist", original, Random(7))
        assertEquals(original.groupingBy { it.id }.eachCount(), session.queue.groupingBy { it.id }.eachCount())
        assertNotEquals(original, session.queue)
        assertEquals("server-a", session.connectionId)
        assertEquals(0, session.index)
        assertEquals(0L, session.positionMs)
    }

    @Test fun `restoring a paused session preserves exact shuffle order and position`() {
        val original = ListeningSession("server-a", "playlist-a", "Test playlist", songs.reversed(), 3, 42137L, true, 2)
        assertEquals(original, SessionRules.restore(original))
    }

    @Test fun `empty playlist cannot replace a listening session`() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionRules.start("server-a", "empty", "Empty playlist", emptyList(), Random(1))
        }
    }

    @Test fun `progress updates do not change queue or connection`() {
        val original = ListeningSession("server-a", "playlist-a", "Test playlist", songs, 0, 0L)
        val next = SessionRules.progress(original, 4, 12345L, 1)
        assertEquals(original.queue, next.queue)
        assertEquals(original.connectionId, next.connectionId)
        assertEquals(4, next.index)
        assertEquals(12345L, next.positionMs)
        assertEquals(1, next.repeatMode)
    }

    @Test fun `invalid saved index is clamped and negative position cannot persist`() {
        val original = ListeningSession("server-a", "playlist-a", "Test playlist", songs, 500, -5L)
        val restored = SessionRules.restore(original)
        assertEquals(songs.lastIndex, restored.index)
        assertEquals(0L, restored.positionMs)
    }
}
