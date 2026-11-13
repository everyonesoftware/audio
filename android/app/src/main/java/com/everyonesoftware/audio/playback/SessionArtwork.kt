package com.everyonesoftware.audio.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.everyonesoftware.audio.data.Credentials
import com.everyonesoftware.audio.data.NavidromeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext

/** One private download supplies a small embedded bitmap and a high-quality local URI. */
internal class SessionArtwork(private val client: NavidromeClient, credentials: Credentials) {
    private val owner = SessionArtworkStore.activate(credentials)

    fun cached(id: String): SessionArtworkImage? = SessionArtworkStore.cached(owner, id)
    fun close() = SessionArtworkStore.clear(owner)

    suspend fun load(id: String): SessionArtworkImage? {
        cached(id)?.let { return it }
        val bytes = client.coverArt(id)
        val images = withContext(Dispatchers.Default) { images(bytes) } ?: return null
        coroutineContext.ensureActive()
        return SessionArtworkStore.publish(owner, id, images.first, images.second)
    }

    private fun images(bytes: ByteArray): Pair<ByteArray, ByteArray>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..16384 || bounds.outHeight !in 1..16384 ||
            bounds.outWidth.toLong() * bounds.outHeight > 64_000_000) return null
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        // Decode at sufficient resolution for a 1024px cover without allocating the full original.
        while (longest / (sample * 2) >= 1024) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return try {
            val thumbnail = encode(decoded, 256, 80, 128 * 1024) ?: return null
            val full = encode(decoded, 1024, 90, 1024 * 1024) ?: return null
            thumbnail to full
        } finally {
            decoded.recycle()
        }
    }

    private fun encode(bitmap: Bitmap, side: Int, quality: Int, limit: Int): ByteArray? {
        val ratio = minOf(1f, side.toFloat() / maxOf(bitmap.width, bitmap.height))
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
        return try {
            ByteArrayOutputStream().use { output ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)) return null
                output.toByteArray().takeIf { it.size <= limit }
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }
}
