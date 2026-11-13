package com.everyonesoftware.audio.updates

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Validated release metadata. Download revalidates this public value before trusting it. */
data class UpdateRelease(
    val count: Int,
    val version: String,
    val apk: String,
    val bytes: Long,
    val sha256: String,
    val downloadUrl: String,
    val notes: String = "",
)

/** Deliberately carries neither a URL nor an underlying transport/parser exception. */
class UpdateException : IOException("Update unavailable. Please try again.")

/** JVM-only seam: canonical URLs are still validated, then transported to this exact loopback origin. */
internal data class UpdateTestConfig(val origin: HttpUrl)

/** Standalone, unauthenticated GitHub client. Never accepts an account's OkHttpClient. */
class UpdateClient private constructor(private val testConfig: UpdateTestConfig?) {
    constructor() : this(null)
    internal constructor(testConfig: UpdateTestConfig, @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit) : this(testConfig as UpdateTestConfig?)

    init {
        testConfig?.origin?.let {
            require(it.scheme == "http" && it.host in setOf("127.0.0.1", "localhost", "::1") &&
                it.username.isEmpty() && it.password.isEmpty() && it.encodedPath == "/" &&
                it.query == null && it.fragment == null) { "Invalid test origin" }
        }
    }

    private val http = OkHttpClient.Builder()
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES).cache(null)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = false }

    suspend fun latest(installedCode: Long): UpdateRelease? = safe {
        val document = fetch(LATEST, api = true, missingAllowed = true) { readBounded(it, MAX_RELEASE_JSON) }
            ?: return@safe null
        val published = json.decodeFromString<PublishedRelease>(document.toString(Charsets.UTF_8))
        check(!published.draft && !published.prerelease && !published.publishedAt.isNullOrBlank())
        val count = tagCount(published.tag)
        // A valid older stable tag cannot become an available downgrade.
        if (count.toLong() <= installedCode) return@safe null
        val prefix = assetPrefix(count)
        val manifestAsset = published.assets.single { it.name == "release.json" }
        check(manifestAsset.url == prefix + "release.json")
        val apkName = "Music-0.1.$count.apk"
        val apkAsset = published.assets.single { it.name == apkName }
        check(apkAsset.url == prefix + apkName)
        val manifestBytes = requireNotNull(fetch(manifestAsset.url, api = false) { readBounded(it, MAX_MANIFEST_JSON) })
        val manifestObject = json.parseToJsonElement(manifestBytes.toString(Charsets.UTF_8)) as JsonObject
        // JSON numeric strings are accepted by some decoders; the release schema requires numbers.
        for (field in listOf("count", "bytes")) {
            val value = manifestObject[field] as JsonPrimitive
            check(!value.isString && Regex("[1-9][0-9]*").matches(value.content))
        }
        val manifest = json.decodeFromJsonElement<Manifest>(manifestObject)
        check(manifest.tag == published.tag && manifest.count == count)
        check(Regex("[0-9a-f]{40}").matches(manifest.sha))
        val release = UpdateRelease(manifest.count, manifest.version, manifest.apk, manifest.bytes,
            manifest.sha256, apkAsset.url, published.body.orEmpty().take(MAX_NOTES))
        validate(release)
        check(apkAsset.size == release.bytes)
        release
    }

    /** Writes only to a new coordinator-owned temporary file; never replaces an existing file. */
    suspend fun download(release: UpdateRelease, target: File, onProgress: (Long, Long) -> Unit) {
        var owned = false
        try {
            safe {
                validate(release)
                withContext(Dispatchers.IO) {
                    check(target.createNewFile())
                    owned = true
                }
                requireNotNull(fetch(release.downloadUrl, api = false) { response ->
                    val body = requireNotNull(response.body)
                    val declared = body.contentLength()
                    check(declared == -1L || declared == release.bytes)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var received = 0L
                    target.outputStream().use { output ->
                        body.byteStream().use { input ->
                            onProgress(0, release.bytes)
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                check(received + read <= release.bytes)
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                received += read
                                onProgress(received, release.bytes)
                            }
                        }
                    }
                    check(received == release.bytes)
                    val expected = release.sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    check(MessageDigest.isEqual(expected, digest.digest()))
                    Unit
                })
                currentCoroutineContext().ensureActive()
            }
        } catch (error: Throwable) {
            if (owned) target.delete()
            throw error
        }
    }

    private fun validate(release: UpdateRelease) {
        check(release.count in 1..2_100_000_000)
        check(release.version == "0.1.${release.count}")
        check(release.apk == "Music-${release.version}.apk")
        check(release.bytes in 1..MAX_APK_BYTES)
        check(Regex("[0-9a-f]{64}").matches(release.sha256))
        check(release.downloadUrl == assetPrefix(release.count) + release.apk)
    }

    private fun tagCount(tag: String): Int {
        check(Regex("v0\\.1\\.[1-9][0-9]{0,9}").matches(tag))
        return requireNotNull(tag.removePrefix("v0.1.").toIntOrNull()).also { check(it <= 2_100_000_000) }
    }

    private fun readBounded(response: Response, maximum: Int): ByteArray {
        val body = requireNotNull(response.body)
        check(body.contentLength() <= maximum)
        return body.byteStream().use { input ->
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                check(result.size() + read <= maximum)
                result.write(buffer, 0, read)
            }
            result.toByteArray()
        }
    }

    /** Redirects are checked on logical HTTPS URLs before any test transport rewriting. */
    private suspend fun <T : Any> fetch(
        initial: String,
        api: Boolean,
        missingAllowed: Boolean = false,
        consume: (Response) -> T,
    ): T? {
        var url = initial.toHttpUrl()
        repeat(MAX_REDIRECTS + 1) { hop ->
            checkAllowed(url, api)
            val transport = testConfig?.origin?.newBuilder()
                ?.encodedPath(url.encodedPath)?.encodedQuery(url.encodedQuery)?.build() ?: url
            val request = Request.Builder().url(transport)
                .header("Accept", if (api) "application/vnd.github+json" else "application/octet-stream")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "Music-Android-Updater")
                .build()
            val result = exchange(request) { response ->
                when {
                    response.code in REDIRECT_STATUSES -> {
                        check(hop < MAX_REDIRECTS)
                        val next = requireNotNull(url.resolve(requireNotNull(response.header("Location"))))
                        checkAllowed(next, api)
                        FetchResult<T>(next = next)
                    }
                    missingAllowed && hop == 0 && response.code == 404 -> FetchResult<T>(missing = true)
                    else -> {
                        check(response.code == 200)
                        check(response.header("Content-Encoding").let { it == null || it.equals("identity", true) })
                        FetchResult(value = consume(response))
                    }
                }
            }
            if (result.missing) return null
            result.value?.let { return it }
            url = requireNotNull(result.next)
        }
        error("Redirect limit")
    }

    private fun checkAllowed(url: HttpUrl, api: Boolean) {
        check(url.scheme == "https" && url.port == 443 && url.username.isEmpty() &&
            url.password.isEmpty() && url.fragment == null)
        if (api) {
            // The fixed /latest endpoint must never turn into a different repo/API request.
            check(url.toString() == LATEST)
        } else {
            check(url.host in ASSET_HOSTS)
            if (url.host == "github.com") {
                check(url.query == null)
                check(Regex("/everyonesoftware/audio/releases/download/v0\\.1\\.[1-9][0-9]{0,9}/(?:release\\.json|Music-0\\.1\\.[1-9][0-9]{0,9}\\.apk)").matches(url.encodedPath))
            }
        }
    }

    /** A cancellation child cancels the actual socket, including a blocked response-body read.
     * coroutineScope waits for IO and file cleanup before returning cancellation to the caller. */
    private suspend fun <T> exchange(request: Request, consume: (Response) -> T): T = coroutineScope {
        val call = http.newCall(request)
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine<Nothing> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
            }
        }
        try {
            withContext(Dispatchers.IO) { call.execute().use(consume) }
        } finally {
            cancellation.cancelAndJoin()
        }
    }

    private suspend fun <T> safe(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        throw UpdateException()
    }

    private data class FetchResult<T>(val value: T? = null, val next: HttpUrl? = null, val missing: Boolean = false)
    @Serializable private data class PublishedRelease(
        @kotlinx.serialization.SerialName("tag_name") val tag: String,
        val draft: Boolean,
        val prerelease: Boolean,
        @kotlinx.serialization.SerialName("published_at") val publishedAt: String?,
        val body: String? = null,
        val assets: List<Asset>,
    )
    @Serializable private data class Asset(
        val name: String,
        @kotlinx.serialization.SerialName("browser_download_url") val url: String,
        val size: Long? = null,
    )
    @Serializable private data class Manifest(
        val count: Int, val version: String, val tag: String, val sha: String,
        val apk: String, val sha256: String, val bytes: Long,
    )

    private companion object {
        const val LATEST = "https://api.github.com/repos/everyonesoftware/audio/releases/latest"
        const val MAX_RELEASE_JSON = 1024 * 1024
        const val MAX_MANIFEST_JSON = 64 * 1024
        const val MAX_NOTES = 16000
        const val MAX_APK_BYTES = 150L * 1024 * 1024
        const val MAX_REDIRECTS = 5
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        val ASSET_HOSTS = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
        fun assetPrefix(count: Int) = "https://github.com/everyonesoftware/audio/releases/download/v0.1.$count/"
    }
}
