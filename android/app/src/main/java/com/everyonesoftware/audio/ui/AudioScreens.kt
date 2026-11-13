package com.everyonesoftware.audio.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.everyonesoftware.audio.data.Album
import com.everyonesoftware.audio.data.Playlist
import com.everyonesoftware.audio.data.Song

/** This is the single owner of system/cutout and keyboard insets. */
@Composable
fun AudioApp(state: AudioUiState, actions: AudioUiActions, artworkUrl: (String?) -> String?) {
    val home = if (state.session != null) Screen.PLAYER else if (state.connection != null) Screen.PLAYLISTS else Screen.WELCOME
    BackHandler(enabled = state.screen != Screen.WELCOME && state.screen != Screen.PLAYER) {
        actions.showScreen(if (state.screen == Screen.QUEUE) Screen.PLAYER else home)
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(horizontal = 18.dp)) {
            if (state.booting) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.semantics { contentDescription = "Restoring your listening session" })
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (state.screen) {
                        Screen.WELCOME -> Welcome(actions)
                        Screen.CONNECT -> Connect(state, actions, home)
                        Screen.LIBRARIES -> Libraries(state, actions)
                        Screen.READY -> Ready(state, actions)
                        Screen.PLAYLISTS -> Playlists(state, actions, artworkUrl)
                        Screen.SEARCH -> Search(state, actions, artworkUrl)
                        Screen.ALBUM -> AlbumDetails(state, actions, artworkUrl)
                        Screen.PLAYER -> Player(state, actions, artworkUrl)
                        Screen.SETTINGS -> Settings(state, actions, home)
                        Screen.QUEUE -> Queue(state, actions, artworkUrl)
                    }
                }
                if (state.session?.queue?.getOrNull(state.session.index) != null && state.screen in listOf(Screen.SETTINGS, Screen.PLAYLISTS, Screen.SEARCH, Screen.ALBUM, Screen.QUEUE)) {
                    MiniPlayer(state, actions, artworkUrl)
                }
                if (state.connection != null && state.screen in listOf(Screen.PLAYLISTS, Screen.SEARCH, Screen.ALBUM, Screen.PLAYER, Screen.SETTINGS, Screen.QUEUE)) {
                    MainNavigation(state.screen, actions)
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, eyebrow: String = "", back: (() -> Unit)? = null, settings: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) AudioIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = back)
        Column(Modifier.weight(1f)) {
            if (eyebrow.isNotBlank()) Text(eyebrow, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
        }
        if (settings != null) AudioIcon(Icons.Default.Settings, "Server and libraries", onClick = settings)
    }
}

@Composable
private fun AudioIcon(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick, modifier.sizeIn(minWidth = 48.dp, minHeight = 52.dp), enabled = enabled) {
        Icon(icon, label, tint = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current)
    }
}

