plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// CI supplies the full-history count; local builds derive the same value from Git.
fun gitText(vararg arguments: String): String? {
    val output = providers.exec {
        commandLine("git", *arguments)
        workingDir(rootProject.rootDir)
        isIgnoreExitValue = true
    }
    return if (output.result.get().exitValue == 0) output.standardOutput.asText.get().trim() else null
}
val countText = providers.gradleProperty("audioCommitCount").orNull ?: run {
    require(gitText("rev-parse", "--is-shallow-repository") != "true") { "Versioning requires full Git history; fetch with depth 0." }
    gitText("rev-list", "--count", "HEAD") ?: "0" // Unborn/exported developer tree only.
}
require(Regex("0|[1-9][0-9]*").matches(countText)) { "audioCommitCount must be a non-negative decimal integer." }
val commitCount = requireNotNull(countText.toLongOrNull()?.takeIf { it <= 2_100_000_000L }) { "Commit count exceeds Android's versionCode range." }
val releaseKeyPath = providers.environmentVariable("AUDIO_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("AUDIO_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("AUDIO_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("AUDIO_KEY_PASSWORD").orNull
val signingValues = listOf(releaseKeyPath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
val releaseSigningConfigured = signingValues.all { !it.isNullOrEmpty() }
require(signingValues.all { it.isNullOrEmpty() } || releaseSigningConfigured) { "Set all four AUDIO release-signing environment variables, or none for debug builds." }

android {
    namespace = "com.everyonesoftware.audio"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.everyonesoftware.audio"
        minSdk = 26
        targetSdk = 36
        versionCode = commitCount.coerceAtLeast(1).toInt()
        versionName = "0.1.$commitCount"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }
    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(requireNotNull(releaseKeyPath))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

val validateReleaseSigning by tasks.registering {
    doLast {
        check(commitCount > 0) { "Release builds need a positive committed Git count (audioCommitCount)." }
        check(releaseSigningConfigured) { "Release signing is required. Set AUDIO_KEYSTORE_PATH, AUDIO_KEYSTORE_PASSWORD, AUDIO_KEY_ALIAS and AUDIO_KEY_PASSWORD." }
        check(file(requireNotNull(releaseKeyPath)).isFile) { "The release keystore file is missing." }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(validateReleaseSigning) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.11.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.8.0")
    implementation("androidx.room:room-runtime:2.8.3")
    implementation("androidx.room:room-ktx:2.8.3")
    ksp("androidx.room:room-compiler:2.8.3")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
