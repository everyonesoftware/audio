package com.everyonesoftware.audio.playback

import android.Manifest
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.Settings
import com.everyonesoftware.audio.AudioApplication
import java.io.FileNotFoundException
import java.io.IOException

/** Exported for trusted media consumers, never for general-purpose URL or filesystem access. */
class ArtworkContentProvider : ContentProvider() {
    // Providers are created before Application.onCreate. Do not initialize the lazy media graph here.
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? {
        enforceTrustedCaller()
        return if (readCurrent(uri) != null) "image/jpeg" else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Artwork is read-only")
        enforceTrustedCaller()
        val bytes = readCurrent(uri) ?: throw FileNotFoundException("Artwork is unavailable")
        // openPipeHelper dispatches the writer to Android's worker executor. Writing a full cover
        // on the calling/binder/Main thread could deadlock before the caller gets the read end.
        return openPipeHelper(uri, "image/jpeg", null, bytes) { output, _, _, _, data ->
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(output).use { it.write(data) }
            } catch (_: IOException) {
                // A consumer may close the pipe without reading it. use() still closes our end.
            }
        }
    }

    private fun readCurrent(uri: Uri): ByteArray? {
        val app = context?.applicationContext as? AudioApplication ?: return null
        return SessionArtworkStore.read(uri, app.graph.credentials.value)
    }

    private fun enforceTrustedCaller() {
        val context = context ?: throw SecurityException("Artwork caller is not trusted")
        // These are the binder identity, NOT a package, UID or attribution supplied in a URI.
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        val allowed = try {
            artworkCallerIsTrusted(uid, pid, Process.myUid(), context.packageManager::getPackagesForUid) { packageName, actualPid, actualUid ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    context.getSystemService(MediaSessionManager::class.java)?.isTrustedForMediaControl(
                        MediaSessionManager.RemoteUserInfo(packageName, actualPid, actualUid)
                    ) == true
                } else {
                    // The platform trust API starts at 28. On 26/27 match its privileged-control
                    // or enabled-notification-listener policy, after resolving package from UID.
                    context.checkPermission(Manifest.permission.MEDIA_CONTENT_CONTROL, actualPid, actualUid) == PackageManager.PERMISSION_GRANTED ||
                        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                            ?.split(':')?.any { ComponentName.unflattenFromString(it)?.packageName == packageName } == true
                }
            }
        } catch (_: SecurityException) {
            false
        }
        if (!allowed) throw SecurityException("Artwork caller is not trusted")
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
        throw UnsupportedOperationException("Artwork does not expose a database")

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Artwork is read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Artwork is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Artwork is read-only")
}

/** Pure policy seam; production obtains UID/PID from Binder and packages exclusively from PM. */
internal fun artworkCallerIsTrusted(
    uid: Int,
    pid: Int,
    ownUid: Int,
    packagesForUid: (Int) -> Array<String>?,
    trustedForMediaControl: (String, Int, Int) -> Boolean,
): Boolean = uid == ownUid || packagesForUid(uid)?.any { trustedForMediaControl(it, pid, uid) } == true
