package com.everyonesoftware.audio.data

import kotlinx.serialization.Serializable

@Serializable
data class Connection(val id: String, val name: String, val baseUrl: String, val username: String)
data class Credentials(val connection: Connection, val password: String)
@Serializable
data class MusicLibrary(val id: String, val name: String)
@Serializable
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val coverArtId: String? = null,
    val durationSeconds: Int = 0,
    val userRating: Int = 0,
)
@Serializable
data class Playlist(val id: String, val name: String, val songCount: Int = 0, val coverArtId: String? = null)
@Serializable
data class ListeningSession(
    val connectionId: String,
    val playlistId: String,
    val playlistName: String,
    val queue: List<Song>,
    val index: Int = 0,
    val positionMs: Long = 0,
    val shuffle: Boolean = true,
    val repeatMode: Int = 0,
    val queueId: String = "",
)
