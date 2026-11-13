package com.everyonesoftware.audio.updates

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class UpdateClientTest {
    private val payload = "signed apk fixture".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    private val base = "https://github.com/everyonesoftware/audio/releases/download/v0.1.42/"
    private fun manifest() = """{"count":42,"version":"0.1.42","tag":"v0.1.42","sha":"${"a".repeat(40)}","apk":"Music-0.1.42.apk","bytes":${payload.size},"sha256":"$hash"}"""
    private fun metadata(draft: Boolean = false, prerelease: Boolean = false) = """{"tag_name":"v0.1.42","draft":$draft,"prerelease":$prerelease,"published_at":"2026-01-01T00:00:00Z","body":"Release notes","assets":[{"name":"release.json","browser_download_url":"${base}release.json"},{"name":"Music-0.1.42.apk","browser_download_url":"${base}Music-0.1.42.apk","size":${payload.size}}]}"""
    private fun client(server: MockWebServer) = UpdateClient(UpdateTestConfig(server.url("/")))
    private fun response(body: String) = MockResponse().setBody(body)
    private suspend fun failure(block: suspend () -> Unit): UpdateException {
        try { block() } catch (e: UpdateException) {
            var cause: Throwable? = e
            while (cause != null) {
                assertEquals("Update unavailable. Please try again.", cause.message)
                assertTrue(cause is UpdateException)
                cause = cause.cause
            }
            return e
        }
        throw AssertionError("Expected safe update failure")
    }
    private suspend fun release(server: MockWebServer): UpdateRelease {
        server.enqueue(response(metadata())); server.enqueue(response(manifest()))
        return requireNotNull(client(server).latest(1))
    }
    private fun temporary() = File.createTempFile("music-update-", ".apk").also { it.delete() }

    @Test fun discoversExactPublishedReleaseAndSendsNoCredentials() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val found = release(server)
            assertEquals(UpdateRelease(42, "0.1.42", "Music-0.1.42.apk", payload.size.toLong(), hash, "${base}Music-0.1.42.apk", "Release notes"), found)
            assertEquals("/repos/everyonesoftware/audio/releases/latest", server.takeRequest().path)
            val manifestRequest = server.takeRequest()
            assertEquals("/everyonesoftware/audio/releases/download/v0.1.42/release.json", manifestRequest.path)
            assertNull(manifestRequest.getHeader("Authorization")); assertNull(manifestRequest.getHeader("Cookie"))
        }
    }
    @Test fun neverOffersEqualOrOlderBuild() = runBlocking<Unit> {
        MockWebServer().use { server ->
            for (installed in listOf(42L, 43L)) {
                server.enqueue(response(metadata())); assertNull(client(server).latest(installed))
            }
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun rejectsDraftPrereleaseAndUnpublishedRelease() = runBlocking<Unit> {
        MockWebServer().use { server ->
            for (body in listOf(metadata(draft = true), metadata(prerelease = true), metadata().replace("\"2026-01-01T00:00:00Z\"", "null"))) {
                server.enqueue(response(body)); failure { client(server).latest(1) }
            }
        }
    }
    @Test fun missingReleaseIsNotAnErrorButOtherStatusesAreSafe() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404)); assertNull(client(server).latest(1))
            for (status in listOf(204, 206, 401, 403, 429, 500)) {
                server.enqueue(MockResponse().setResponseCode(status).setBody("secret https://private.invalid/token"))
                failure { client(server).latest(1) }
            }
        }
    }
    @Test fun validatesManifestIdentityHashSizeAndCommit() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val invalid = listOf(
                manifest().replace("\"count\":42", "\"count\":41"),
                manifest().replace("0.1.42", "0.1.042"),
                manifest().replace("Music-0.1.42.apk", "../Music-0.1.42.apk"),
                manifest().replace(hash, "x".repeat(64)),
                manifest().replace("\"bytes\":${payload.size}", "\"bytes\":0"),
                manifest().replace("\"bytes\":${payload.size}", "\"bytes\":1073741825"),
                manifest().replace("a".repeat(40), "bad"),
                manifest().replace("\"count\":42", "\"count\":\"42\""), "{}", "not json")
            for (body in invalid) {
                server.enqueue(response(metadata())); server.enqueue(response(body)); failure { client(server).latest(1) }
            }
        }
    }
    @Test fun rejectsUntrustedOrAmbiguousAssetUrls() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val invalid = listOf(
                metadata().replace(base, "https://evil.invalid/"),
                metadata().replace(base, base.replace("everyonesoftware/audio", "someone/audio")),
                metadata().replace(base, base.replace("https:", "http:")),
                metadata().replace("${base}release.json", "${base}release.json?token=secret"),
                metadata().replace("\"name\":\"release.json\"", "\"name\":\"../release.json\""))
            for (body in invalid) { server.enqueue(response(body)); failure { client(server).latest(1) } }
        }
    }
    @Test fun boundsJsonAndNotes() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(response(" ".repeat(1024 * 1024 + 1))); failure { client(server).latest(1) }
            server.enqueue(response(metadata().replace("Release notes", "n".repeat(20000))))
            server.enqueue(response(manifest()))
            assertEquals(16000, client(server).latest(1)!!.notes.length)
            server.enqueue(response(metadata())); server.enqueue(response(" ".repeat(65537)))
            failure { client(server).latest(1) }
        }
    }
    @Test fun downloadsAndVerifiesSizeChecksumAndProgress() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val found = release(server); val target = temporary()
            try {
                server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
                val progress = mutableListOf<Pair<Long, Long>>()
                client(server).download(found, target) { received, total -> progress += received to total }
                assertArrayEquals(payload, target.readBytes())
                assertEquals(payload.size.toLong() to payload.size.toLong(), progress.last())
                assertTrue(progress.all { it.first <= it.second })
            } finally { target.delete() }
        }
    }
    @Test fun deletesCorruptTruncatedAndOversizedDownloads() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val found = release(server)
            for (body in listOf("x".repeat(payload.size), "short", "x".repeat(payload.size + 1))) {
                val target = temporary()
                server.enqueue(response(body).setChunkedBody(body, 3))
                failure { client(server).download(found, target) { _, _ -> } }
                assertFalse(target.exists())
            }
            val target = temporary()
            server.enqueue(response("short").setHeader("Content-Length", payload.size).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
            failure { client(server).download(found, target) { _, _ -> } }; assertFalse(target.exists())
        }
    }
    @Test fun downloadRejectsTamperedReleaseBeforeNetwork() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val found = release(server); val before = server.requestCount
            for (bad in listOf(found.copy(downloadUrl = "http://evil.invalid/secret"), found.copy(apk = "../evil.apk"), found.copy(bytes = -1), found.copy(sha256 = "bad"))) {
                failure { client(server).download(bad, temporary()) { _, _ -> } }
            }
            assertEquals(before, server.requestCount)
        }
    }
    @Test fun manualRedirectsAreBoundedAndRejectExternalAndInsecureTargets() = runBlocking<Unit> {
        MockWebServer().use { server ->
            for (location in listOf("https://evil.invalid/secret", "http://github.com/everyonesoftware/audio/releases/download/v0.1.42/release.json", "https://github.com.evil.invalid/x", "https://user:secret@github.com/x")) {
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", location))
                failure { client(server).latest(1) }
            }
            repeat(6) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/repos/everyonesoftware/audio/releases/latest")) }
            failure { client(server).latest(1) }
        }
    }
    @Test fun permitsCanonicalAssetRedirectToCdnButNotApiRedirectToCdn() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(response(metadata()))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://release-assets.githubusercontent.com/github-production-release-asset/fixture?sig=test"))
            server.enqueue(response(manifest()))
            assertEquals(42, client(server).latest(1)!!.count)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://objects.githubusercontent.com/fixture"))
            failure { client(server).latest(1) }
        }
    }
    @Test fun cancellationClosesCallAndRemovesPartialFile() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val found = release(server); val target = temporary()
            server.enqueue(response("x".repeat(payload.size)).throttleBody(1, 1, TimeUnit.SECONDS))
            val started = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.IO) { client(server).download(found, target) { n, _ -> if (n > 0) started.complete(Unit) } }
            withTimeout(3000) { started.await() }
            withTimeout(3000) { job.cancelAndJoin() }
            assertFalse(target.exists())
        }
    }
    @Test fun cancellationWhileWaitingForHeadersIsPrompt() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.IO) { client(server).latest(1) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
            withTimeout(3000) { job.cancelAndJoin() }
        }
    }
    @Test fun rejectsDownloadStatusesAndDeclaredOversizeWithoutKeepingFile() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val found = release(server)
            for (status in listOf(204, 206, 404, 403, 429, 500)) {
                server.enqueue(MockResponse().setResponseCode(status))
                val target = temporary()
                failure { client(server).download(found, target) { _, _ -> } }
                assertFalse(target.exists())
            }
            server.enqueue(response("x").setHeader("Content-Length", found.bytes + 1))
            val target = temporary()
            failure { client(server).download(found, target) { _, _ -> } }
            assertFalse(target.exists())
        }
    }
    @Test fun preservesPreexistingTarget() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val found = release(server)
            val target = temporary().also { it.writeText("keep existing") }
            try {
                failure { client(server).download(found, target) { _, _ -> } }
                assertEquals("keep existing", target.readText())
                assertEquals(2, server.requestCount)
            } finally { target.delete() }
        }
    }
    @Test fun verifiesDownloadAfterAllowedCdnRedirect() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val found = release(server); val target = temporary()
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "https://objects.githubusercontent.com/fixture?signature=test"))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
            try {
                client(server).download(found, target) { _, _ -> }
                assertArrayEquals(payload, target.readBytes())
            } finally { target.delete() }
        }
    }
    @Test fun offlineErrorsHaveNoTransportDetails() = runBlocking<Unit> {
        val server = MockWebServer(); server.start(); val client = client(server); server.shutdown()
        failure { client.latest(1) }
    }
}
