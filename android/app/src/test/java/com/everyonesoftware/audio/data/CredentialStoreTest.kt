package com.everyonesoftware.audio.data

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class CredentialStoreTest {
    private fun store() = CredentialStore { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
    @Test fun encryptsWithRandomIvAndAuthenticatesOwner() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = CredentialStore { key }
        val a = store.encrypt("a", "private-password")
        val b = store.encrypt("a", "private-password")
        assertNotEquals(a, b)
        assertFalse(a.contains("private-password"))
        assertEquals("private-password", store.decrypt("a", a))
        try { store.decrypt("b", a); fail("Wrong owner must fail") } catch (_: CredentialStoreException) { }
    }
    @Test fun rejectsCorruptionAndLostKeyWithoutLeakingSecrets() {
        val store = store()
        val encrypted = store.encrypt("id", "private-password")
        listOf("", "v2:invalid", encrypted.dropLast(8), encrypted.replaceRange(encrypted.length / 2, encrypted.length / 2 + 1, "!")).forEach {
            try { store.decrypt("id", it); fail("Invalid envelope must fail") } catch (e: CredentialStoreException) {
                assertFalse(e.toString().contains("private-password")); assertNull(e.cause)
            }
        }
        try { store().decrypt("id", encrypted); fail("Lost key must fail") } catch (_: CredentialStoreException) { }
    }
}
