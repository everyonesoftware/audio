package com.everyonesoftware.audio.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialStoreException : IllegalStateException("Stored credentials are unavailable. Reconnect to this server.")

/** AES/GCM authenticated envelopes; connection ID is authenticated, never a filename or alias. */
internal class CredentialStore internal constructor(private val keyProvider: () -> SecretKey) {
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this({ androidKey() })

    fun encrypt(connectionId: String, password: String): String = safely {
        require(password.toByteArray(Charsets.UTF_8).size <= 16_384)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        cipher.updateAAD(connectionId.toByteArray(Charsets.UTF_8))
        "v1:" + encode(cipher.iv) + ":" + encode(cipher.doFinal(password.toByteArray(Charsets.UTF_8)))
    }
    fun decrypt(connectionId: String, envelope: String): String = safely {
        require(envelope.length <= 32_768)
        val parts = envelope.split(':')
        require(parts.size == 3 && parts[0] == "v1")
        val iv = Base64.getDecoder().decode(parts[1])
        val ciphertext = Base64.getDecoder().decode(parts[2])
        require(iv.size == 12 && ciphertext.size >= 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, iv))
        cipher.updateAAD(connectionId.toByteArray(Charsets.UTF_8))
        cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }
    private inline fun <T> safely(block: () -> T): T = try { block() } catch (_: Exception) { throw CredentialStoreException() }
    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    companion object {
        private const val ALIAS = "com.everyonesoftware.audio.credentials.v1"
        @Synchronized private fun androidKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(ALIAS)) return store.getKey(ALIAS, null) as SecretKey
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            }.generateKey()
        }
    }
}
