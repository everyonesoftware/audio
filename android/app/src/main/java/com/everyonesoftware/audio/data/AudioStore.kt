package com.everyonesoftware.audio.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val Context.audioPreferences by preferencesDataStore(name = "audio_selection")

@Entity(tableName = "connections")
internal data class StoredConnection(@PrimaryKey val id: String, val metadata: String, val encryptedPassword: String)

@Entity(tableName = "sessions", foreignKeys = [ForeignKey(entity = StoredConnection::class,
    parentColumns = ["id"], childColumns = ["connectionId"], onDelete = ForeignKey.CASCADE)])
internal data class StoredSession(@PrimaryKey val connectionId: String, val payload: String)

@Entity(tableName = "playlist_cache", foreignKeys = [ForeignKey(entity = StoredConnection::class,
    parentColumns = ["id"], childColumns = ["connectionId"], onDelete = ForeignKey.CASCADE)])
internal data class StoredPlaylists(@PrimaryKey val connectionId: String, val payload: String)

@Dao
internal interface AudioDao {
    @Query("SELECT * FROM connections ORDER BY rowid") suspend fun connections(): List<StoredConnection>
    @Query("SELECT * FROM connections WHERE id = :id") suspend fun connection(id: String): StoredConnection?
    @Upsert suspend fun saveConnection(connection: StoredConnection)
    @Query("DELETE FROM connections WHERE id = :id") suspend fun removeConnection(id: String)
    @Upsert suspend fun saveSession(session: StoredSession)
    @Query("SELECT * FROM sessions WHERE connectionId = :id") suspend fun session(id: String): StoredSession?
    @Upsert suspend fun cachePlaylists(playlists: StoredPlaylists)
    @Query("SELECT * FROM playlist_cache WHERE connectionId = :id") suspend fun cachedPlaylists(id: String): StoredPlaylists?
}

@Database(entities = [StoredConnection::class, StoredSession::class, StoredPlaylists::class], version = 1, exportSchema = false)
internal abstract class AudioDatabase : RoomDatabase() {
    abstract fun audioDao(): AudioDao
}

/** Room stores metadata and encrypted envelopes only; preferences contain IDs, never secrets or URLs. */
class AudioStore(context: Context) {
    private val context = context.applicationContext
    private val dao = database(this.context).audioDao()
    private val preferences = this.context.audioPreferences
    private val secrets = CredentialStore(this.context)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun connections(): List<Connection> = lock.withLock {
        dao.connections().map { decode<Connection>(it.metadata) }
    }
    suspend fun saveConnection(credentials: Credentials) = lock.withLock {
        val connection = credentials.connection
        require(connection.id.isNotBlank()) { "A connection ID is required." }
        val safe = connection.copy(baseUrl = NavidromeClient.validateBaseUrl(
            connection.baseUrl, allowLocalHttp = com.everyonesoftware.audio.BuildConfig.DEBUG))
        val encrypted = withContext(Dispatchers.IO) { secrets.encrypt(connection.id, credentials.password) }
        // Upsert UPDATE (not REPLACE) preserves foreign-key children when passwords change.
        dao.saveConnection(StoredConnection(connection.id, json.encodeToString(safe), encrypted))
    }
    suspend fun credentials(id: String): Credentials? = lock.withLock {
        val stored = dao.connection(id) ?: return@withLock null
        val connection = decode<Connection>(stored.metadata)
        val password = withContext(Dispatchers.IO) { secrets.decrypt(id, stored.encryptedPassword) }
        Credentials(connection, password)
    }
    suspend fun saveSession(session: ListeningSession) = lock.withLock {
        requireConnection(session.connectionId)
        require(session.positionMs >= 0 && session.index >= 0 &&
            (session.queue.isEmpty() && session.index == 0 || session.index < session.queue.size)) { "Invalid listening position." }
        dao.saveSession(StoredSession(session.connectionId, json.encodeToString(session)))
    }
    suspend fun session(connectionId: String): ListeningSession? = lock.withLock {
        dao.session(connectionId)?.let { decode<ListeningSession>(it.payload) }
    }
    suspend fun selectedConnectionId(): String? = lock.withLock {
        preferences.data.first()[selectedKey]?.takeIf { dao.connection(it) != null }
    }
    suspend fun selectConnection(id: String) = lock.withLock {
        requireConnection(id)
        preferences.edit { it[selectedKey] = id }
        Unit
    }
    suspend fun selectedLibraries(connectionId: String): Set<String> = lock.withLock {
        if (dao.connection(connectionId) == null) emptySet()
        else preferences.data.first()[librariesKey(connectionId)]?.toSet() ?: emptySet()
    }
    suspend fun setSelectedLibraries(connectionId: String, ids: Set<String>) = lock.withLock {
        requireConnection(connectionId)
        preferences.edit { it[librariesKey(connectionId)] = ids.toSet() }
        Unit
    }
    suspend fun removeConnection(id: String) = lock.withLock {
        // Preferences first: a crash can unselect a retained connection, never select a removed one.
        preferences.edit {
            if (it[selectedKey] == id) it.remove(selectedKey)
            it.remove(librariesKey(id))
        }
        dao.removeConnection(id) // FK cascade removes sessions and cached playlists atomically.
    }
    suspend fun cachePlaylists(connectionId: String, playlists: List<Playlist>) = lock.withLock {
        requireConnection(connectionId)
        dao.cachePlaylists(StoredPlaylists(connectionId, json.encodeToString(playlists)))
    }
    suspend fun cachedPlaylists(connectionId: String): List<Playlist> = lock.withLock {
        dao.cachedPlaylists(connectionId)?.let { decode<List<Playlist>>(it.payload) } ?: emptyList()
    }
    private suspend fun requireConnection(id: String) {
        require(dao.connection(id) != null) { "This connection is unavailable. Reconnect to the server." }
    }
    private inline fun <reified T> decode(value: String): T = try { json.decodeFromString<T>(value) }
        catch (_: Exception) { throw IllegalStateException("Stored music data is unavailable. Reconnect or refresh your library.") }
    companion object {
        private val lock = Mutex()
        private val selectedKey = stringPreferencesKey("selected_connection")
        // An ID is data, not a path: DataStore keys safely retain arbitrary string IDs.
        private fun librariesKey(id: String) = stringSetPreferencesKey("libraries:$id")
        @Volatile private var instance: AudioDatabase? = null
        private fun database(context: Context): AudioDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context, AudioDatabase::class.java, "audio.db").build().also { instance = it }
        }
    }
}
