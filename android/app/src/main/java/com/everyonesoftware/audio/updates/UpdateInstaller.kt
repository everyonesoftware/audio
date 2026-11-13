package com.everyonesoftware.audio.updates

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.WorkerThread
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/**
 * Preflights an update, then prepares the system's user-confirmed installer; never installs silently.
 *
 * Call [verify] and [installIntent] on IO, serialized with the downloader. Only cache/updates/update.apk
 * is accepted. Preparing the intent repeats every check against the current installed package and
 * bytes; the caller must still gate the actual launch on user consent and its playback checkpoint.
 *
 * PackageManager supplies certificate metadata, not a standalone guarantee that every APK byte has
 * a valid signature. SHA-256 binds these bytes to the selected release, and current certificate DER
 * must match exactly (no signing-history/rotation acceptance). Android's package installer remains
 * the final cryptographic signature and install-policy authority. No apksig dependency is bundled.
 */
class UpdateInstaller internal constructor(
    context: Context,
    private val packageIdentity: (File?) -> UpdateApkIdentity
) {
    private val context = context.applicationContext
    private var verifiedRelease: UpdateRelease? = null

    constructor(context: Context) : this(context, { file -> readIdentity(context.applicationContext, file) })

    /** A failure invalidates earlier verification and exposes no paths, certificates or causes. */
    @WorkerThread
    @Synchronized
    fun verify(file: File, release: UpdateRelease) {
        verifiedRelease = null
        safely {
            val candidate = fixedApk(file)
            require(release.count > 0 && release.bytes > 0)
            require(Regex("[0-9a-fA-F]{64}").matches(release.sha256))
            require(candidate.isFile && candidate.length() == release.bytes)

            val installed = packageIdentity(null)
            val archive = packageIdentity(candidate)
            require(installed.packageName == context.packageName)
            require(archive.packageName == context.packageName)
            require(archive.versionCode == release.count.toLong())
            require(archive.versionCode > installed.versionCode)
            require(archive.versionName == release.version)
            require(!archive.debuggable || installed.debuggable)
            // Compare a sorted multiset of complete certificate encodings, never historical keys.
            require(currentSigners(archive) == currentSigners(installed))

            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            candidate.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    require(bytes <= release.bytes)
                    digest.update(buffer, 0, count)
                }
            }
            require(bytes == release.bytes && candidate.length() == release.bytes)
            require(hex(digest.digest()).equals(release.sha256, ignoreCase = true))
            require(fixedApk(file) == candidate)
            verifiedRelease = release
        }
    }

    /** Unknown-source approval is separate from approving this particular install. */
    fun canInstall(): Boolean = try {
        context.packageManager.canRequestPackageInstalls()
    } catch (_: Exception) {
        false
    }

    fun permissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Requires a successful [verify] on this helper, and rechecks it now. Does not launch anything.
     * The coordinator must not replace/delete the fixed file until the installer finishes reading it.
     */
    @WorkerThread
    @Synchronized
    fun installIntent(file: File): Intent = safely {
        val release = requireNotNull(verifiedRelease)
        verify(file, release)
        val candidate = fixedApk(file)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", candidate)
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri("Music update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun fixedApk(file: File): File {
        val root = context.cacheDir.canonicalFile
        val expected = File(root, "updates/update.apk")
        // Comparing to the un-resolved expected path also rejects an updates/ symlink escape.
        require(file.canonicalFile == expected)
        require(expected.canonicalFile == expected)
        return expected
    }

    private fun currentSigners(identity: UpdateApkIdentity): List<String> {
        require(identity.currentSigners.isNotEmpty())
        return identity.currentSigners.map { certificate ->
            require(certificate.isNotEmpty())
            hex(certificate)
        }.sorted()
    }

    private inline fun <T> safely(block: () -> T): T = try {
        block()
    } catch (_: Exception) {
        verifiedRelease = null
        throw IllegalArgumentException("The update could not be verified.")
    }

    internal companion object {
        private fun hex(bytes: ByteArray): String = bytes.joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }

        /** Null means the currently installed app, never a caller-selected installed package. */
        @Suppress("DEPRECATION")
        internal fun readIdentity(context: Context, file: File?): UpdateApkIdentity {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
            val info = requireNotNull(if (file == null) {
                context.packageManager.getPackageInfo(context.packageName, flags)
            } else {
                context.packageManager.getPackageArchiveInfo(file.path, flags)
            })
            val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // signingCertificateHistory deliberately NOT used: rotations are not supported.
                info.signingInfo?.apkContentsSigners
            } else {
                info.signatures
            }
            val appInfo = requireNotNull(info.applicationInfo)
            return UpdateApkIdentity(
                packageName = info.packageName,
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    info.versionCode.toLong()
                },
                versionName = info.versionName,
                debuggable = appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                currentSigners = requireNotNull(signers).map { it.toByteArray() }
            )
        }
    }
}

/** Internal identity seam lets policy tests vary metadata while real parser tests use a signed APK. */
internal data class UpdateApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val debuggable: Boolean,
    val currentSigners: List<ByteArray>
)
