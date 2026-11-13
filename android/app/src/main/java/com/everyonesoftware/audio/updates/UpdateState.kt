package com.everyonesoftware.audio.updates

import com.everyonesoftware.audio.BuildConfig

enum class UpdateStage { IDLE, CHECKING, AVAILABLE, DOWNLOADING, VERIFYING, READY, ERROR }

data class UpdateUiState(
    val stage: UpdateStage = UpdateStage.IDLE,
    val automaticChecks: Boolean = true,
    val lastCheckedAt: Long? = null,
    val release: UpdateRelease? = null,
    val downloadedBytes: Long = 0,
    val error: String? = null,
    val installedVersion: String = BuildConfig.VERSION_NAME,
    val installPermissionRequired: Boolean = false,
)
