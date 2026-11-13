package com.everyonesoftware.audio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.everyonesoftware.audio.updates.UpdateStage
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** A quiet header affordance, never a banner over the music library. */
@Composable
internal fun UpdateNotificationIcon(onClick: () -> Unit) {
    IconButton(onClick, Modifier.sizeIn(minWidth = 48.dp, minHeight = 52.dp)) {
        Box(Modifier.size(28.dp)) {
            Icon(Icons.Default.Download, "Update available", Modifier.size(24.dp).align(Alignment.Center))
            Box(Modifier.size(7.dp).align(Alignment.TopEnd).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
    }
}

/** Presentation only: checking/downloading never issues a player command. */
@Composable
internal fun Updates(state: AudioUiState, actions: AudioUiActions) {
    val update = state.updates
    val release = update.release
    val busy = update.stage in listOf(UpdateStage.CHECKING, UpdateStage.DOWNLOADING, UpdateStage.VERIFYING)
    // A resumed player or changed release invalidates any previous install consent.
    var confirmInstall by rememberSaveable(release?.count, update.stage, state.playing, update.installPermissionRequired) { mutableStateOf(false) }
    var sourceExpanded by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ actions.showScreen(state.updatesReturnScreen) }, Modifier.sizeIn(minWidth = 48.dp, minHeight = 52.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Column {
                Text("Settings", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Updates", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Installed version ${update.installedVersion}", style = MaterialTheme.typography.titleMedium)
            UpdateHint(update.lastCheckedAt?.let { "Last checked ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))}" } ?: "Not checked yet")
            OutlinedButton(actions::checkForUpdates, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("updates-check"), enabled = !busy, shape = RoundedCornerShape(12.dp)) {
                Text(if (update.stage == UpdateStage.CHECKING) "Checking for updates…" else "Check now")
            }
            if (update.stage == UpdateStage.CHECKING) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                UpdateHint("You can keep listening while Music checks.")
            }
            if (update.stage == UpdateStage.IDLE && update.lastCheckedAt != null && release == null) {
                Text("Music is up to date", style = MaterialTheme.typography.titleMedium)
            }
            if (release != null) {
                HorizontalDivider()
                Text(if (update.stage == UpdateStage.READY) "Ready to install" else "A new version is available", style = MaterialTheme.typography.titleLarge)
                Text("Version ${release.version} · ${updateSize(release.bytes)}", style = MaterialTheme.typography.titleMedium)
            }
            if (update.error != null) {
                Text(update.error, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            when (update.stage) {
                UpdateStage.AVAILABLE, UpdateStage.ERROR -> if (release != null) {
                    UpdateButton(if (update.stage == UpdateStage.ERROR) "Retry download" else "Download update", "updates-download", actions::downloadUpdate)
                    UpdateHint("Downloading does not interrupt playback. Installation is a separate step.")
                }
                UpdateStage.DOWNLOADING -> {
                    val total = release?.bytes ?: 0L
                    val downloaded = update.downloadedBytes.coerceAtLeast(0L).let { if (total > 0) it.coerceAtMost(total) else it }
                    if (total > 0) {
                        val fraction = (downloaded.toDouble() / total).toFloat().coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().testTag("updates-progress"))
                        Text("${updateSize(downloaded)} of ${updateSize(total)} · ${(fraction * 100).toInt()}%", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Downloading update…")
                    }
                    OutlinedButton(actions::cancelUpdateDownload, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("updates-cancel"), shape = RoundedCornerShape(12.dp)) { Text("Cancel download") }
                }
                UpdateStage.VERIFYING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Verifying download…", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    UpdateHint("Checking the file before making it available to install.")
                }
                UpdateStage.READY -> if (release != null) {
                    UpdateHint("Installation closes Music. Your queue and position are preserved, and playback stays paused when you reopen the app.")
                    if (state.playing) {
                        UpdateHint("Pause playback before continuing. The update will not pause your music automatically.")
                        UpdateButton("Pause playback", "updates-pause", actions::pauseForUpdate)
                    } else {
                        if (update.installPermissionRequired) UpdateHint("Android needs your permission for Music to install updates. Allow this in system settings, return here, then tap Install update again.")
                        UpdateButton(if (update.installPermissionRequired) "Allow installations" else "Install update", "updates-install") { confirmInstall = true }
                    }
                }
                else -> Unit
            }
            if (release != null && release.notes.isNotBlank()) {
                Text("What’s new", style = MaterialTheme.typography.titleMedium)
                // Plain text only; release notes cannot launch links or execute markup.
                Text(release.notes, style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("updates-automatic")
                .toggleable(update.automaticChecks, role = Role.Switch, onValueChange = actions::setAutomaticUpdates), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Automatic update checks", style = MaterialTheme.typography.titleMedium)
                    UpdateHint("Check when you open Music, at most once a day.")
                }
                Switch(update.automaticChecks, onCheckedChange = null)
            }
            UpdateHint("Checks contact GitHub, not your music server. No server credentials are sent. Downloads and installation require your approval.")
            TextButton({ sourceExpanded = !sourceExpanded }, Modifier.testTag("updates-source")) { Text(if (sourceExpanded) "Hide source details" else "Source and verification details") }
            if (sourceExpanded) {
                UpdateHint("Updates come from this app’s GitHub releases. Downloads are checked for the expected size and SHA-256 checksum, then Android verifies the app identity and signing certificate before installation.")
                UpdateHint("Automatic checks do not automatically download or install anything. You can turn checks off and use Check now instead.")
            }
        }
    }
    if (confirmInstall && update.stage == UpdateStage.READY && !state.playing && release != null) {
        AlertDialog(
            onDismissRequest = { confirmInstall = false },
            title = { Text(if (update.installPermissionRequired) "Allow installations?" else "Install Music ${release.version}?") },
            text = { Text(if (update.installPermissionRequired)
                "Android settings will open so you can allow Music to install updates. Return here afterward and tap Install update again. This does not install the update."
            else "Music will close during installation. Your queue and position are preserved, and playback stays paused. Android will ask you to confirm the update.") },
            confirmButton = {
                TextButton({
                    confirmInstall = false
                    // Recheck the latest state; consent cannot bypass resumed playback.
                    if (!state.playing && update.stage == UpdateStage.READY) actions.installUpdate()
                }, Modifier.testTag("updates-confirm-install")) { Text(if (update.installPermissionRequired) "Open Android settings" else "Continue to Android") }
            },
            dismissButton = { TextButton({ confirmInstall = false }) { Text("Not now") } },
        )
    }
}

@Composable
private fun UpdateHint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun UpdateButton(label: String, tag: String, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) { Text(label) }
}

private fun updateSize(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes.coerceAtLeast(0L).toDouble() / 1_000_000.0)
