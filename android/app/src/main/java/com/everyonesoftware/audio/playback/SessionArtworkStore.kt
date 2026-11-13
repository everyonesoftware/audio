package com.everyonesoftware.audio.playback

import android.net.Uri
import com.everyonesoftware.audio.BuildConfig
import com.everyonesoftware.audio.data.Credentials
import java.util.UUID

/** The thumbnail is treated as immutable by session consumers. Neither field contains server URLs. */
internal data class SessionArtworkImage(val thumbnail: ByteArray, val uri: Uri)

/** Process-memory only. Revocation affects future reads, not descriptors already handed to a caller. */
internal object SessionArtworkStore {
    private const val MAX_ENTRIES = 4
    private const val MAX_THUMBNAIL_BYTES = 128 * 1024
    private const val MAX_FULL_BYTES = 1024 * 1024
    private val authority = "${BuildConfig.APPLICATION_ID}.artwork"
    private val path = Regex("/art/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg")
    private data class Entry(val image: SessionArtworkImage, val full: ByteArray)
    private val entries = LinkedHashMap<String, Entry>(MAX_ENTRIES, 0.75f, true)
    private var activeCredentials: Credentials? = null
    private var activeOwner: String? = null

    @Synchronized fun activate(credentials: Credentials): String {
        entries.clear()
        activeCredentials = credentials
        return UUID.randomUUID().toString().also { activeOwner = it }
    }

    @Synchronized fun cached(owner: String, id: String): SessionArtworkImage? {
        if (owner != activeOwner) return null
        return entries[id]?.image
    }

    @Synchronized fun publish(owner: String, id: String, thumbnail: ByteArray, full: ByteArray): SessionArtworkImage? {
        if (owner != activeOwner || thumbnail.isEmpty() || thumbnail.size > MAX_THUMBNAIL_BYTES ||
            full.isEmpty() || full.size > MAX_FULL_BYTES) return null
        val uri = Uri.parse("content://$authority/art/${UUID.randomUUID()}.jpg")
        val image = SessionArtworkImage(thumbnail, uri)
        // Snapshot the full payload: even a caller retaining its input cannot change issued content.
        entries[id] = Entry(image, full.copyOf())
        if (entries.size > MAX_ENTRIES) {
            entries.entries.iterator().apply { next(); remove() }
        }
        return image
    }

    @Synchronized fun clear(owner: String) {
        if (owner != activeOwner) return
        entries.clear()
        activeCredentials = null
        activeOwner = null
    }

    @Synchronized fun read(uri: Uri, currentCredentials: Credentials?): ByteArray? {
        if (activeOwner == null || currentCredentials == null || currentCredentials != activeCredentials) return null
        if (uri.scheme != "content" || uri.encodedAuthority != authority ||
            uri.encodedQuery != null || uri.encodedFragment != null || !path.matches(uri.encodedPath.orEmpty())) return null
        // Match the entire issued URI, not decoded segments or a caller-provided filesystem path.
        val id = entries.entries.firstOrNull { it.value.image.uri.toString() == uri.toString() }?.key ?: return null
        return entries[id]?.full?.copyOf() // URI reads count as use in the same thumbnail/full LRU.
    }
}
