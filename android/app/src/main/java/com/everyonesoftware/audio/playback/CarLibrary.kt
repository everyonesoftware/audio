package com.everyonesoftware.audio.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.everyonesoftware.audio.AudioGraph
import com.everyonesoftware.audio.data.Credentials
import com.everyonesoftware.audio.data.ListeningSession
import com.everyonesoftware.audio.data.Playlist
import com.everyonesoftware.audio.data.Song

/** Browsing is read-only. Only resolve() proposes a replacement of the shared listening session. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class CarLibrary(private val graph: AudioGraph) {
    data class Selection(val credentials: Credentials, val previous: ListeningSession?, val session: ListeningSession)

    fun root(): MediaItem = folder(CarMediaIds.ROOT, "Audio")

    suspend fun children(parentId: String): List<MediaItem> {
        graph.bootstrap()
        if (parentId == CarMediaIds.ROOT) return listOf(folder(CarMediaIds.PLAYLISTS, "Playlists"), folder(CarMediaIds.QUEUE, "Queue"))
        val credentials = credentials()
        if (parentId == CarMediaIds.PLAYLISTS) {
            val playlists = playlists(credentials, reconcileCounts = true)
            val recent = graph.session.value?.playlistId
            return playlists.sortedByDescending { it.id == recent }.map { playlist ->
                playlistItem(credentials.connection.id, playlist)
            }
        }
        if (parentId == CarMediaIds.QUEUE) {
            val session = graph.session.value ?: return emptyList()
            require(session.connectionId == credentials.connection.id)
            return session.queue.mapIndexed { index, song ->
                songItem(CarMediaIds.queued(session.connectionId, session.queueId, index), song)
            }
        }
        val id = requireNotNull(CarMediaIds.parse(parentId)) { "Refresh playlists and retry." }
        require(id.kind == "playlist" && id.connection == credentials.connection.id) { "The music account changed. Refresh playlists." }
        val songs = graph.playlists.fetchContents(credentials, id.parent).songs
        checkCurrent(credentials)
        return listOf(playable(CarMediaIds.shuffle(id.connection, id.parent), "Shuffle and play", "Start a new shuffled queue")) +
            songs.mapIndexed { index, song -> songItem(CarMediaIds.track(id.connection, id.parent, index, song.id), song) }
    }

    suspend fun item(mediaId: String): MediaItem {
        graph.bootstrap()
        when (mediaId) {
            CarMediaIds.ROOT -> return root()
            CarMediaIds.PLAYLISTS -> return folder(mediaId, "Playlists")
            CarMediaIds.QUEUE -> return folder(mediaId, "Queue")
        }
        val id = requireNotNull(CarMediaIds.parse(mediaId)) { "Refresh playlists and retry." }
        val credentials = credentials()
        require(id.connection == credentials.connection.id) { "The music account changed. Refresh playlists." }
        return when (id.kind) {
            "playlist" -> {
                val playlist = playlists(credentials).firstOrNull { it.id == id.parent }
                requireNotNull(playlist) { "This playlist is unavailable. Refresh playlists." }
                playlistItem(id.connection, playlist)
            }
            "queued" -> {
                val session = requireNotNull(graph.session.value) { "Choose a playlist first." }
                require(session.queueId == id.parent) { "The queue changed. Reopen Queue." }
                songItem(mediaId, requireNotNull(session.queue.getOrNull(id.index!!)) { "The queue changed. Reopen Queue." })
            }
            else -> children(CarMediaIds.playlist(id.connection, id.parent)).firstOrNull { it.mediaId == mediaId }
                ?: throw IllegalArgumentException("This song is unavailable. Refresh the playlist.")
        }
    }

    suspend fun resolve(mediaId: String): Selection {
        graph.bootstrap()
        val id = requireNotNull(CarMediaIds.parse(mediaId)) { "Choose a song or shuffle action from Audio." }
        val credentials = credentials()
        val previous = graph.session.value
        require(id.connection == credentials.connection.id) { "The music account changed. Refresh playlists." }
        if (id.kind == "queued") {
            val saved = requireNotNull(previous) { "Choose a playlist first." }
            require(saved.connectionId == id.connection && saved.queueId == id.parent && id.index in saved.queue.indices) {
                "The queue changed. Reopen Queue."
            }
            return Selection(credentials, previous, saved.copy(index = id.index!!, positionMs = 0))
        }
        require(id.kind == "shuffle" || id.kind == "track") { "Choose a song or shuffle action from Audio." }
        val playlist = playlists(credentials).firstOrNull { it.id == id.parent }
        requireNotNull(playlist) { "This playlist is unavailable. Refresh playlists." }
        val songs = graph.playlists.fetchContents(credentials, id.parent).songs
        checkCurrent(credentials)
        val shuffled = SessionRules.start(id.connection, playlist.id, playlist.name, songs)
        val selected = if (id.kind == "shuffle") shuffled else {
            require(id.index in songs.indices && songs[id.index!!].id == id.song) { "The playlist changed. Reopen it and choose the song again." }
            shuffled.copy(queue = songs, index = id.index, shuffle = false)
        }
        return Selection(credentials, previous, selected)
    }

    suspend fun search(query: String): List<MediaItem> {
        require(query.length <= 256) { "Use a shorter playlist name." }
        graph.bootstrap()
        val credentials = credentials()
        return playlists(credentials).filter { it.name.contains(query.trim(), ignoreCase = true) }
            .map { playlistItem(credentials.connection.id, it) }
    }

    suspend fun resolveSearch(query: String): Selection {
        require(query.length <= 256) { "Use a shorter playlist name." }
        graph.bootstrap()
        val credentials = credentials()
        val previous = graph.session.value
        if (query.isBlank()) return Selection(credentials, previous, requireNotNull(previous) { "Choose a playlist in Audio first." })
        val matches = playlists(credentials).filter { it.name.contains(query.trim(), ignoreCase = true) }
        val exact = matches.filter { it.name.equals(query.trim(), ignoreCase = true) }
        val playlist = if (exact.isNotEmpty()) exact.singleOrNull() else matches.singleOrNull()
        requireNotNull(playlist) { "Say an exact playlist name or choose a playlist in Audio." }
        val songs = graph.playlists.fetchContents(credentials, playlist.id).songs
        checkCurrent(credentials)
        return Selection(credentials, previous, SessionRules.start(credentials.connection.id, playlist.id, playlist.name, songs))
    }

    private fun credentials() = requireNotNull(graph.credentials.value) { "Open Audio on your phone and connect to Navidrome." }

    private fun checkCurrent(credentials: Credentials) {
        require(graph.credentials.value == credentials) { "The music account changed. Refresh playlists." }
    }
    private suspend fun playlists(credentials: Credentials, reconcileCounts: Boolean = false): List<Playlist> {
        val catalog = graph.playlists.listing(credentials, reconcileCounts)
        checkCurrent(credentials)
        require(catalog.connectionId == credentials.connection.id) { "The music account changed. Refresh playlists." }
        if (catalog.listError != null) throw com.everyonesoftware.audio.data.NavidromeException(catalog.listError)
        return catalog.playlists
    }

    companion object {
        const val SHUFFLE_PLAYLIST = "com.everyonesoftware.audio.SHUFFLE_PLAYLIST"
        private fun playlistItem(connection: String, playlist: Playlist): MediaItem = MediaItem.Builder()
            .setMediaId(CarMediaIds.playlist(connection, playlist.id))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(playlist.name).setSubtitle("${playlist.songCount} songs")
                .setIsBrowsable(true).setIsPlayable(false).setSupportedCommands(listOf(SHUFFLE_PLAYLIST)).build()).build()
        private fun folder(id: String, title: String, subtitle: String? = null) = media(id, title, subtitle, browsable = true)
        private fun playable(id: String, title: String, subtitle: String? = null) = media(id, title, subtitle, browsable = false)
        fun songItem(id: String, song: Song): MediaItem = MediaItem.Builder().setMediaId(id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).setIsBrowsable(false).setIsPlayable(true).build()).build()
        private fun media(id: String, title: String, subtitle: String?, browsable: Boolean): MediaItem =
            MediaItem.Builder().setMediaId(id).setMediaMetadata(MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle)
                .setIsBrowsable(browsable).setIsPlayable(!browsable).build()).build()

        fun page(items: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
            require(page >= 0 && pageSize > 0) { "Invalid library page." }
            val from = page.toLong() * pageSize
            if (from >= items.size) return emptyList()
            return items.subList(from.toInt(), (from + pageSize).coerceAtMost(items.size.toLong()).toInt())
        }
    }
}
