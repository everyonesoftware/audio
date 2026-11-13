package com.everyonesoftware.audio.updates

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateInstallerTest {
    private lateinit var context: Context
    private lateinit var apk: File
    private lateinit var release: UpdateRelease
    private lateinit var archive: UpdateApkIdentity
    private lateinit var installed: UpdateApkIdentity

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        apk = File(context.cacheDir, "updates/update.apk")
        apk.parentFile!!.mkdirs()
        File(context.applicationInfo.sourceDir).copyTo(apk, overwrite = true)
        archive = UpdateInstaller.readIdentity(context, apk)
        installed = archive.copy(versionCode = archive.versionCode - 1)
        release = UpdateRelease(
            count = archive.versionCode.toInt(), version = requireNotNull(archive.versionName),
            apk = "Music-fixture.apk", bytes = apk.length(), sha256 = digest(apk),
            downloadUrl = "https://example.invalid/Music-fixture.apk", notes = "Fixture only"
        )
    }

    @After fun tearDown() { apk.delete() }

    private fun installer(
        installedIdentity: UpdateApkIdentity = installed,
        archiveIdentity: UpdateApkIdentity = archive
    ) = UpdateInstaller(context) { file -> if (file == null) installedIdentity else archiveIdentity }

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected update rejection")
        } catch (error: IllegalArgumentException) {
            assertEquals("The update could not be verified.", error.message)
            assertNull(error.cause)
        }
    }

    @Test fun realInstalledArchiveParsesWithCurrentSignersAndIsNotAnUpgrade() {
        assertEquals(context.packageName, archive.packageName)
        assertTrue(archive.currentSigners.isNotEmpty())
        assertTrue(archive.currentSigners.all { it.isNotEmpty() })
        assertEquals(
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            archive.debuggable
        )
        rejects { UpdateInstaller(context).verify(apk, release) }
    }

    @Test fun realArchivePassesAgainstLowerInstalledVersionUsingRealPackageParser() {
        val helper = UpdateInstaller(context) { file ->
            if (file == null) installed else UpdateInstaller.readIdentity(context, file)
        }
        helper.verify(apk, release)
    }

    @Test fun rejectsSizeAndHashMismatchAndMalformedHash() {
        rejects { installer().verify(apk, release.copy(bytes = release.bytes + 1)) }
        rejects { installer().verify(apk, release.copy(sha256 = "0".repeat(64))) }
        rejects { installer().verify(apk, release.copy(sha256 = "not-a-digest")) }
        rejects { installer().verify(apk, release.copy(bytes = 0)) }
    }

    @Test fun rejectsWrongPackageAndMetadataMismatch() {
        rejects { installer(archiveIdentity = archive.copy(packageName = "other.package")).verify(apk, release) }
        rejects { installer().verify(apk, release.copy(count = release.count + 1)) }
        rejects { installer().verify(apk, release.copy(version = "wrong-version")) }
        rejects { installer(installedIdentity = archive).verify(apk, release) }
        rejects { installer(installedIdentity = archive.copy(versionCode = archive.versionCode + 1)).verify(apk, release) }
    }

    @Test fun requiresExactCurrentSignerMultisetNotLineageOrSubset() {
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5, 6)
        val multiple = installed.copy(currentSigners = listOf(first, second))
        installer(multiple, archive.copy(currentSigners = listOf(second, first))).verify(apk, release)
        rejects { installer(multiple, archive.copy(currentSigners = listOf(first))).verify(apk, release) }
        rejects { installer(multiple, archive.copy(currentSigners = listOf(first, first))).verify(apk, release) }
        rejects { installer(archiveIdentity = archive.copy(currentSigners = listOf(second))).verify(apk, release) }
        rejects { installer(archiveIdentity = archive.copy(currentSigners = emptyList())).verify(apk, release) }
        rejects { installer(archiveIdentity = archive.copy(currentSigners = listOf(byteArrayOf()))).verify(apk, release) }
        rejects { installer(installedIdentity = installed.copy(currentSigners = emptyList())).verify(apk, release) }
    }

    @Test fun productionInstallRejectsDebugArchiveButDebugInstallCanTestDebugUpdates() {
        rejects {
            installer(installed.copy(debuggable = false), archive.copy(debuggable = true)).verify(apk, release)
        }
        installer(installed.copy(debuggable = true), archive.copy(debuggable = true)).verify(apk, release)
        installer(installed.copy(debuggable = false), archive.copy(debuggable = false)).verify(apk, release)
    }

    @Test fun rejectsNonFixedCachePathsAndSymlinkEscape() {
        val outside = File(context.cacheDir, "outside-update.apk")
        val alternate = File(apk.parentFile, "alternate.apk")
        val link = File(apk.parentFile, "link.apk")
        try {
            apk.copyTo(outside, overwrite = true)
            apk.copyTo(alternate, overwrite = true)
            rejects { installer().verify(outside, release) }
            rejects { installer().verify(alternate, release) }
            android.system.Os.symlink(outside.path, link.path)
            rejects { installer().verify(link, release) }
            apk.delete()
            android.system.Os.symlink(outside.path, apk.path)
            rejects { installer().verify(apk, release) }
        } finally {
            link.delete()
            apk.delete()
            outside.delete()
            alternate.delete()
        }
    }

    @Test fun malformedArchiveAndMissingFileFailSafely() {
        apk.writeText("not an APK")
        rejects { UpdateInstaller(context).verify(apk, release.copy(bytes = apk.length(), sha256 = digest(apk))) }
        apk.delete()
        rejects { installer().verify(apk, release) }
    }

    @Test fun installIntentRequiresVerificationAndRechecksBytesAfterVerification() {
        val helper = installer()
        rejects { helper.installIntent(apk) }
        helper.verify(apk, release)
        apk.appendBytes(byteArrayOf(0))
        rejects { helper.installIntent(apk) }
        // A failed attempt must invalidate the earlier verification, even if the file is restored.
        File(context.applicationInfo.sourceDir).copyTo(apk, overwrite = true)
        rejects { helper.installIntent(apk) }
    }

    @Test fun preparingInstallRejectsSameSizeMutationAndSanitizesParserFailures() {
        val helper = installer()
        helper.verify(apk, release)
        java.io.RandomAccessFile(apk, "rw").use { file ->
            val first = file.readByte()
            file.seek(0)
            file.writeByte(first.toInt() xor 1)
        }
        assertEquals(release.bytes, apk.length())
        rejects { helper.installIntent(apk) }
        File(context.applicationInfo.sourceDir).copyTo(apk, overwrite = true)
        val broken = UpdateInstaller(context) { throw SecurityException("private path and signer details") }
        rejects { broken.verify(apk, release) }
    }

    @Test fun preparingInstallRechecksArchiveAndInstalledPackageIdentity() {
        var currentArchive = archive
        var currentInstalled = installed
        val helper = UpdateInstaller(context) { file -> if (file == null) currentInstalled else currentArchive }
        helper.verify(apk, release)
        currentArchive = archive.copy(currentSigners = listOf(byteArrayOf(9)))
        rejects { helper.installIntent(apk) }
        currentArchive = archive
        helper.verify(apk, release)
        currentInstalled = archive
        rejects { helper.installIntent(apk) }
    }

    @Test fun installerIntentGrantsOnlyReadAccessToNarrowContentUri() {
        val helper = installer()
        helper.verify(apk, release)
        val intent = helper.installIntent(apk)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("${context.packageName}.updates", intent.data!!.authority)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(intent.data, intent.clipData!!.getItemAt(0).uri)
        context.contentResolver.openInputStream(intent.data!!)!!.use { input ->
            assertEquals(release.sha256, digestBytes(input.readBytes()))
        }
        val provider = context.packageManager.resolveContentProvider("${context.packageName}.updates", 0)!!
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
    }

    @Test fun permissionIntentTargetsOnlyThisAppAndPermissionMatchesPlatform() {
        val helper = UpdateInstaller(context)
        assertEquals(context.packageManager.canRequestPackageInstalls(), helper.canInstall())
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, helper.permissionIntent().action)
        assertEquals(Uri.parse("package:${context.packageName}"), helper.permissionIntent().data)
    }

    private fun digest(file: File): String = digestBytes(file.readBytes())
    private fun digestBytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
