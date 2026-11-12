# Audio Android — native alpha

An experimental Kotlin/Jetpack Compose client, not a WebView wrapper. Application ID: `com.everyonesoftware.audio`; version `0.1.<Git commit count>`; minimum Android API 26; compile/target API 36. Before the first commit, local debug builds use `0.1.0`. The current UI follows the readable, playlist-first v5 draft. Its colors are provisional.

## Current slice

- Welcome → connect with an existing Navidrome username/password → accessible libraries → playlists → full player.
- Direct Subsonic-compatible requests to the configured server, including base paths. No Audio account/backend/proxy.
- Playlist search and a direct Shuffle action. Refresh puts the most recently played playlist first. The phone UI does not yet implement playlist inspection or a longer recent-playlist history; the car browser supports playlist inspection.
- Media3/ExoPlayer owned by `PlaybackService`, with a media session, audio-focus handling and audio-becoming-noisy handling. UI controllers do not own or duplicate players.
- A persisted queue contains every playlist occurrence, its exact order, connection, playlist, current index, last committed position, repeat mode, shuffle flag and queue identity. A fresh Shuffle creates a new queue identity even if its track order happens to match the previous one.
- A cold launch restores the full player **paused**. Reopening while the service is already playing reconnects without stopping it. Progress is checkpointed approximately every second and on transport/lifecycle events; an abrupt kill can lose an uncommitted checkpoint, not generate a new shuffle.
- Shuffle-on reshuffles the visible queue around the current song. Shuffle-off retains the existing visible order; this alpha does not reconstruct the playlist's original unshuffled order.
- The title-side rating trigger opens five large choices. `setRating` is followed by `getSong`; only the server-confirmed value becomes local state. Failures retain the last confirmed value. Clear sends zero. Responses are bound to the captured account/song, not whichever song is visible when a request completes.
- Settings supports adding/switching connections, changing accessible-library selections and opening Navidrome administration in the browser. Library preferences are local browsing preferences, not permissions, server folder management, playlist filters or changes to an existing queue. Album/all-song browsing, local account removal UI and playlist editing are not implemented.

## Android Auto: first integration, not yet host-validated

The manifest declares media support, and the existing `MediaLibraryService` serves a shallow Playlists / Queue tree. Android Auto supplies the actual browsing and playback UI; this is not a custom car activity or the HTML mockup embedded in a WebView.

- Playlist rows are browsable. Opening one does not replace or start playback. Its contents include **Shuffle and play**, followed by every song occurrence in server order. Selecting a song starts that playlist in server order at the chosen occurrence.
- A custom **Shuffle and play** browse action is also advertised on playlist rows for hosts that support it. Hosts without that affordance can use the playable action inside the playlist. Actual host placement and navigation back to Now Playing remain unverified.
- The current playlist sorts first; this is not a complete listening-history implementation. Car and phone share one player and one persisted queue. Queue occurrence IDs include the connection, queue identity, and index, so duplicate songs remain distinct and stale queue selections cannot target a replacement queue.
- Browser subscriptions receive queue-change notifications after a phone selection. Library/network failures return safe errors; reloading the requested list retries the request without replacing the listening session.
- Voice search matches playlist names: a unique exact name wins, otherwise a unique partial match is required. Ambiguous or missing matches fail without picking arbitrary music. An empty playback query resumes an existing session. Artist, album, and song search are not implemented. The native voice intent is handled on first creation or a new intent, not replayed on recreation.
- Cold browser connection restores the stored queue/index/position/repeat state paused. An explicit playback command resumes it; merely browsing never autoplays or reshuffles. This is a local controller contract, not a claim about the host's own launch/autoplay policy.
- Server/account setup and five-star ratings stay on the phone. Authenticated artwork is currently phone-only; this slice does **not** send signed artwork URLs into session metadata. Authenticated car artwork and a car-player shuffle-toggle action remain deferred; starting a shuffled playlist and changing shuffle on the phone are supported.

