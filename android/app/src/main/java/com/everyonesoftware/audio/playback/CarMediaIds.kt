package com.everyonesoftware.audio.playback

import java.util.Base64

/** Opaque, canonical, connection-scoped IDs; never accept a controller-supplied URL. */
internal object CarMediaIds {
    const val ROOT = "audio:root"
    const val PLAYLISTS = "audio:playlists"
    const val QUEUE = "audio:queue"
    data class Item(val kind: String, val connection: String, val parent: String, val index: Int? = null, val song: String? = null)

    fun playlist(connection: String, playlist: String) = encode("playlist", connection, playlist)
    fun shuffle(connection: String, playlist: String) = encode("shuffle", connection, playlist)
    fun track(connection: String, playlist: String, index: Int, song: String) = encode("track", connection, playlist) + ":$index:" + part(song)
    fun queued(connection: String, queueId: String, index: Int) = encode("queued", connection, queueId) + ":$index"

    fun parse(id: String): Item? = runCatching {
        require(id.length <= 16_384)
        val bits = id.split(':')
        require(bits.size in 4..6 && bits[0] == "audio")
        val kind = bits[1]
        val expectedSize = when (kind) { "playlist", "shuffle" -> 4; "queued" -> 5; "track" -> 6; else -> error("Unknown media ID") }
        require(bits.size == expectedSize)
        val index = if (bits.size >= 5) bits[4].toInt().also { require(it >= 0 && it.toString() == bits[4]) } else null
        Item(kind, unpart(bits[2]), unpart(bits[3]), index, if (kind == "track") unpart(bits[5]) else null)
    }.getOrNull()

    private fun encode(kind: String, connection: String, parent: String) = "audio:$kind:${part(connection)}:${part(parent)}"
    private fun part(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun unpart(value: String): String {
        val decoded = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
        require(decoded.isNotBlank() && part(decoded) == value)
        return decoded
    }
}
