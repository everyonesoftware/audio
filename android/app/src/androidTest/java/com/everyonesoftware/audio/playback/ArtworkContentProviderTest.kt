package com.everyonesoftware.audio.playback

import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.everyonesoftware.audio.AudioApplication
import com.everyonesoftware.audio.data.Connection
import com.everyonesoftware.audio.data.Credentials
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real resolver reads in the app UID; not an external media host/Android Auto test. */
@RunWith(AndroidJUnit4::class)
class ArtworkContentProviderTest {
    private val app get() = ApplicationProvider.getApplicationContext<AudioApplication>()
    private val resolver get() = app.contentResolver
    private lateinit var credentials: Credentials
    private lateinit var owner: String
    private var previousConnection: String? = null
    private val connections = mutableListOf<String>()

    @Before fun activate() = runBlocking {
        app.graph.bootstrap()
        previousConnection = app.graph.credentials.value?.connection?.id?.takeIf { id ->
            app.graph.store.connections().any { it.id == id }
        }
        credentials = newAccount()
        app.graph.activate(credentials.connection.id)
        owner = SessionArtworkStore.activate(credentials)
    }

    private suspend fun newAccount(): Credentials {
        val value = Credentials(Connection(UUID.randomUUID().toString(), "Artwork provider fixture",
            "https://artwork.invalid/private-server/", "private-user"), "private-password")
        app.graph.store.saveConnection(value)
        connections += value.connection.id
        return value
    }

    @After fun cleanup() = runBlocking {
        if (::owner.isInitialized) SessionArtworkStore.clear(owner)
        connections.forEach { app.graph.store.removeConnection(it) }
        previousConnection?.let { app.graph.activate(it) }
        Unit // Keep the JUnit @After method's JVM return type void even with no prior account.
    }

    private fun publish(id: String = "secret-cover-id", full: ByteArray = byteArrayOf(4, 5, 6)) =
        requireNotNull(SessionArtworkStore.publish(owner, id, byteArrayOf(1, 2, 3), full))