No personal account, physical phone, Android Auto app, Desktop Head Unit, or vehicle was used. Next verification is an Android Auto host/DHU trial, including real controller trust, browse-action placement, day/night presentation, interruptions, reconnect behavior, and production-server streaming. Do not interpret passing browser callbacks as passing host certification or Play distribution review.

Implementation follows the pinned Media3 1.8.0 APIs. References: [Serve content](https://developer.android.com/media/media3/session/serve-content), [Android for Cars media](https://developer.android.com/media/implement/surfaces/cars), and the [1.8.0 session source](https://github.com/androidx/media/tree/1.8.0/libraries/session/src/main/java/androidx/media3/session).

## Build

Prerequisites: **JDK 21**, Android SDK platform 36 and build tools 36.0.0. Point `JAVA_HOME` at JDK 21. In Android Studio, select JDK 21 for Gradle; an existing bundled Java 25 is not a compatible substitute for this pinned Gradle 8.13 build.

Open this `android/` directory in Android Studio. Set `ANDROID_HOME` to the local SDK, or create an ignored `local.properties` containing `sdk.dir=<local SDK path>`. No SDK paths, signing keys or credentials belong in Git.

From `android/`:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

On Windows cmd/PowerShell use `gradlew.bat` instead of `./gradlew`. The wrapper pins the Gradle distribution SHA-256. The APK is `app/build/outputs/apk/debug/app-debug.apk` and uses a local **debug** signing key. Signed release builds and GitHub publication are configured separately in [RELEASING.md](RELEASING.md); persistent signing secrets are required, and no automatic in-app updater is implemented.

## Native verification

Use an explicitly chosen, task-owned emulator, not a personal device. After building both APKs:

```sh
python ../scripts/test-android-device.py --serial emulator-5580
```

Set `ANDROID_HOME` or pass `--adb <adb executable>`. An optional `--class <fully qualified class or class#method>` filters tests. The runner refuses non-emulator serials, installs the two APKs, and requires an actual JUnit `OK` summary: `am instrument` can exit zero even when assertions fail. Results are saved under the root's ignored `test-results/`.

Verified on an isolated API 36 Google APIs x86_64 emulator:

- **24 JVM tests**: protocol/authentication, safe errors, rating verification, encryption envelopes, queue rules, and canonical connection/occurrence media IDs.
- **41 Android instrumentation tests**: real Keystore/Room/DataStore, native Compose screens/actions, connection view model, session scoping, service-controller connection, and an end-to-end production-activity flow against a local HTTP/audio fixture, plus native media-browser, voice-intent, queue-subscription, stale-request and paused-restore checks.
- The end-to-end test signs actual requests, discovers a fixture library/playlist, decodes and streams a fixture WAV through ExoPlayer while the activity is backgrounded, writes and reads back a rating, seeks, pauses, recreates the activity, and verifies persisted order/position/rating.
- A separate two-phase force-stop probe preserved that fixture's session, stopped the package, and launched the production activity in a new process. The complete saved session was restored unchanged and paused. The fixture server was deliberately unavailable for this second phase; this verifies restoration, not offline audio playback.
- Android lint passes with **zero errors** and warnings (mostly available dependency/target updates, the intentional exported media service and a KTX style suggestion). No baseline or lint-error suppression was used to turn failures green.
- All preserved browser mockup suites pass independently. They do not stand in for native audio tests.

`NativeFlowTest` has optional instrumentation arguments `preserveSession=true` and `coldRestoreOnly=true` for the two-phase probe. Run only that class for each phase, with `adb -s <owned emulator> shell am force-stop com.everyonesoftware.audio` between phases. Preserve mode leaves **test-only** credentials/session until the cold probe's cleanup. Normal suite runs always clean up their fixture account. Emulator screenshots and the expected-session JSON are written under the test installation's external `files/evidence/`; none is a production demo or real account.

`AndroidAutoTest` exercises both Media3 and platform-legacy browsing, real fixture WAV playback, one-tap custom shuffle, queue occurrence selection, playlist inspection, name-based voice requests, native voice-intent recreation, duplicate-name refusal, network retry, subscription refresh, and a delayed car response racing a newer phone queue. A saved-account/no-session voice-launch regression gates the server response until initialization finishes, verifying startup refresh cannot cancel explicit playback. It rejects arbitrary URI replacements and stale queue IDs. These are native controller tests, not an Android Auto host simulation.

`AutoRestoreTest` can also run in two instrumentation processes: run only that class with `-e seedAutoRestore true`, force-stop the package, then run it with `-e verifyAutoRestore true`. Both phases must report `OK (1 test)`. The first phase preserves only its explicitly named fixture account; the second removes it. It verifies exact queue order (including duplicates), queue identity, index, position, repeat state and paused playback through a real browser with the fixture server unavailable. It does not claim offline audio playback. Full-suite and two-phase logs are saved separately under `test-results/native-auto-*` during local verification.

## Security boundaries

- Production credentials start blank. The mockups' prefilled dummy forms do not enter the Android app.
- Release URLs require HTTPS; debug permits HTTP only for `localhost`, `127.0.0.1` and the emulator's `10.0.2.2` host bridge. Ordinary LAN HTTP and certificate-validation bypasses are not supported.
- Passwords are AES-256/GCM encrypted with a per-installation Android Keystore key; connection identity is authenticated as associated data. Passwords and token-bearing URLs are not stored in UI state, playback metadata or session JSON. Room's library/session metadata is app-private, not whole-database encrypted.
- Cloud backup/device transfer of account and listening data is excluded. Keystore/ciphertext failures fail closed and require reconnecting.
- API and media redirects are disabled. Media items hold unsigned stream references. The service captures credentials per queue and authenticates immediately before a request; stale queue/account requests are rejected. Playback errors do not expose credential-bearing causes.
- Authenticated artwork uses an uncached image loader with redirects disabled. URLs are remembered per artwork/connection rather than regenerated on every progress tick.
- The media service is exported for OS media-session integration, but `onConnect` admits only the app's own UID/package or controllers trusted by Android. Legacy binding initially uses a synthetic unknown-UID lookup; returning a session for that lookup is not authorization of the real caller. Set-item requests resolve only canonical app IDs or bounded playlist-name searches; controller URIs and arbitrary add/remove/reorder operations are rejected. Stream references and credentials are reconstructed by the service, not supplied by the controller.
- Session mutations are serialized. Progress writes are checked against the connection, queue identity and exact song sequence **inside** the state lock, preventing a late old tick from overwriting a new shuffle.
- Asynchronous car selections also check their captured credentials and prior queue under that lock. A late response cannot replace a newer phone queue or a different account. Selecting an existing queue occurrence preserves concurrently confirmed rating/repeat metadata.

## Explicit limits / next verification

This is a first native slice, not a production release. No personal server, real credentials or physical phone were used. Real Navidrome deployments, reverse proxies, unusual libraries, API-key-only authentication, hardware codecs, Bluetooth/headset controls, real lock-screen behavior, interruptions, battery restrictions and older Android versions need further verification. The current authentication UI supports username/password token authentication, not API-key-only accounts.

Offline downloads, casting, equalization, playlist editing and automatic in-app updates are not implemented. The Android Auto integration described above still needs real host/DHU and vehicle testing. The GitHub release workflow supports signing/publication once repository secrets are configured; hosted publication is verified separately from local builds. Library selection currently persists preferences; there is no native album/all-song browser for those preferences to filter yet. Server administration remains in Navidrome.

For a first personal trial, install the debug APK manually and enter credentials **in the app**, not in chat or repository files. Use a valid HTTPS address, including any reverse-proxy base path. Never disable TLS validation to work around a certificate problem.
