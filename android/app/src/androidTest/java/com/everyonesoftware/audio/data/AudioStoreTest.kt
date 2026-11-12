package com.everyonesoftware.audio.data

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Requires a real Android device/emulator: Room, DataStore and AndroidKeyStore. */
class AudioStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun credentialsAndMetadataSurviveNewStoreInstance() = runBlocking {
        val id = UUID.randomUUID().toString()
        val connection = Connection(id, "Private Music", "https://example.com/music/", "listener")
        val store = AudioStore(context)
        try {
            store.saveConnection(Credentials(connection, "test-secret-$id"))
            val reopened = AudioStore(context)
            assertEquals(connection, reopened.connections().single { it.id == id })
            assertEquals(Credentials(connection, "test-secret-$id"), reopened.credentials(id))
        } finally { store.removeConnection(id) }
    }
    @Test fun sessionsLibrariesAndPlaylistCacheAreConnectionScoped() = runBlocking {
        val id = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val store = AudioStore(context)
        try {
            listOf(id, other).forEach { store.saveConnection(Credentials(Connection(it, it, "https://example.com/", "u"), "p")) }
            val session = ListeningSession(id, "p", "Playlist", listOf(Song("s", "Track", "Artist")), positionMs = 1234)
            store.saveSession(session)
            store.setSelectedLibraries(id, setOf("42", "music"))
            store.cachePlaylists(id, listOf(Playlist("p", "Playlist", 1)))
            store.selectConnection(id)
            val reopened = AudioStore(context)
            assertEquals(session, reopened.session(id))
            assertNull(reopened.session(other))
            assertEquals(setOf("42", "music"), reopened.selectedLibraries(id))
            assertTrue(reopened.selectedLibraries(other).isEmpty())
            assertEquals(listOf(Playlist("p", "Playlist", 1)), reopened.cachedPlaylists(id))
            assertTrue(reopened.cachedPlaylists(other).isEmpty())
            assertEquals(id, reopened.selectedConnectionId())
            reopened.removeConnection(id)
            assertNull(reopened.credentials(id)); assertNull(reopened.session(id))
            assertNull(reopened.selectedConnectionId())
            assertTrue(reopened.cachedPlaylists(id).isEmpty()); assertTrue(reopened.selectedLibraries(id).isEmpty())
            assertNotNull(reopened.credentials(other))
        } finally { store.removeConnection(id); store.removeConnection(other) }
    }
    @Test fun credentialReplacementPreservesSessionAndStoresNoPlaintextSecret() = runBlocking {
        val id = UUID.randomUUID().toString()
        val connection = Connection(id, "Music", "https://example.com/", "u")
        val password = "unique-secret-$id"
        val store = AudioStore(context)
        try {
            store.saveConnection(Credentials(connection, password))
            val session = ListeningSession(id, "p", "P", listOf(Song("s", "T", "A")))
            store.saveSession(session)
            store.saveConnection(Credentials(connection, "replacement-$password"))
            assertEquals(session, store.session(id))
            assertEquals("replacement-$password", store.credentials(id)!!.password)
            context.openOrCreateDatabase("audio.db", android.content.Context.MODE_PRIVATE, null).use { db ->
                db.rawQuery("SELECT metadata, encryptedPassword FROM connections WHERE id = ?", arrayOf(id)).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertFalse(cursor.getString(0).contains(password))
                    assertFalse(cursor.getString(1).contains(password))
                    assertTrue(cursor.getString(1).startsWith("v1:"))
                }
                db.execSQL("UPDATE connections SET encryptedPassword = ? WHERE id = ?", arrayOf("v1:invalid:invalid", id))
            }
            try { store.credentials(id); fail("Corrupt credentials must not fabricate a password") }
            catch (expected: CredentialStoreException) { assertNull(expected.cause) }
        } finally { store.removeConnection(id) }
    }
    @Test fun debugFixtureCanPersistLoopbackConnection() = runBlocking {
        val id = UUID.randomUUID().toString()
        val store = AudioStore(context)
        val connection = Connection(id, "Local test fixture", "http://127.0.0.1:18921/music/", "fixture")
        try {
            store.saveConnection(Credentials(connection, "fixture-only"))
            assertEquals(connection, store.credentials(id)!!.connection)
        } finally { store.removeConnection(id) }
    }
    @Test fun missingCredentialNeverProducesFabricatedPassword() = runBlocking {
        assertNull(AudioStore(context).credentials(UUID.randomUUID().toString()))
    }
    @Test fun encryptedCredentialEnvelopeIsBoundToItsConnection() {
        val store = CredentialStore(context)
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        val ciphertext = store.encrypt(a, "never-plaintext")
        assertFalse(ciphertext.contains("never-plaintext"))
        assertEquals("never-plaintext", CredentialStore(context).decrypt(a, ciphertext))
        try {
            store.decrypt(b, ciphertext)
            fail("Ciphertext must be authenticated to its connection ID")
        } catch (expected: CredentialStoreException) {
            assertFalse(expected.toString().contains("never-plaintext"))
        }
    }
}