@Composable
private fun MainNavigation(screen: Screen, actions: AudioUiActions) {
    val selected = when (screen) {
        Screen.SEARCH -> Screen.SEARCH
        Screen.SETTINGS -> Screen.SETTINGS
        else -> Screen.PLAYLISTS
    }
    NavigationBar(modifier = Modifier.height(68.dp), containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        NavigationBarItem(selected == Screen.PLAYLISTS, { actions.showScreen(Screen.PLAYLISTS) },
            icon = { Icon(Icons.Default.LibraryMusic, null) }, label = { Text("Library") }, modifier = Modifier.testTag("tab-library"))
        NavigationBarItem(selected == Screen.SEARCH, { actions.showScreen(Screen.SEARCH) },
            icon = { Icon(Icons.Default.Search, null) }, label = { Text("Search") }, modifier = Modifier.testTag("tab-search"))
        NavigationBarItem(selected == Screen.SETTINGS, { actions.showScreen(Screen.SETTINGS) },
            icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") }, modifier = Modifier.testTag("tab-settings"))
    }
}

@Composable
private fun Primary(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = enabled, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) { Text(label) }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ErrorMessage(error: String?) {
    if (error != null) Text(error, Modifier.padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Welcome(actions: AudioUiActions) {
    Column(Modifier.fillMaxSize().padding(vertical = 16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Hint("WELCOME TO AUDIO")
            Icon(Icons.Default.GraphicEq, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Your music.\nRight where\nyou left it.", style = MaterialTheme.typography.headlineLarge)
            Text("A personal player for the music on your Navidrome server.")
            HorizontalDivider()
            Text("Bring your playlists", style = MaterialTheme.typography.titleMedium)
            Hint("Choose your usual rotation. Shuffle once and start listening.")
            Text("Keep your place", style = MaterialTheme.typography.titleMedium)
            Hint("Next time, open right to the player and continue your session.")
        }
        Primary("Connect to Navidrome", Modifier.padding(top = 16.dp)) { actions.showScreen(Screen.CONNECT) }
        Hint("Use your existing Navidrome account.", Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun Connect(state: AudioUiState, actions: AudioUiActions, home: Screen) {
    // Password intentionally never goes into saveable state or mockup-prefilled values.
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Header("Connect your server", "Navidrome account", back = { actions.showScreen(home) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Hint("Use your Navidrome account. You don’t need to create an Audio account.")
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("connect-name"), enabled = !state.loading, label = { Text("Connection name (optional)") }, singleLine = true)
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().testTag("connect-server-url"), enabled = !state.loading, label = { Text("Server address") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true)
            Hint("Use the server’s base address, including a subpath if needed.")
            OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth().testTag("connect-username"), enabled = !state.loading, label = { Text("Username") }, singleLine = true)
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().testTag("connect-password"), enabled = !state.loading, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
            ErrorMessage(state.error)
            Spacer(Modifier.height(8.dp))
        }
        Primary(if (state.loading) "Checking connection…" else "Connect & discover libraries", Modifier.padding(vertical = 12.dp).testTag("connect-submit"), enabled = !state.loading && url.isNotBlank() && username.isNotBlank() && password.isNotEmpty()) {
            actions.connect(name.trim(), url.trim(), username.trim(), password)
        }
    }
}

@Composable
private fun Libraries(state: AudioUiState, actions: AudioUiActions) {
    Column(Modifier.fillMaxSize()) {
        Header("Choose libraries", "Browsing preferences", back = { actions.showScreen(Screen.SETTINGS) })
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                state.connection?.let { Text(it.name, style = MaterialTheme.typography.titleMedium); Hint(it.username) }
                Text("What do you want to browse?", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.headlineMedium)
                Hint("Choose one or more libraries this account can access. You can change this later.")
            }
            items(state.libraries, key = { it.id }) { library ->
                Row(Modifier.fillMaxWidth().heightIn(min = 86.dp).toggleable(state.selectedLibraries.contains(library.id), enabled = !state.loading, role = Role.Checkbox, onValueChange = { actions.setLibrary(library.id, it) }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state.selectedLibraries.contains(library.id), onCheckedChange = null, enabled = !state.loading)
                    Text(library.name, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                }
                HorizontalDivider()
            }
            item {
                if (state.libraries.isEmpty() && !state.loading) Hint("No libraries available. Ask your Navidrome administrator to grant this account access.")
                ErrorMessage(state.error)
                Hint("Missing a library? Creation and account access are managed in Navidrome.")
            }
        }
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Hint("${state.selectedLibraries.size} of ${state.libraries.size} selected", Modifier.padding(bottom = 8.dp))
                Primary(if (state.loading) "Saving…" else "Continue", Modifier.testTag("library-continue"), enabled = !state.loading && state.selectedLibraries.isNotEmpty(), onClick = actions::saveLibraries)
            }
        }
    }
}

@Composable
private fun Ready(state: AudioUiState, actions: AudioUiActions) {
    Column(Modifier.fillMaxSize()) {
        Header("You’re connected", "Setup complete", back = { actions.showScreen(Screen.LIBRARIES) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(Icons.Default.CheckCircle, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Let’s find your\nfirst playlist.", style = MaterialTheme.typography.headlineMedium)
            Hint("Once you start listening, the next launch will remember your place.")
            state.connection?.let { Text(it.name, style = MaterialTheme.typography.titleMedium); Hint(it.username) }
            HorizontalDivider()
            Hint("Browse libraries")
            Text(state.libraries.filter { it.id in state.selectedLibraries }.joinToString { it.name }.ifBlank { "None selected" })
            ErrorMessage(state.error)
        }
        Primary(if (state.loading) "Loading playlists…" else "Choose a playlist", Modifier.padding(vertical = 12.dp), enabled = !state.loading, onClick = actions::loadPlaylists)
    }
}

@Composable
private fun Artwork(id: String?, artworkUrl: (String?) -> String?, modifier: Modifier) {
    val url = remember(id, artworkUrl) { id?.let(artworkUrl) }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.MusicNote, null, Modifier.fillMaxSize(0.3f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

@Composable
private fun Playlists(state: AudioUiState, actions: AudioUiActions, artworkUrl: (String?) -> String?) {
    Column(Modifier.fillMaxSize()) {
        Header("Library", state.connection?.name.orEmpty())
        Surface(Modifier.fillMaxWidth().clickable { actions.showScreen(Screen.SEARCH) }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Hint("Search playlists and albums", Modifier.padding(start = 10.dp))
            }
        }
        ErrorMessage(state.error)
        ErrorMessage(state.playlistError)
        ErrorMessage(state.albumsError)
        if (state.playlistsRefreshing || state.albumsRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp).semantics { contentDescription = "Refreshing library" })
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 10.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Playlists", style = MaterialTheme.typography.titleLarge)
                    TextButton(actions::loadPlaylists, enabled = !state.loading && !state.playlistsRefreshing) { Text("Refresh") }
                }
                if (state.playlists.isEmpty() && !state.playlistsRefreshing) Hint("No playlists available on this server.")
                LazyRow(contentPadding = PaddingValues(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.playlists, key = { "playlist-${it.id}" }) { playlist ->
                        PlaylistTile(playlist, playlist.id in state.stalePlaylistIds, artworkUrl, enabled = !state.loading) { actions.startPlaylist(playlist.id) }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Albums", style = MaterialTheme.typography.titleLarge)
                    if (state.albumsRefreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                if (state.albums.isEmpty() && !state.albumsRefreshing && state.albumsError == null) Hint("No albums available on this server.")
                LazyRow(contentPadding = PaddingValues(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.albums, key = { "album-${it.id}" }) { album -> AlbumTile(album, artworkUrl) { actions.openAlbum(album.id) } }
                }
            }
        }
    }
}

@Composable
private fun PlaylistTile(playlist: Playlist, stale: Boolean, artworkUrl: (String?) -> String?, enabled: Boolean, onShuffle: () -> Unit) {
    Column(Modifier.width(132.dp)) {
        Box(Modifier.size(132.dp)) {
            Artwork(playlist.coverArtId, artworkUrl, Modifier.fillMaxSize().clickable(enabled = enabled, onClick = onShuffle).testTag("playlist-card-${playlist.id}"))
            FilledTonalIconButton(onShuffle, Modifier.align(Alignment.BottomEnd).padding(6.dp).size(42.dp).testTag("playlist-shuffle-${playlist.id}"), enabled = enabled) {
                Icon(Icons.Default.Shuffle, "Shuffle and play ${playlist.name}")
            }
        }
        Text(playlist.name, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Hint("${playlist.songCount} songs" + if (stale) " · last known" else "")
    }
}

@Composable
private fun AlbumTile(album: Album, artworkUrl: (String?) -> String?, onClick: () -> Unit) {
    Column(Modifier.width(132.dp).clickable(onClick = onClick).testTag("album-card-${album.id}")) {
        Artwork(album.coverArtId, artworkUrl, Modifier.size(132.dp))
        Text(album.name, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Hint(album.artist + if (album.year > 0) " · ${album.year}" else "")
    }
}

@Composable
private fun Search(state: AudioUiState, actions: AudioUiActions, artworkUrl: (String?) -> String?) {
    var query by rememberSaveable(state.connection?.id) { mutableStateOf("") }
    val term = query.trim()
    val playlists = state.playlists.filter { it.name.contains(term, ignoreCase = true) }
    val albums = state.albums.filter { it.name.contains(term, ignoreCase = true) || it.artist.contains(term, ignoreCase = true) }
    Column(Modifier.fillMaxSize()) {
        Header("Search", state.connection?.name.orEmpty())
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(bottom = 10.dp), label = { Text("Playlists and albums") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
        ErrorMessage(state.albumsError)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (term.isBlank()) item { Hint("Search your playlists and albums by name or artist.") }
            if (playlists.isNotEmpty()) item { Text("Playlists", style = MaterialTheme.typography.titleLarge) }
            items(playlists, key = { "search-playlist-${it.id}" }) { playlist ->
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable { actions.startPlaylist(playlist.id) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Artwork(playlist.coverArtId, artworkUrl, Modifier.size(52.dp))
                    Column(Modifier.weight(1f)) { Text(playlist.name, style = MaterialTheme.typography.titleMedium); Hint("${playlist.songCount} songs") }
                    AudioIcon(Icons.Default.Shuffle, "Shuffle and play ${playlist.name}") { actions.startPlaylist(playlist.id) }
                }
                HorizontalDivider()
            }
            if (albums.isNotEmpty()) item { Text("Albums", style = MaterialTheme.typography.titleLarge) }
            items(albums, key = { "search-album-${it.id}" }) { album ->
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable { actions.openAlbum(album.id) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Artwork(album.coverArtId, artworkUrl, Modifier.size(52.dp))
                    Column(Modifier.weight(1f)) { Text(album.name, style = MaterialTheme.typography.titleMedium); Hint(album.artist) }
                    Icon(Icons.Default.ChevronRight, "Open ${album.name}")
                }
                HorizontalDivider()
            }
            if (term.isNotBlank() && playlists.isEmpty() && albums.isEmpty()) item { Hint("No playlists or albums match “$term”.") }
        }
    }
}

@Composable
private fun AlbumDetails(state: AudioUiState, actions: AudioUiActions, artworkUrl: (String?) -> String?) {
    val album = state.selectedAlbum
    Column(Modifier.fillMaxSize()) {
        Header(album?.name ?: "Album", album?.artist.orEmpty(), back = { actions.showScreen(Screen.PLAYLISTS) })
        if (album != null) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                Artwork(album.coverArtId, artworkUrl, Modifier.size(220.dp))
            }
            Text(album.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Hint(album.artist + if (album.year > 0) " · ${album.year}" else "", Modifier.padding(top = 4.dp, bottom = 8.dp))
            Primary(if (state.loading) "Starting…" else "Play album", Modifier.testTag("album-play"), enabled = !state.loading && !state.albumLoading && state.albumSongs.isNotEmpty()) {
                actions.playAlbum(album.id)
            }
        }
        if (state.albumLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
        ErrorMessage(state.albumError)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 10.dp, bottom = 12.dp)) {
            items(state.albumSongs, key = { it.id }) { song ->
                Row(Modifier.fillMaxWidth().heightIn(min = 62.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) { Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis); Hint(song.artist) }
                    Hint(timeLabel(song.durationSeconds * 1000L))
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun Player(state: AudioUiState, actions: AudioUiActions, artworkUrl: (String?) -> String?) {
    val session = state.session
    val song = session?.queue?.getOrNull(session.index)
    if (session == null || song == null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Text("Choose something to listen to", style = MaterialTheme.typography.headlineMedium)
            ErrorMessage(state.error)
            Primary("Choose a playlist", Modifier.padding(vertical = 16.dp), enabled = !state.loading, onClick = actions::loadPlaylists)
        }
        return
    }
    var showRating by rememberSaveable(session.connectionId, song.id) { mutableStateOf(false) }
    var attemptedRating by rememberSaveable(session.connectionId, song.id) { mutableStateOf<Int?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scrollFallback = maxHeight < 580.dp || LocalDensity.current.fontScale > 1.3f
        Column(if (scrollFallback) Modifier.fillMaxSize().verticalScroll(rememberScrollState()) else Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(session.playlistName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton({ actions.showScreen(Screen.QUEUE) }, Modifier.testTag("player-queue")) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null)
                    Text("Queue", Modifier.padding(start = 6.dp))
                }
            }
            Box(if (scrollFallback) Modifier.fillMaxWidth().height(140.dp) else Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val side = minOf(maxHeight, maxWidth)
                    Artwork(song.coverArtId, artworkUrl, Modifier.size(side))
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.headlineMedium, maxLines = if (scrollFallback) 5 else 2, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton({ showRating = true }, Modifier.width(64.dp).heightIn(min = 64.dp).testTag("player-rating").semantics { contentDescription = "Rate song, ${if (song.userRating == 0) "not rated" else "${song.userRating} of 5 stars"}" }, contentPadding = PaddingValues(4.dp), shape = RoundedCornerShape(12.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (song.userRating > 0) Icons.Default.Star else Icons.Outlined.StarBorder, null)
                        Text(if (state.ratingBusy) "…" else if (song.userRating > 0) "${song.userRating}/5" else "Rate", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Column {
                var seeking by remember(song.id) { mutableStateOf<Float?>(null) }
                val duration = state.durationMs.coerceAtLeast(0)
                Slider(value = seeking ?: state.positionMs.coerceIn(0L, duration).toFloat(), onValueChange = { seeking = it }, onValueChangeFinished = { seeking?.let { actions.seekTo(it.toLong()) }; seeking = null }, valueRange = 0f..duration.coerceAtLeast(1L).toFloat(), enabled = duration > 0, modifier = Modifier.fillMaxWidth().testTag("player-seek").semantics { contentDescription = "Playback position" })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Hint(timeLabel((seeking?.toLong() ?: state.positionMs).coerceAtLeast(0))); Hint(timeLabel(duration)) }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 76.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                AudioIcon(Icons.Default.Shuffle, "Shuffle", Modifier.semantics { stateDescription = if (session.shuffle) "On" else "Off" }, active = session.shuffle, onClick = actions::toggleShuffle)
                AudioIcon(Icons.Default.SkipPrevious, "Previous track", onClick = actions::previous)
                FilledIconButton(actions::togglePlay, Modifier.size(width = 76.dp, height = 72.dp).testTag("player-toggle"), shape = RoundedCornerShape(24.dp)) {
                    Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.playing) "Pause" else "Resume playback", Modifier.size(34.dp))
                }
                AudioIcon(Icons.Default.SkipNext, "Next track", Modifier.testTag("player-next"), onClick = actions::next)
                AudioIcon(if (session.repeatMode == 1) Icons.Default.RepeatOne else Icons.Default.Repeat, "Repeat", Modifier.semantics { stateDescription = when (session.repeatMode) { 1 -> "Repeat one"; 2 -> "Repeat all"; else -> "Off" } }, active = session.repeatMode != 0, onClick = actions::toggleRepeat)
            }
            Spacer(Modifier.height(16.dp))
            ErrorMessage(state.error)
        }
    }
    if (showRating) RatingDialog(song.title, song.artist, song.userRating, state.ratingBusy, state.ratingError, attemptedRating, onRate = { attemptedRating = it; actions.rateSong(it) }, onClose = { showRating = false })
}

private fun timeLabel(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

@Composable
private fun RatingDialog(title: String, artist: String, confirmed: Int, busy: Boolean, error: String?, attempted: Int?, onRate: (Int) -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp).widthIn(max = 420.dp).onPreviewKeyEvent { if (it.key == Key.Escape) { onClose(); true } else false }, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Rate this song", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Hint(artist)
                Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    (1..5).forEach { rating ->
                        Surface(Modifier.weight(1f), color = if (rating == confirmed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (rating == confirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) {
                            Box(Modifier.widthIn(min = 42.dp).heightIn(min = 68.dp).testTag("rating-$rating").selectable(selected = rating == confirmed, enabled = !busy, role = Role.RadioButton, onClick = { onRate(rating) }).semantics { contentDescription = "$rating ${if (rating == 1) "star" else "stars"}" }, contentAlignment = Alignment.Center) {
                                Icon(if (rating <= confirmed) Icons.Default.Star else Icons.Outlined.StarBorder, null, Modifier.size(30.dp), tint = if (rating <= confirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (busy) Text("Saving rating…", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
                ErrorMessage(error)
                if (error != null && attempted != null) TextButton({ onRate(attempted) }, enabled = !busy) { Text("Retry") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ onRate(0) }, enabled = !busy && confirmed > 0) { Text("Clear rating", style = MaterialTheme.typography.bodyMedium) }
                    Button(onClose, Modifier.heightIn(min = 52.dp)) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun Settings(state: AudioUiState, actions: AudioUiActions, home: Screen) {
    val context = LocalContext.current
    var browserError by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Header("Server & libraries", "Connection settings", back = { actions.showScreen(home) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Hint("Connections on this device. Listening sessions stay separate for each server.")
            state.connections.forEach { connection ->
                Surface(shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, if (connection.id == state.connection?.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(connection.name, style = MaterialTheme.typography.titleMedium); Hint(connection.username); Hint(connection.baseUrl) }
                        if (connection.id == state.connection?.id) Text("Active", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        else TextButton({ actions.switchConnection(connection.id) }, enabled = !state.loading) { Text("Switch", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            TextButton({ actions.showScreen(Screen.CONNECT) }, enabled = !state.loading) { Text("Add another server") }
            if (state.connection != null) {
                HorizontalDivider()
                Text("Browse libraries", style = MaterialTheme.typography.titleLarge)
                Hint(state.libraries.filter { it.id in state.selectedLibraries }.joinToString { it.name }.ifBlank { "None selected" })
                Primary("Choose libraries", enabled = !state.loading) { actions.showScreen(Screen.LIBRARIES) }
                HorizontalDivider()
                Text("Server administration", style = MaterialTheme.typography.titleLarge)
                Hint("Create libraries, set folder paths, or grant access in Navidrome. Administrator access may be required.")
                Hint("Opens your connected server in an external browser.")
                OutlinedButton({
                    val uri = Uri.parse(state.connection.baseUrl)
                    if (uri.scheme !in listOf("https", "http") || uri.host.isNullOrBlank() || uri.userInfo != null) browserError = "The server address is not a valid HTTP or HTTPS address."
                    else {
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)); browserError = null }
                        catch (_: android.content.ActivityNotFoundException) { browserError = "No browser is available to open this server." }
                    }
                }, Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Open Navidrome administration", style = MaterialTheme.typography.bodyMedium) }
                ErrorMessage(browserError)
            }
            ErrorMessage(state.error)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Queue(state: AudioUiState, actions: AudioUiActions, artworkUrl: (String?) -> String?) {
    Column(Modifier.fillMaxSize()) {
        Header("Your saved queue", state.session?.playlistName.orEmpty(), back = { actions.showScreen(Screen.PLAYER) })
        Hint("Your order is kept when you resume. Browsing preferences do not remove songs from this queue.", Modifier.padding(bottom = 12.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
            val session = state.session
            if (session == null || session.queue.isEmpty()) item { Hint("No listening queue yet.") }
            else items(session.queue.size) { index ->
                val song = session.queue[index]
                // The public action contract has no jump-to-index mutation; do not fake one.
                Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).background(if (index == session.index) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text((index + 1).toString(), Modifier.widthIn(min = 24.dp), style = MaterialTheme.typography.bodySmall)
                    Artwork(song.coverArtId, artworkUrl, Modifier.size(46.dp))
                    Column(Modifier.weight(1f)) { Text(song.title, style = MaterialTheme.typography.titleMedium); Hint(song.artist); if (index == session.index) Text("Current song", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                }
                HorizontalDivider()
            }
        }
        ErrorMessage(state.error)
    }
}

@Composable
private fun MiniPlayer(state: AudioUiState, actions: AudioUiActions, artworkUrl: (String?) -> String?) {
    val session = state.session ?: return
    val song = session.queue.getOrNull(session.index) ?: return
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().heightIn(min = 74.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "Open full player") { actions.showScreen(Screen.PLAYER) }.padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Artwork(song.coverArtId, artworkUrl, Modifier.size(52.dp))
                Column(Modifier.weight(1f)) { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(session.playlistName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            AudioIcon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.playing) "Pause" else "Resume playback", onClick = actions::togglePlay)
        }
    }
}