    private fun bytes(uri: Uri) = requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }

    private inline fun <reified T : Throwable> rejects(block: () -> Unit) {
        try { block(); fail("Expected ${T::class.java.simpleName}") }
        catch (error: Throwable) { if (error !is T) throw error }
    }

    @Test fun resolverReadsFullImageAndMimeWithoutLeakingIdentity() {
        val image = publish()
        assertArrayEquals(byteArrayOf(1, 2, 3), image.thumbnail)
        assertArrayEquals(byteArrayOf(4, 5, 6), bytes(image.uri))
        assertEquals("image/jpeg", resolver.getType(image.uri))
        assertTrue(image.uri.toString().matches(Regex("content://${app.packageName}\\.artwork/art/[0-9a-f-]{36}\\.jpg")))
        listOf("secret-cover-id", "private-user", "private-password", "private-server", credentials.connection.id).forEach {
            assertFalse(image.uri.toString().contains(it))
        }
        val provider = app.packageManager.resolveContentProvider("${app.packageName}.artwork", PackageManager.GET_META_DATA)!!
        assertTrue(provider.exported)
        assertFalse(provider.grantUriPermissions)
    }

    @Test fun malformedUnknownAndExpiredUrisCannotBeRead() {
        val image = publish()
        val invalid = listOf(
            Uri.parse("${image.uri}?"), Uri.parse("${image.uri}?x=1"), Uri.parse("${image.uri}#"),
            Uri.parse("${image.uri}#fragment"), Uri.parse("${image.uri}/extra"),
            Uri.parse(image.uri.toString().replace("/art/", "/art/%2e%2e/")),
            Uri.parse(image.uri.toString().replace("/art/", "/%61rt/")),
            Uri.parse("content://${app.packageName}.artwork/art/${UUID.randomUUID()}.jpg"),
        )
        invalid.forEach { uri ->
            rejects<FileNotFoundException> { bytes(uri) }
            assertNull(resolver.getType(uri))
            assertNull(SessionArtworkStore.read(uri, credentials))
        }
        assertNull(SessionArtworkStore.read(Uri.parse(image.uri.toString().replace("content:", "https:")), credentials))
        assertNull(SessionArtworkStore.read(Uri.parse(image.uri.toString().replace(".artwork/", ".artwork.evil/")), credentials))
        SessionArtworkStore.clear(owner)
        rejects<FileNotFoundException> { bytes(image.uri) }
        assertNull(resolver.getType(image.uri))
    }

    @Test fun writesAndDatabaseOperationsAreRejected() {
        val image = publish()
        listOf("w", "rw", "wa", "rwt").forEach { mode ->
            rejects<SecurityException> { resolver.openFileDescriptor(image.uri, mode)?.close() }
        }
        rejects<UnsupportedOperationException> { resolver.insert(image.uri, ContentValues()) }
        rejects<UnsupportedOperationException> { resolver.update(image.uri, ContentValues(), null, null) }
        rejects<UnsupportedOperationException> { resolver.delete(image.uri, null, null) }
        rejects<UnsupportedOperationException> { resolver.query(image.uri, null, null, null, null)?.close() }
        assertArrayEquals(byteArrayOf(4, 5, 6), bytes(image.uri))
    }

    @Test fun credentialSwitchRejectsOldUriEvenBeforeStoreReactivation() = runBlocking {
        val image = publish()
        assertNull(SessionArtworkStore.read(image.uri, null))
        assertNull(SessionArtworkStore.read(image.uri, credentials.copy(password = "changed")))
        val next = newAccount()
        app.graph.activate(next.connection.id)
        rejects<FileNotFoundException> { bytes(image.uri) }
        assertNull(resolver.getType(image.uri))
        val obsoleteOwner = owner
        owner = SessionArtworkStore.activate(next)
        assertNull(SessionArtworkStore.cached(obsoleteOwner, "secret-cover-id"))
        assertNull(SessionArtworkStore.publish(obsoleteOwner, "late", byteArrayOf(1), byteArrayOf(2)))
        val fresh = publish()
        SessionArtworkStore.clear(obsoleteOwner)
        assertArrayEquals(byteArrayOf(4, 5, 6), bytes(fresh.uri))
        assertNotEquals(image.uri, fresh.uri)
    }

    @Test fun fourEntryLruKeepsThumbnailAndUriTogetherAndReadRefreshesRecency() {
        val first = publish("first")
        val second = publish("second")
        publish("third"); publish("fourth")
        assertEquals(first, SessionArtworkStore.cached(owner, "first"))
        publish("fifth")
        assertNull(SessionArtworkStore.cached(owner, "second"))
        rejects<FileNotFoundException> { bytes(second.uri) }
        assertArrayEquals(byteArrayOf(4, 5, 6), bytes(first.uri))
        val replacement = publish("first", byteArrayOf(9))
        assertNotEquals(first.uri, replacement.uri)
        rejects<FileNotFoundException> { bytes(first.uri) }
        assertArrayEquals(byteArrayOf(9), bytes(replacement.uri))
        assertEquals(replacement, SessionArtworkStore.cached(owner, "first"))
        // 'third' is now oldest; reading it must protect it from eviction.
        val third = SessionArtworkStore.cached(owner, "third")!!
        val fourth = SessionArtworkStore.cached(owner, "fourth")!!
        val fifth = SessionArtworkStore.cached(owner, "fifth")!!
        bytes(third.uri); bytes(fourth.uri); bytes(fifth.uri); bytes(replacement.uri); bytes(third.uri)
        publish("sixth")
        rejects<FileNotFoundException> { bytes(fourth.uri) }
        assertArrayEquals(byteArrayOf(4, 5, 6), bytes(third.uri))
    }

    @Test fun emptyAndOversizedPayloadsDoNotReplaceValidEntry() {
        val original = publish()
        val cases = listOf(ByteArray(0) to byteArrayOf(1), byteArrayOf(1) to ByteArray(0),
            ByteArray(128 * 1024 + 1) to byteArrayOf(1), byteArrayOf(1) to ByteArray(1024 * 1024 + 1))
        cases.forEach { (thumb, full) ->
            assertNull(SessionArtworkStore.publish(owner, "secret-cover-id", thumb, full))
            assertEquals(original, SessionArtworkStore.cached(owner, "secret-cover-id"))
        }
        val maximum = ByteArray(1024 * 1024) { (it % 127).toByte() }
        val image = SessionArtworkStore.publish(owner, "maximum", ByteArray(128 * 1024), maximum)!!
        val executor = Executors.newSingleThreadExecutor()
        try {
            // Opening on Main must return without trying to write 1 MiB synchronously into the pipe.
            val opened = executor.submit<android.os.ParcelFileDescriptor> {
                var descriptor: android.os.ParcelFileDescriptor? = null
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    descriptor = resolver.openFileDescriptor(image.uri, "r")
                }
                requireNotNull(descriptor)
            }.get(5, TimeUnit.SECONDS)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(opened).use {
                assertArrayEquals(maximum, it.readBytes())
            }
        } finally { executor.shutdownNow() }
        val read = SessionArtworkStore.read(image.uri, credentials)!!
        read[0] = 99
        assertEquals(0.toByte(), bytes(image.uri)[0])
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 28)
    @Test fun privilegedExternalUidCanReadThroughTheExportedProvider() {
        val image = publish()
        val shell = app.packageManager.getApplicationInfo("com.android.shell", 0)
        assertNotEquals(android.os.Process.myUid(), shell.uid)
        val manager = app.getSystemService(android.media.session.MediaSessionManager::class.java)
        assertTrue("The isolated emulator shell is a trusted media controller", manager.isTrustedForMediaControl(
            android.media.session.MediaSessionManager.RemoteUserInfo("com.android.shell", -1, shell.uid)))
        // This command executes in a different UID and reaches the provider across Binder,
        // unlike the ordinary same-UID resolver tests. The URI is generated locally, not input.
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("content read --uri ${image.uri}")
        val bytes = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        assertArrayEquals(byteArrayOf(4, 5, 6), bytes)
    }

    @Test fun trustPolicyFailsClosedWithoutUidPackagesAndUsesActualIdentity() {
        var queriedUid: Int? = null
        val checked = mutableListOf<Triple<String, Int, Int>>()
        fun allowed(uid: Int, packages: Array<String>?, trusted: Boolean) = artworkCallerIsTrusted(
            uid, 123, 42, { queriedUid = it; packages }, { name, pid, actualUid ->
                checked += Triple(name, pid, actualUid); trusted
            })
        assertTrue(allowed(42, null, false))
        assertNull(queriedUid)
        assertFalse(allowed(99, null, true))
        assertFalse(allowed(99, emptyArray(), true))
        assertFalse(allowed(99, arrayOf("real.uid.package"), false))
        assertEquals(99, queriedUid)
        assertEquals(Triple("real.uid.package", 123, 99), checked.single())
        assertTrue(allowed(99, arrayOf("real.uid.package"), true))
    }
}
