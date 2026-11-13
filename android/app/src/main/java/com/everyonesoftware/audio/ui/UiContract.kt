package com.everyonesoftware.audio.ui

import com.everyonesoftware.audio.data.Connection
import com.everyonesoftware.audio.data.ListeningSession
import com.everyonesoftware.audio.data.MusicLibrary
import com.everyonesoftware.audio.data.Playlist

enum class Screen { WELCOME, CONNECT, LIBRARIES, READY, PLAYLISTS, PLAYER, SETTINGS, QUEUE }

data class AudioUiState(
    val booting: Boolean = true,
    val screen: Screen = Screen.WELCOME,
    val connections: List<Connection> = emptyList(),
    val connection: Connection? = null,
    val libraries: List<MusicLibrary> = emptyList(),
    val selectedLibraries: Set<String> = emptySet(),
    val playlists: List<Playlist> = emptyList(),
    val playlistsRefreshing: Boolean = false,
    val stalePlaylistIds: Set<String> = emptySet(),
    val playlistError: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val session: ListeningSession? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val ratingBusy: Boolean = false,
    val ratingError: String? = null,
)

interface AudioUiActions {
    fun connect(name: String, url: String, username: String, password: String)
    fun showScreen(screen: Screen)
    fun setLibrary(id: String, selected: Boolean)
    fun saveLibraries()
    fun loadPlaylists()
    fun startPlaylist(id: String)
    fun togglePlay()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun toggleShuffle()
    fun toggleRepeat()
    fun rateSong(rating: Int)
    fun clearError()
    fun switchConnection(id: String)
}
