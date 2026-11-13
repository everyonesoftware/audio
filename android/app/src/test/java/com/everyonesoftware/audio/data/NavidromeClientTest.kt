package com.everyonesoftware.audio.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okio.Buffer
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class NavidromeClientTest {
    private fun credentials(url: String) = Credentials(Connection("connection", "Music", url, "a+b &雪"), "secret &雪")
    private fun client(server: MockWebServer) = NavidromeClient(credentials(server.url("/music/").toString()), OkHttpClient(), allowLocalHttp = true)
    private fun ok(fields: String = "") = MockResponse().setBody("""{"subsonic-response":{"status":"ok","version":"1.16.1"$fields}}""")
    private inline fun <reified T : Throwable> rejects(block: () -> Unit): T {
        try { block() } catch (error: Throwable) { assertTrue(error.javaClass.name, error is T); return error as T }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    @Test fun baseUrlPreservesReverseProxyPath() {
        assertEquals("https://example.com/navidrome/", NavidromeClient.validateBaseUrl("https://example.com/navidrome"))
    }
    @Test fun baseUrlRejectsUnsafeInputs() {
        listOf("http://example.com", "https://u:p@example.com", "https://example.com/?token=x", "https://example.com/#x", "file:///music").forEach {
            rejects<IllegalArgumentException> { NavidromeClient.validateBaseUrl(it) }
        }
        rejects<IllegalArgumentException> { NavidromeClient.validateBaseUrl("http://localhost:1234") }
        rejects<IllegalArgumentException> { NavidromeClient.validateBaseUrl("http://192.168.1.1", true) }
        assertEquals("http://127.0.0.1:1234/", NavidromeClient.validateBaseUrl("http://127.0.0.1:1234", true))
    }
    @Test fun pingUsesFreshEncodedTokenAuthentication() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok()); server.enqueue(ok())
            val client = client(server)
            client.ping(); client.ping()
            val requests = List(2) { server.takeRequest(2, TimeUnit.SECONDS)!!.requestUrl!! }
            requests.forEach { url ->
                assertEquals("/music/rest/ping.view", url.encodedPath)
                assertEquals("a+b &雪", url.queryParameter("u"))
                assertEquals("1.16.1", url.queryParameter("v"))
                assertEquals("json", url.queryParameter("f"))
                assertNull(url.queryParameter("p"))
                val salt = url.queryParameter("s")!!
                assertTrue(salt.length >= 16)
                val token = MessageDigest.getInstance("MD5").digest(("secret &雪" + salt).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(token, url.queryParameter("t"))
            }
            assertNotEquals(requests[0].queryParameter("s"), requests[1].queryParameter("s"))
        }
    }
    @Test fun parsesNumericLibraryIdsAndEmptyCollections() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(",\"musicFolders\":{\"musicFolder\":[{\"id\":42,\"name\":\"Main\"}]}"))
            server.enqueue(ok(",\"playlists\":{}"))
            val client = client(server)
            assertEquals(listOf(MusicLibrary("42", "Main")), client.libraries())
            assertTrue(client.playlists().isEmpty())
        }
    }
    @Test fun parsesPlaylistSongsAndRatings() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(",\"playlist\":{\"entry\":[{\"id\":\"s1\",\"title\":\"Track\",\"artist\":\"Artist\",\"album\":\"Album\",\"duration\":93,\"userRating\":4,\"coverArt\":\"art\"}]}"))
            assertEquals(listOf(Song("s1", "Track", "Artist", "Album", "art", 93, 4)), client(server).playlist("p &/雪"))
            assertEquals("p &/雪", server.takeRequest().requestUrl!!.queryParameter("id"))
        }
    }
    @Test fun parsesAlbumListAndAlbumTracks() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(",\"albumList2\":{\"album\":[{\"id\":\"a1\",\"name\":\"Album One\",\"artist\":\"Artist\",\"coverArt\":\"art-a1\",\"songCount\":2,\"year\":2020}]}"))
            server.enqueue(ok(",\"album\":{\"id\":\"a1\",\"name\":\"Album One\",\"artist\":\"Artist\",\"coverArt\":\"art-a1\",\"songCount\":2,\"year\":2020,\"song\":[{\"id\":\"s1\",\"title\":\"Track One\",\"artist\":\"Artist\",\"album\":\"Album One\",\"coverArt\":\"art-a1\",\"duration\":180},{\"id\":\"s2\",\"title\":\"Track Two\",\"artist\":\"Artist\",\"album\":\"Album One\",\"coverArt\":\"art-a1\",\"duration\":210}]}"))
            val client = client(server)
            assertEquals(listOf(Album("a1", "Album One", "Artist", "art-a1", 2, 2020)), client.albums())
            assertEquals(listOf(Song("s1", "Track One", "Artist", "Album One", "art-a1", 180), Song("s2", "Track Two", "Artist", "Album One", "art-a1", 210)), client.album("a1").songs)
            val listRequest = server.takeRequest().requestUrl!!
            assertEquals("/music/rest/getAlbumList2.view", listRequest.encodedPath)
            assertEquals("alphabeticalByName", listRequest.queryParameter("type"))
            assertEquals("100", listRequest.queryParameter("size"))
            assertEquals("0", listRequest.queryParameter("offset"))
            assertEquals("a1", server.takeRequest().requestUrl!!.queryParameter("id"))
        }
    }
    @Test fun ratingReturnsAuthoritativeReadbackNotRequestedValue() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok()); server.enqueue(ok(",\"song\":{\"id\":\"s\",\"title\":\"T\",\"artist\":\"A\",\"userRating\":3}"))
            assertEquals(3, client(server).rateSong("s", 5))
            val set = server.takeRequest().requestUrl!!
            assertEquals("/music/rest/setRating.view", set.encodedPath)
            assertEquals("5", set.queryParameter("rating"))
            assertEquals("/music/rest/getSong.view", server.takeRequest().requestUrl!!.encodedPath)
        }
    }
    @Test fun parsesOptionalPlaylistValidityWithoutTrustingSummaryCounts() = runBlocking {
        MockWebServer().use { server ->
            val deadline = "2026-11-12T20:30:00.123Z"
            server.enqueue(ok(",\"playlists\":{\"playlist\":[{\"id\":\"p\",\"name\":\"Rated\",\"songCount\":99,\"validUntil\":\"$deadline\"}]}"))
            server.enqueue(ok(",\"playlist\":{\"songCount\":99,\"validUntil\":\"$deadline\",\"entry\":[{\"id\":\"s\",\"title\":\"Track\"}]}"))
            server.enqueue(ok(",\"playlist\":{\"validUntil\":\"unsupported timestamp\"}"))
            val client = client(server)
            val expected = java.time.Instant.parse(deadline).toEpochMilli()
            assertEquals(expected, client.playlists().single().validUntilMs)
            val result = client.playlistContents("p")
            assertEquals(expected, result.validUntilMs)
            assertEquals(1, result.songs.size)
            assertNull(client.playlistContents("p").validUntilMs)
        }
    }

    @Test fun ratingCanBeRetriedAfterUncertainReadback() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok()); server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(ok()); server.enqueue(ok(",\"song\":{\"id\":\"s\",\"userRating\":5}"))
            val client = client(server)
            rejects<NavidromeException> { runBlocking { client.rateSong("s", 5) } }
            assertEquals(5, client.rateSong("s", 5))
        }
    }
    @Test fun mediaUrisHaveNoPersistedSecretsAndRejectOtherTargets() {
        val client = NavidromeClient(credentials("https://example.com/music/"))
        val unsigned = client.streamUri("s &/雪")
        val url = unsigned.toHttpUrl()
        assertEquals("/music/rest/stream.view", url.encodedPath)
        assertEquals("s &/雪", url.queryParameter("id"))
        assertEquals(setOf("id"), url.queryParameterNames)
        assertNotNull(client.authenticatedMediaUri(unsigned).toHttpUrl().queryParameter("t"))
        assertEquals("/music/rest/getCoverArt.view", client.coverArtUri("a").toHttpUrl().encodedPath)
        listOf("https://evil.example/music/rest/stream.view?id=s", "https://example.com/rest/stream.view?id=s", "https://example.com/music/rest/ping.view", "https://example.com/music/rest/stream.view?id=s&t=old", "https://u:p@example.com/music/rest/stream.view?id=s").forEach {
            rejects<IllegalArgumentException> { client.authenticatedMediaUri(it) }
        }
    }
    @Test fun apiErrorsAreTypedAndNeverLeakServerContent() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"failed","error":{"code":40,"message":"secret &雪 https://sensitive.example"}}}"""))
            val error = rejects<NavidromeException> { runBlocking { client(server).ping() } }
            assertFalse(error.toString().contains("secret")); assertFalse(error.toString().contains("sensitive"))
            assertTrue(error.message!!.isNotBlank())
        }
    }
    @Test fun rejectsEmptyEmbeddedCredentialsAndAmbiguousBasePaths() {
        listOf("https://@example.com/", "https://example.com/music/../", "https://example.com/music\\rest").forEach {
            rejects<IllegalArgumentException> { NavidromeClient.validateBaseUrl(it) }
        }
    }
    @Test fun rejectsRatingReadbackForDifferentSong() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok()); server.enqueue(ok(",\"song\":{\"id\":\"other\",\"userRating\":5}"))
            rejects<NavidromeException> { runBlocking { client(server).rateSong("s", 5) } }
            Unit
        }
    }
    @Test fun rejectsErrorCodeEvenIfServerIncorrectlyReportsOkStatus() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(",\"error\":{\"code\":40,\"message\":\"private\"}"))
            rejects<NavidromeException> { runBlocking { client(server).ping() } }
            Unit
        }
    }
    private fun assertSafeArtworkError(error: NavidromeException) {
        // Coroutine stacktrace recovery may copy a safe exception and attach its original.
        // It must never attach an underlying transport exception or its signed URL.
        var cause: Throwable? = error
        while (cause != null) {
            assertTrue(cause.javaClass.name, cause is NavidromeException)
            assertEquals(error.message, cause.message)
            assertFalse(cause.toString().contains("private-art"))
            assertFalse(cause.toString().contains("secret"))
            assertFalse(cause.toString().contains("http://"))
            cause = cause.cause
        }
    }

    @Test fun coverArtDownloadsBytesWithFreshEncodedAuthenticationAndNoStore() = runBlocking {
        MockWebServer().use { server ->
            val bytes = byteArrayOf(0, -1, 42, -128)
            repeat(2) { server.enqueue(MockResponse().setBody(Buffer().write(bytes))) }
            val client = client(server)
            repeat(2) { assertArrayEquals(bytes, client.coverArt("art &/雪")) }
            val requests = List(2) { server.takeRequest(2, TimeUnit.SECONDS)!! }
            requests.forEach { request ->
                val url = request.requestUrl!!
                assertEquals("/music/rest/getCoverArt.view", url.encodedPath)
                assertEquals("art &/雪", url.queryParameter("id"))
                assertEquals("1024", url.queryParameter("size"))
                assertEquals("a+b &雪", url.queryParameter("u"))
                assertEquals("1.16.1", url.queryParameter("v"))
                assertEquals("EveryoneMusic", url.queryParameter("c"))
                assertNull(url.queryParameter("p"))
                val salt = url.queryParameter("s")!!
                val token = MessageDigest.getInstance("MD5").digest(("secret &雪" + salt).toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(token, url.queryParameter("t"))
                assertEquals("no-store", request.getHeader("Cache-Control"))
            }
            assertNotEquals(requests[0].requestUrl!!.queryParameter("s"), requests[1].requestUrl!!.queryParameter("s"))
        }
    }
    @Test fun coverArtAcceptsExactlyTwoMiBWithKnownAndUnknownLength() = runBlocking {
        MockWebServer().use { server ->
            val bytes = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            server.enqueue(MockResponse().setChunkedBody(Buffer().write(bytes), 8192))
            val client = client(server)
            repeat(2) { assertArrayEquals(bytes, client.coverArt("art")) }
        }
    }
    @Test fun coverArtRejectsOversizedKnownAndUnknownLengthWithoutLeakingCauses() = runBlocking {
        MockWebServer().use { server ->
            val bytes = ByteArray(2 * 1024 * 1024 + 1)
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            server.enqueue(MockResponse().setChunkedBody(Buffer().write(bytes), 8192))
            val client = client(server)
            repeat(2) {
                val error = rejects<NavidromeException> { runBlocking { client.coverArt("private-art") } }
                assertSafeArtworkError(error)
                assertFalse(error.toString().contains("private-art"))
            }
        }
    }
    @Test fun coverArtRejectsDeclaredOversizeBeforeConsumingBody() = runBlocking {
        MockWebServer().use { server ->
            // A huge declared body must fail on headers, not wait for body bytes/read timeout.
            server.enqueue(MockResponse().setBody("x").setHeader("Content-Length", 2 * 1024 * 1024 + 1)
                .setBodyDelay(2, TimeUnit.SECONDS))
            withTimeout(1000) {
                val error = rejects<NavidromeException> { client(server).coverArt("art") }
                assertSafeArtworkError(error)
            }
        }
    }
    @Test fun coverArtRejectsStatusesAndDoesNotFollowRedirects() = runBlocking {
        MockWebServer().use { server ->
            val client = client(server)
            listOf(302, 401, 403, 503).forEach { code ->
                server.enqueue(MockResponse().setResponseCode(code).addHeader("Location", server.url("/leak"))
                    .setBody("secret &雪 https://sensitive.example/?t=private-token"))
                val error = rejects<NavidromeException> { runBlocking { client.coverArt("art") } }
                assertSafeArtworkError(error)
                assertFalse(error.toString().contains("secret"))
                assertFalse(error.toString().contains("sensitive"))
                assertFalse(error.toString().contains("private-token"))
            }
            assertEquals(4, server.requestCount)
        }
    }
    @Test fun coverArtSanitizesTransportAndBodyReadFailures() = runBlocking {
        MockWebServer().use { server ->
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                throw IOException("private transport ${chain.request().url}")
            }.build()
            val client = NavidromeClient(credentials(server.url("/music/").toString()), http, allowLocalHttp = true)
            val transport = rejects<NavidromeException> { runBlocking { client.coverArt("private-art") } }
            assertSafeArtworkError(transport)
            assertFalse(transport.toString().contains("private"))
            assertFalse(transport.toString().contains("http"))

            server.enqueue(MockResponse().setBody("private body").setHeader("Content-Length", 100)
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END))
            val body = rejects<NavidromeException> { runBlocking { client(server).coverArt("private-art") } }
            assertSafeArtworkError(body)
            assertFalse(body.toString().contains("private"))
            assertFalse(body.toString().contains("http"))
        }
    }
    @Test fun coverArtCancellationCancelsCallDuringBodyConsumptionAndUsesTenSecondTimeout() = runBlocking {
        MockWebServer().use { server ->
            val reading = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            val timeout = AtomicLong()
            val http = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callStart(call: Call) { timeout.set(call.timeout().timeoutNanos()) }
                override fun responseBodyStart(call: Call) { reading.countDown() }
                override fun canceled(call: Call) { cancelled.countDown() }
            }).build()
            server.enqueue(MockResponse().setBody("x".repeat(100)).throttleBody(1, 200, TimeUnit.MILLISECONDS))
            val client = NavidromeClient(credentials(server.url("/music/").toString()), http, allowLocalHttp = true)
            val download = async { client.coverArt("art") }
            try {
                assertTrue(withContext(Dispatchers.IO) { reading.await(2, TimeUnit.SECONDS) })
                withTimeout(1000) { download.cancelAndJoin() }
                assertTrue(download.isCancelled)
                assertTrue(cancelled.await(1, TimeUnit.SECONDS))
                assertEquals(TimeUnit.SECONDS.toNanos(10), timeout.get())
            } finally { download.cancelAndJoin() }
        }
    }
    @Test fun redirectsAreNotFollowed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/leak")))
            rejects<NavidromeException> { runBlocking { client(server).ping() } }
            assertEquals(1, server.requestCount)
        }
    }
}
