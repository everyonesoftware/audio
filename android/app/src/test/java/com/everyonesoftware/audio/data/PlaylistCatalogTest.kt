package com.everyonesoftware.audio.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistCatalogTest {
    private val a = Credentials(Connection("a", "A", "https://a.invalid/", "fixture"), "test-only")
    private val b = Credentials(Connection("b", "B", "https://b.invalid/", "fixture"), "test-only")
    private val song = Song("s", "Song", "Fixture")
    private val smart = Playlist("smart", "Rated", 0)

    private class Fixture {
        val cache = mutableMapOf<String, List<Playlist>>()
        var listed: suspend (Credentials) -> List<Playlist> = { listOf(Playlist("smart", "Rated", 0)) }
        var fetched: suspend (Credentials, String) -> List<Song> = { _, _ -> emptyList() }
        var validUntil: (String) -> Long? = { null }
        val calls = mutableListOf<Pair<String, String>>()
        fun catalog(scope: TestScope) = PlaylistCatalog(scope.backgroundScope,
            summaries = { listed(it) },
            contents = { credentials, id -> calls += credentials.connection.id to id; PlaylistContents(fetched(credentials, id), validUntil(id)) },
            cached = { cache[it].orEmpty() }, save = { id, value -> cache[id] = value },
            now = { scope.testScheduler.currentTime })
    }

    @Test fun contentsOverrideStaleZeroAndCountDuplicateOccurrences() = runTest {
        val fixture = Fixture().apply { fetched = { _, _ -> listOf(song, song) } }
        val catalog = fixture.catalog(this)
        catalog.activate(a)
        catalog.refresh().join()
        assertEquals(2, catalog.state.value.playlists.single().songCount)
        assertEquals(2, fixture.cache["a"]!!.single().songCount)
        assertTrue(catalog.state.value.staleIds.isEmpty())
        assertFalse(catalog.state.value.refreshing)
    }

    @Test fun cachedRowsStayVisibleWhileDetailsLoadAndRefreshesCoalesce() = runTest {
        val gate = CompletableDeferred<List<Song>>()
        val fixture = Fixture().apply { cache["a"] = listOf(smart.copy(songCount = 7)); fetched = { _, _ -> gate.await() } }
        val catalog = fixture.catalog(this)
        catalog.activate(a)
        val first = catalog.refresh()
        runCurrent()
        assertSame(first, catalog.refresh())
        assertEquals(7, catalog.state.value.playlists.single().songCount)
        assertTrue(catalog.state.value.refreshing)
        gate.complete(listOf(song))
        first.join()
        assertEquals(1, catalog.state.value.playlists.single().songCount)
    }

    @Test fun partialFailurePreservesLastKnownCountAndRefreshesOtherRows() = runTest {
        val fixture = Fixture().apply {
            cache["a"] = listOf(smart.copy(songCount = 8))
            listed = { listOf(smart, Playlist("other", "Other", 5)) }
            fetched = { _, id -> if (id == "smart") error("private URL or token") else emptyList() }
        }
        val catalog = fixture.catalog(this)
        catalog.activate(a)
        catalog.refresh().join()
        assertEquals(listOf(8, 0), catalog.state.value.playlists.map { it.songCount })
        assertEquals(setOf("smart"), catalog.state.value.staleIds)
        assertNotNull(catalog.state.value.error)
        assertFalse(catalog.state.value.error!!.contains("private"))
        assertEquals(catalog.state.value.playlists, fixture.cache["a"])
    }

    @Test fun summaryFailureRetainsCacheAndCanBeRetried() = runTest {
        val fixture = Fixture().apply { cache["a"] = listOf(smart.copy(songCount = 8)); listed = { error("private") } }
        val catalog = fixture.catalog(this)
        catalog.activate(a)
        catalog.refresh().join()
        assertEquals(8, catalog.state.value.playlists.single().songCount)
        assertNotNull(catalog.state.value.error)
        fixture.listed = { listOf(smart) }
        catalog.refresh().join()
        assertEquals(0, catalog.state.value.playlists.single().songCount)
        assertNull(catalog.state.value.error)
    }

    @Test fun lateOldAccountResponseCannotChangeNewAccountOrItsCache() = runTest {
        val gate = CompletableDeferred<List<Song>>()
        val fixture = Fixture().apply {
            fetched = { credentials, _ -> if (credentials == a) withContext(NonCancellable) { gate.await() } else listOf(song) }
        }
        val catalog = fixture.catalog(this)
        catalog.activate(a)
        val old = catalog.refresh()
        runCurrent()
        catalog.activate(b)
        catalog.refresh().join()
        gate.complete(listOf(song, song))
        old.join()
        assertEquals("b", catalog.state.value.connectionId)
        assertEquals(1, catalog.state.value.playlists.single().songCount)
        assertEquals(1, fixture.cache["b"]!!.single().songCount)
        assertNull(fixture.cache["a"])
    }

    @Test fun ratingsDebounceAndEarlyBrowseCannotConsumeDeferredRefresh() = runTest {
        var matches = listOf(song, song)
        val fixture = Fixture().apply { fetched = { _, _ -> matches } }
        val catalog = fixture.catalog(this)
        catalog.activate(a)
        catalog.refresh().join()
        catalog.ratingChanged(a)
        advanceTimeBy(3_000)
        catalog.ratingChanged(a)
        catalog.refresh().join()
        assertEquals(setOf("smart"), catalog.state.value.staleIds)
        assertEquals(2, fixture.calls.size)
        matches = listOf(song)
        advanceTimeBy(5_999)
        runCurrent()
        assertEquals(2, fixture.calls.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, fixture.calls.size)
        assertEquals(1, catalog.state.value.playlists.single().songCount)
        assertTrue(catalog.state.value.staleIds.isEmpty())
    }

    @Test fun accountSwitchCancelsDeferredRatingRefresh() = runTest {
        val fixture = Fixture()
        val catalog = fixture.catalog(this)
        catalog.activate(a)
        catalog.ratingChanged(a)
        catalog.activate(b)
        catalog.ratingChanged(a)
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(fixture.calls.isEmpty())
        assertEquals("b", catalog.state.value.connectionId)
    }

    @Test fun advertisedValidityDefersOnlyTheAffectedPlaylist() = runTest {
        val fixture = Fixture().apply {
            listed = { listOf(smart, Playlist("slow", "Slow smart playlist")) }
            validUntil = { id -> if (id == "slow") 30_000L else null }
            fetched = { _, _ -> listOf(song) }
        }
        val catalog = fixture.catalog(this)
        catalog.activate(a)
        catalog.refresh().join()
        catalog.ratingChanged(a)
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(2, fixture.calls.count { it.second == "smart" })
        assertEquals(1, fixture.calls.count { it.second == "slow" })
        assertEquals(setOf("slow"), catalog.state.value.staleIds)
        advanceTimeBy(24_100)
        runCurrent()
        assertEquals(2, fixture.calls.count { it.second == "slow" })
        assertTrue(catalog.state.value.staleIds.isEmpty())
    }

    @Test fun playbackContentsCannotBeOverwrittenByAnOlderBackgroundResponse() = runTest {
        val gate = CompletableDeferred<List<Song>>()
        val fixture = Fixture().apply { fetched = { _, _ -> gate.await() } }
        val catalog = fixture.catalog(this)
        catalog.activate(a)
        val refresh = catalog.refresh()
        runCurrent()
        fixture.fetched = { _, _ -> List(3) { song } }
        catalog.fetchContents(a, "smart")
        gate.complete(emptyList())
        refresh.join()
        assertEquals(3, catalog.state.value.playlists.single().songCount)
        assertEquals(3, fixture.cache["a"]!!.single().songCount)
    }

    @Test fun requestConcurrencyIsBoundedAndAllRowsAreReconciled() = runTest {
        var inFlight = 0
        var peak = 0
        val fixture = Fixture().apply {
            listed = { (1..20).map { Playlist("$it", "Playlist $it", 99) } }
            fetched = { _, _ ->
                inFlight++
                peak = maxOf(peak, inFlight)
                delay(100)
                inFlight--
                listOf(song)
            }
        }
        val catalog = fixture.catalog(this)
        catalog.activate(a)
        val refresh = catalog.refresh()
        advanceTimeBy(1_000)
        refresh.join()
        assertEquals(3, peak)
        assertEquals(20, fixture.calls.size)
        assertTrue(catalog.state.value.playlists.all { it.songCount == 1 })
        assertTrue(catalog.state.value.staleIds.isEmpty())
    }

    @Test fun removedPlaylistsDisappearAndAnEmptyCatalogCompletes() = runTest {
        val fixture = Fixture().apply { cache["a"] = listOf(smart); listed = { emptyList() } }
        val catalog = fixture.catalog(this)
        catalog.activate(a)
        catalog.refresh().join()
        assertTrue(catalog.state.value.playlists.isEmpty())
        assertTrue(fixture.cache["a"]!!.isEmpty())
        assertFalse(catalog.state.value.refreshing)
    }

    @Test fun persistenceIsBatchedRatherThanRewritingTheCatalogForEveryCount() = runTest {
        var writes = 0
        val catalog = PlaylistCatalog(backgroundScope,
            summaries = { (1..20).map { Playlist("$it", "Playlist $it", 99) } },
            contents = { _, _ -> PlaylistContents(listOf(song)) }, cached = { emptyList() },
            save = { _, _ -> writes++ }, now = { testScheduler.currentTime })
        catalog.activate(a)
        catalog.refresh().join()
        assertTrue("A scan should persist a batched snapshot, not one per row: $writes", writes <= 2)
        assertTrue(writes > 0)
    }

    @Test fun lateInspectionFromBeforeRatingCannotOverwriteThePostRatingCount() = runTest {
        val fixture = Fixture().apply { fetched = { _, _ -> List(3) { song } } }
        val catalog = fixture.catalog(this)
        catalog.activate(a)
        catalog.refresh().join()
        val gate = CompletableDeferred<List<Song>>()
        fixture.fetched = { _, _ -> gate.await() }
        val inspection = async { catalog.fetchContents(a, "smart") }
        runCurrent()
        catalog.ratingChanged(a)
        fixture.fetched = { _, _ -> listOf(song) }
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(1, catalog.state.value.playlists.single().songCount)
        gate.complete(List(3) { song })
        inspection.await()
        advanceTimeBy(300)
        assertEquals(1, catalog.state.value.playlists.single().songCount)
        assertTrue(catalog.state.value.staleIds.isEmpty())
        assertEquals(1, fixture.cache["a"]!!.single().songCount)
    }

    @Test fun lateInspectionCannotOverwriteANewerScanEvenWithoutARatingChange() = runTest {
        val fixture = Fixture()
        val catalog = fixture.catalog(this)
        catalog.activate(a)
        catalog.refresh().join()
        val gate = CompletableDeferred<List<Song>>()
        fixture.fetched = { _, _ -> gate.await() }
        val inspection = async { catalog.fetchContents(a, "smart") }
        runCurrent()
        fixture.fetched = { _, _ -> listOf(song) }
        catalog.refresh().join()
        gate.complete(List(3) { song })
        inspection.await()
        assertEquals(1, catalog.state.value.playlists.single().songCount)
        assertEquals(1, fixture.cache["a"]!!.single().songCount)
    }

    @Test fun listingDoesNotAwaitGatedDetailsAndReusesRecentSummaries() = runTest {
        val gate = CompletableDeferred<List<Song>>()
        var lists = 0
        val fixture = Fixture().apply {
            listed = { lists++; listOf(smart) }
            fetched = { _, _ -> gate.await() }
        }
        val catalog = fixture.catalog(this)
        catalog.activate(a)
        val listed = catalog.listing(a)
        assertTrue(listed.refreshing)
        assertEquals(1, listed.playlists.size)
        assertEquals(1, catalog.listing(a).playlists.size)
        assertEquals(1, lists)
        gate.complete(listOf(song))
        catalog.refresh().join()
        assertEquals(1, catalog.listing(a).playlists.single().songCount)
        assertEquals(1, lists)
        advanceTimeBy(30_001)
        catalog.listing(a)
        assertEquals(2, lists)
    }

    @Test fun failedDetailDoesNotMakeThePlaylistListUnavailable() = runTest {
        val fixture = Fixture().apply { fetched = { _, _ -> error("fixture detail failed") } }
        val catalog = fixture.catalog(this)
        catalog.activate(a)
        catalog.refresh().join()
        val listed = catalog.listing(a, reconcileCounts = false)
        assertNotNull(listed.error)
        assertNull(listed.listError)
        assertEquals(1, listed.playlists.size)
    }

    @Test fun playbackNameLookupDoesNotStartAnUnrelatedCountScan() = runTest {
        val fixture = Fixture().apply { fetched = { _, _ -> listOf(song) } }
        val catalog = fixture.catalog(this)
        catalog.activate(a)
        assertEquals(1, catalog.listing(a, reconcileCounts = false).playlists.size)
        runCurrent()
        assertTrue(fixture.calls.isEmpty())
        catalog.listing(a)
        runCurrent()
        assertEquals(1, fixture.calls.size)
    }
}
