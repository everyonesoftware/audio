package com.everyonesoftware.audio.updates

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Independent of the music account and player. Requests are serialized, never owned by a screen. */
class UpdateCoordinator(
    private val scope: CoroutineScope,
    private val installedCode: Long,
    private val directory: File,
    private val latest: suspend (Long) -> UpdateRelease?,
    private val download: suspend (UpdateRelease, File, (Long, Long) -> Unit) -> Unit,
    private val verify: suspend (File, UpdateRelease) -> Unit,
    initialState: UpdateUiState = UpdateUiState(),
    initialLastAttemptAt: Long? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val persist: (UpdateUiState, Long?) -> Unit = { _, _ -> },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val initialRelease = initialState.release?.takeIf { it.count > installedCode }
    private val current = MutableStateFlow(initialState.copy(release = initialRelease,
        stage = if (initialRelease != null) UpdateStage.AVAILABLE else UpdateStage.IDLE, error = null))
    val state = current.asStateFlow()
    private var request: Job? = null
    private var automaticRequest = false
    private var lastAttemptAt = initialLastAttemptAt
    @Volatile private var generation = 0L
    private var installationOpen = false

    fun check(automatic: Boolean = false): Job? {
        if (installationOpen || request?.isCompleted == false || current.value.stage in listOf(UpdateStage.DOWNLOADING, UpdateStage.VERIFYING)) return request
        val time = now()
        if (automatic && (!current.value.automaticChecks || lastAttemptAt?.let { time >= it && time - it < DAY_MS } == true)) return null
        val ticket = ++generation
        val before = current.value
        lastAttemptAt = time
        automaticRequest = automatic
        current.update { it.copy(stage = UpdateStage.CHECKING, error = null) }
        save()
        request = scope.launch {
            try {
                val found = latest(installedCode)?.takeIf { it.count > installedCode }
                if (ticket == generation) {
                    current.update { it.copy(release = found, lastCheckedAt = now(), error = null,
                        downloadedBytes = if (found == before.release && before.stage == UpdateStage.READY) before.downloadedBytes else 0,
                        stage = when { found == null -> UpdateStage.IDLE; found == before.release && before.stage == UpdateStage.READY -> UpdateStage.READY; else -> UpdateStage.AVAILABLE }) }
                    save()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (ticket == generation) current.update { it.copy(stage = if (before.stage == UpdateStage.READY) UpdateStage.READY else UpdateStage.ERROR,
                    error = "Unable to check for updates. Check your connection and try again.") }
            } finally {
                if (ticket == generation && current.value.stage == UpdateStage.CHECKING) current.value = before.copy(automaticChecks = current.value.automaticChecks)
            }
        }
        return request
    }

    fun setAutomaticChecks(enabled: Boolean) {
        current.update { it.copy(automaticChecks = enabled) }
        if (!enabled && automaticRequest && current.value.stage == UpdateStage.CHECKING) {
            ++generation
            request?.cancel()
            current.update { it.copy(stage = if (it.release != null) UpdateStage.AVAILABLE else UpdateStage.IDLE) }
        }
        save()
    }

    fun download(): Job? {
        if (installationOpen || request?.isCompleted == false || current.value.stage !in listOf(UpdateStage.AVAILABLE, UpdateStage.ERROR)) return request
        val release = current.value.release?.takeIf { it.count > installedCode } ?: return null
        val ticket = ++generation
        current.update { it.copy(stage = UpdateStage.DOWNLOADING, downloadedBytes = 0, error = null) }
        request = scope.launch {
            val temporary = File(directory, "${UUID.randomUUID()}.part")
            val candidate = File(directory, "update.apk")
            var promoted = false
            try {
                withContext(io) {
                    check(directory.isDirectory || directory.mkdirs())
                    directory.listFiles()?.filter { it.extension == "part" && it != temporary }?.forEach { it.delete() }
                    // Cached bytes survive process death, but must pass all checks again before use.
                    val cached = if (candidate.isFile) {
                        try { verify(candidate, release); true }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { check(candidate.delete()); false }
                    } else false
                    if (!cached) {
                        download(release, temporary) { received, _ ->
                            current.update { if (ticket == generation) it.copy(downloadedBytes = received.coerceIn(0, release.bytes)) else it }
                        }
                        currentCoroutineContext().ensureActive()
                        current.update { if (ticket == generation) it.copy(stage = UpdateStage.VERIFYING) else it }
                        check(!candidate.exists())
                        check(temporary.renameTo(candidate))
                        promoted = true
                        verify(candidate, release)
                    }
                    currentCoroutineContext().ensureActive()
                }
                if (ticket == generation) current.update { it.copy(stage = UpdateStage.READY, downloadedBytes = release.bytes, error = null) }
            } catch (cancelled: CancellationException) {
                if (promoted) candidate.delete()
                throw cancelled
            } catch (_: Exception) {
                if (promoted) candidate.delete()
                if (ticket == generation) current.update { it.copy(stage = UpdateStage.ERROR, downloadedBytes = 0,
                    error = "The update could not be downloaded or verified. Nothing was installed. Try again.") }
            } finally {
                temporary.delete()
                if (ticket == generation && current.value.stage in listOf(UpdateStage.DOWNLOADING, UpdateStage.VERIFYING)) {
                    current.update { it.copy(stage = UpdateStage.AVAILABLE, downloadedBytes = 0) }
                }
            }
        }
        return request
    }

    fun cancelDownload() {
        if (current.value.stage != UpdateStage.DOWNLOADING) return
        ++generation
        request?.cancel()
        current.update { it.copy(stage = UpdateStage.AVAILABLE, downloadedBytes = 0, error = null) }
    }

    /** Freezes all replacement/cleanup until Android's installer has returned to the activity. */
    fun beginInstall(): Pair<File, UpdateRelease>? {
        if (installationOpen || request?.isCompleted == false || current.value.stage != UpdateStage.READY) return null
        val release = current.value.release ?: return null
        installationOpen = true
        return File(directory, "update.apk") to release
    }

    fun installerReturned() { installationOpen = false }
    fun permissionRequired(required: Boolean) { current.update { it.copy(installPermissionRequired = required) } }
    fun installFailed() {
        installationOpen = false
        current.update { it.copy(stage = UpdateStage.ERROR,
            error = "Unable to open the installer. Recheck the download and try again.") }
    }
    private fun save() { persist(current.value, lastAttemptAt) }

    private companion object { const val DAY_MS = 86_400_000L }
}
