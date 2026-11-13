package com.everyonesoftware.audio.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.everyonesoftware.audio.data.Connection
import com.everyonesoftware.audio.data.Album
import com.everyonesoftware.audio.data.ListeningSession
import com.everyonesoftware.audio.data.MusicLibrary
import com.everyonesoftware.audio.data.Playlist
import com.everyonesoftware.audio.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real native UI; all account/media fixtures and action spies live only in androidTest. */
@RunWith(AndroidJUnit4::class)
class AudioScreensTest {
    @get:Rule val compose = createComposeRule()

    private val actions = RecordingActions()
    private val connection = Connection("test-server", "Test server", "https://example.invalid", "test-user")
    private val song = Song("test-song", "Test song", "Test artist", userRating = 2)
    private val session = ListeningSession("test-server", "test-playlist", "Test playlist", listOf(song))

    private fun render(state: AudioUiState) {
        compose.setContent { AudioTheme { AudioApp(state, actions, artworkUrl = { null }) } }
    }

    private fun playerState() = AudioUiState(
        booting = false,
        screen = Screen.PLAYER,
        connection = connection,
        session = session,
        durationMs = 180_000L,
    )

    @Test fun welcomeConnectOpensTheRealConnectionRoute() {
        render(AudioUiState(booting = false))
        compose.onNodeWithText("WELCOME TO MUSIC").assertIsDisplayed()
        compose.onNodeWithText("Connect to Navidrome").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(Screen.CONNECT), actions.screens) }
    }

    @Test fun connectionStartsBlankAndPasswordUsesHiddenSemantics() {
        render(AudioUiState(booting = false, screen = Screen.CONNECT))
        compose.onNodeWithText("Use your Navidrome account. You don’t need to create a Music account.").assertIsDisplayed()
        listOf("connect-server-url", "connect-username", "connect-password").forEach { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        }
        compose.onNodeWithTag("connect-password")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    }

    @Test fun connectionSubmitsOnlyEnteredCredentials() {
        render(AudioUiState(booting = false, screen = Screen.CONNECT))
        compose.onNodeWithTag("connect-server-url").performTextInput("https://example.invalid")
        compose.onNodeWithTag("connect-username").performTextInput("entered-user")
        compose.onNodeWithTag("connect-password").performTextInput("test-only-password")
        compose.onNodeWithTag("connect-submit").performClick()
        compose.runOnIdle {
            assertEquals(1, actions.connects.size)
            val submitted = actions.connects.single()
            assertEquals("https://example.invalid", submitted.url)
            assertEquals("entered-user", submitted.username)
            assertEquals("test-only-password", submitted.password)
        }
    }

    @Test fun connectingDisablesDuplicateSubmissionAndShowsRealError() {
        render(AudioUiState(
            booting = false, screen = Screen.CONNECT, loading = true,
            error = "Test connection failed",
        ))
        compose.onNodeWithTag("connect-submit").assertIsNotEnabled()
        compose.onNodeWithText("Test connection failed").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, actions.connects.size) }
    }

    @Test fun libraryContinueIsVisibleWithoutScrollingEvenForLongLibraryList() {
        render(AudioUiState(
            booting = false,
            screen = Screen.LIBRARIES,
            connection = connection,
            libraries = (1..20).map { MusicLibrary("library-$it", "Test library $it") },
            selectedLibraries = setOf("library-1"),
        ))
        compose.onNodeWithText("What happens to playlists?", substring = true).assertDoesNotExist()
        // Deliberately no performScrollTo: the setup action must have a fixed visible footer.
        compose.onNodeWithTag("library-continue").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, actions.librarySaves) }
    }

    @Test fun librarySaveDisablesDuplicateContinue() {
        render(AudioUiState(
            booting = false, screen = Screen.LIBRARIES, loading = true,
            libraries = listOf(MusicLibrary("library-1", "Test library")),
            selectedLibraries = setOf("library-1"),
        ))
        compose.onNodeWithTag("library-continue").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test fun playlistShuffleStartsTheChosenPlaylistExactlyOnce() {
        render(AudioUiState(
            booting = false, screen = Screen.PLAYLISTS, connection = connection,
            playlists = listOf(Playlist("test-playlist", "Test playlist", songCount = 3)),
        ))
        compose.onNodeWithTag("playlist-shuffle-test-playlist").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("test-playlist"), actions.startedPlaylists) }
    }

    @Test fun cachedZeroDoesNotPreventCheckingPlaylistContents() {
        render(AudioUiState(
            booting = false, screen = Screen.PLAYLISTS, connection = connection,
            playlists = listOf(Playlist("test-playlist", "Test playlist", songCount = 0)),
        ))
        compose.onNodeWithTag("playlist-shuffle-test-playlist").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("test-playlist"), actions.startedPlaylists) }
    }

    @Test fun libraryShowsPlaylistAndAlbumShelvesAndPersistentTabs() {
        render(AudioUiState(
            booting = false, screen = Screen.PLAYLISTS, connection = connection,
            playlists = listOf(Playlist("test-playlist", "Test playlist", songCount = 3)),
            albums = listOf(Album("test-album", "Test album", "Test artist", songCount = 2)),
        ))
        compose.onNodeWithText("Playlists").assertIsDisplayed()
        compose.onNodeWithText("Albums").assertIsDisplayed()
        compose.onNodeWithTag("playlist-shuffle-test-playlist").assertIsDisplayed()
        compose.onNodeWithTag("album-card-test-album").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("test-album"), actions.openedAlbums) }
        compose.onNodeWithTag("tab-library").assertIsDisplayed()
        compose.onNodeWithTag("tab-search").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(Screen.SEARCH, actions.screens.last()) }
    }

    @Test fun bottomChromeSpansWidthAndUsesApprovedTonalSurfacesAcrossScreens() {
        val state = mutableStateOf(playerState().copy(screen = Screen.PLAYLISTS))
        compose.setContent { AudioTheme { AudioApp(state.value, actions, artworkUrl = { null }) } }
        for (screen in listOf(Screen.PLAYLISTS, Screen.SEARCH, Screen.ALBUM, Screen.QUEUE, Screen.SETTINGS, Screen.PLAYER)) {
            compose.runOnIdle { state.value = state.value.copy(screen = screen) }
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val first = compose.onNodeWithTag("tab-library").fetchSemanticsNode().boundsInRoot
            val last = compose.onNodeWithTag("tab-settings").fetchSemanticsNode().boundsInRoot
            assertEquals("$screen: no left tab gutter", root.left, first.left, 1f)
            assertEquals("$screen: no right tab gutter", root.right, last.right, 1f)
            val navigation = compose.onNodeWithTag("bottom-navigation").fetchSemanticsNode().boundsInRoot
            assertEquals("$screen: navigation reaches the bottom", root.bottom, navigation.bottom, 1f)
            val pixels = compose.onRoot().captureToImage().toPixelMap()
            val edgeX = (root.width - 2).toInt()
            assertEquals("$screen: darker tabs", Color(0xFF262E29), pixels[edgeX, first.center.y.toInt()])
            // The generic Compose test activity has its own three-button contrast scrim.
            // NativeFlowTest checks the inset pixels with MainActivity's real dark system-bar style.
            if (screen == Screen.PLAYER) {
                compose.onNodeWithTag("mini-player").assertDoesNotExist()
            } else {
                val mini = compose.onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot
                assertEquals("$screen: full-width mini-player", root.width, mini.width, 1f)
                assertEquals("$screen: mini-player touches tabs", mini.bottom, navigation.top, 1f)
                assertEquals("$screen: lighter player", Color(0xFF354239), pixels[edgeX, mini.center.y.toInt()])
                compose.onNodeWithTag("mini-player-toggle").assertIsDisplayed().performClick()
            }
        }
        compose.runOnIdle { assertEquals(5, actions.playToggles) }
        assertTrue(actions.screens.isEmpty())
    }

    @Test fun tonalMiniPlayerOpensFullPlayerAndCapturesLibraryAndPlayer() {
        val state = mutableStateOf(playerState().copy(
            screen = Screen.PLAYLISTS,
            playlists = listOf(Playlist("test-playlist", "Test playlist", songCount = 3)),
            albums = listOf(Album("test-album", "Test album", "Test artist", songCount = 2)),
        ))
        compose.setContent { AudioTheme { AudioApp(state.value, actions, artworkUrl = { null }) } }
        for (screen in listOf(Screen.PLAYLISTS, Screen.PLAYER)) {
            compose.runOnIdle { state.value = state.value.copy(screen = screen) }
            compose.waitForIdle()
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            val directory = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "evidence").apply { mkdirs() }
            java.io.File(directory, "tonal-${screen.name.lowercase()}.png").outputStream().use {
                assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            image.recycle()
        }
        compose.runOnIdle { state.value = state.value.copy(screen = Screen.PLAYLISTS) }
        compose.onNodeWithText("Test song").performClick()
        compose.runOnIdle { assertEquals(listOf(Screen.PLAYER), actions.screens) }
    }

    @Test fun albumDetailsStartsTheSelectedAlbumAndListsItsTracks() {
        render(AudioUiState(
            booting = false, screen = Screen.ALBUM, connection = connection,
            selectedAlbum = Album("test-album", "Test album", "Test artist", songCount = 1),
            albumSongs = listOf(song.copy(id = "album-track", title = "Album track")),
        ))
        compose.onNodeWithText("Album track").assertIsDisplayed()
        compose.onNodeWithTag("album-play").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("test-album"), actions.playedAlbums) }
    }

    @Test fun playerTransportCallsParentActions() {
        render(playerState())
        compose.onNodeWithTag("player-toggle").assertIsDisplayed().performClick()
        compose.onNodeWithTag("player-next").assertIsDisplayed().performClick()
        compose.onNodeWithTag("player-seek").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, actions.playToggles)
            assertEquals(1, actions.nextCalls)
        }
    }

    @Test fun ratingModalHasFiveLargeRadioTargetsAndDoneClosesWithoutSaving() {
        render(playerState())
        compose.onNodeWithTag("player-rating").assertIsDisplayed().performClick()
        compose.onNodeWithTag("player-queue").assertIsDisplayed()
        compose.onNodeWithTag("tab-library").assertIsDisplayed()
        compose.onNodeWithText("Change playlist").assertDoesNotExist()
        compose.onNodeWithText("READY TO RESUME").assertDoesNotExist()
        compose.onNodeWithText("Tap a star to save to Navidrome. Clear removes your song rating.").assertDoesNotExist()
        (1..5).forEach { value ->
            compose.onNodeWithTag("rating-$value")
                .assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(64.dp)
        }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("rating-1").assertDoesNotExist()
        compose.onNodeWithTag("player-rating").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<Int>(), actions.ratings) }
    }

    @Test fun tappingRatingCallsParentWithoutMutatingTheSessionSong() {
        render(playerState())
        compose.onNodeWithTag("player-rating").performClick()
        compose.onNodeWithTag("rating-4").performClick()
        compose.runOnIdle {
            assertEquals(listOf(4), actions.ratings)
            assertEquals(2, session.queue.single().userRating)
        }
    }

    @Test fun clearRatingSendsZeroToParent() {
        render(playerState())
        compose.onNodeWithTag("player-rating").performClick()
        compose.onNodeWithText("Clear rating").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(0), actions.ratings) }
    }

    @Test fun pendingRatingDisablesChoicesAndErrorRetryResendsTheAttempt() {
        val state = mutableStateOf(playerState())
        compose.setContent { AudioTheme { AudioApp(state.value, actions, artworkUrl = { null }) } }
        compose.onNodeWithTag("player-rating").performClick()
        compose.onNodeWithTag("rating-4").performClick()
        compose.runOnIdle { state.value = state.value.copy(ratingBusy = true) }
        (1..5).forEach { compose.onNodeWithTag("rating-$it").assertIsNotEnabled() }
        compose.onNodeWithText("Clear rating").assertIsNotEnabled()
        compose.runOnIdle {
            state.value = state.value.copy(ratingBusy = false, ratingError = "Test rating save failed")
        }
        compose.onNodeWithText("Test rating save failed").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(4, 4), actions.ratings)
            assertEquals(2, state.value.session!!.queue.single().userRating)
        }
    }

    private data class ConnectCall(val name: String, val url: String, val username: String, val password: String)

    private class RecordingActions : AudioUiActions {
        val screens = mutableListOf<Screen>()
        val connects = mutableListOf<ConnectCall>()
        val startedPlaylists = mutableListOf<String>()
        val openedAlbums = mutableListOf<String>()
        val playedAlbums = mutableListOf<String>()
        val ratings = mutableListOf<Int>()
        var librarySaves = 0
        var playToggles = 0
        var nextCalls = 0
        override fun connect(name: String, url: String, username: String, password: String) {
            connects += ConnectCall(name, url, username, password)
        }
        override fun showScreen(screen: Screen) { screens += screen }
        override fun setLibrary(id: String, selected: Boolean) = Unit
        override fun saveLibraries() { librarySaves++ }
        override fun loadPlaylists() = Unit
        override fun loadAlbums() = Unit
        override fun openAlbum(id: String) { openedAlbums += id }
        override fun playAlbum(id: String) { playedAlbums += id }
        override fun startPlaylist(id: String) { startedPlaylists += id }
        override fun togglePlay() { playToggles++ }
        override fun next() { nextCalls++ }
        override fun previous() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun toggleShuffle() = Unit
        override fun toggleRepeat() = Unit
        override fun rateSong(rating: Int) { ratings += rating }
        override fun clearError() = Unit
        override fun switchConnection(id: String) = Unit
    }
}
