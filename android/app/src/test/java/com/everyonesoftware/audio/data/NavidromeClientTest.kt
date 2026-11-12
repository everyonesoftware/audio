package com.everyonesoftware.audio.data

import kotlinx.coroutines.runBlocking
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
    @Test fun redirectsAreNotFollowed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/leak")))
            rejects<NavidromeException> { runBlocking { client(server).ping() } }
            assertEquals(1, server.requestCount)
        }
    }
}
