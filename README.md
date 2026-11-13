# Music — playlist-first Android music player

A native Android alpha for Navidrome, plus the preserved interactive design studies that informed its layout. Native behavior is verified against isolated local protocol/audio fixtures; compatibility with a personal live Navidrome server and physical-device audio behavior remain to be tested.

## Native Android alpha

The Kotlin/Compose application lives in `android/`. It connects directly to Navidrome, discovers accessible libraries, loads playlists, starts a shuffled queue in the full player, streams through a Media3 background service, and saves connection-scoped queue order, position, repeat and shuffle settings. Returning users reopen the full player; cold restore is paused, while an already-running service keeps playing. Five-star song ratings are read back from the server before becoming confirmed UI state.

Music is the native app's new display name. It retains the application ID (`com.everyonesoftware.audio`), signing key/configuration, and local data identifiers to support updates to existing installs. Preserved browser mockups keep their historical names.

Credentials start blank and are encrypted with an Android Keystore-backed key. Release connections require HTTPS; debug-only loopback HTTP supports isolated tests. There is no Music backend, account service, WebView, production demo content, or embedded server password.

Build/setup, architecture, test commands, checkpoint semantics, and explicit alpha limitations are documented in [`android/README.md`](android/README.md). The debug APK builds to `android/app/build/outputs/apk/debug/app-debug.apk`.

Verified locally: 49 JVM tests and 66 instrumentation tests on an isolated API 36 emulator, including real production-activity connection, decoded WAV streaming while backgrounded, rating readback, seeking, activity return, native media browsing/playback, and artwork delivery through modern and legacy media controllers plus a trusted external UID. Android lint passes with warnings. These are fixture checks, not a claim that real-server, Bluetooth, Android Auto host, offline-download, or release-distribution testing is complete.

### Android Auto — first native integration

Music now declares media support for Android Auto and serves Playlists and Queue through its existing Media3 service. Browsing a playlist leaves playback unchanged; a shuffle action starts it, and choosing a queue occurrence keeps the existing queue identity. The phone and car controllers use the same player and persisted session. Playlist-name voice requests are supported; connection setup and ratings stay on the phone.

The integration is tested through real Media3 and legacy platform browsers on an isolated emulator, including streaming, stale-selection protection, subscription updates, and a two-process paused-restore probe. The user reports successful Android Auto vehicle playback and Bluetooth playback in a Tesla, with intermittent missing Tesla artwork in the earlier build. The service publishes both a small embedded current-song thumbnail and a higher-quality local content URI (up to 1024 pixels), from one authenticated download without exposing server URLs. Phone artwork is unchanged. The host chooses which representation to display; both vehicle paths still need a retest of this change. A car-player shuffle toggle and broader voice search remain deferred. The car host determines the actual layout; the browser study below is not a screenshot of the implementation. See [`android/README.md`](android/README.md) for scope and verification.

## Automated APK releases

`.github/workflows/android-release.yml` builds on pushes to `main` or manual dispatch, tests/lints the app, signs a non-debuggable APK, and publishes it with a SHA-256 checksum and source metadata to GitHub Releases. Both jobs check out complete Git history. Android's version code is the Git commit count, with version name/tag/asset such as `0.1.42`, `v0.1.42`, and `Music-0.1.42.apk`.

One-time persistent signing secrets are required before publication. See [`android/RELEASING.md`](android/RELEASING.md) for the four secret names, key backup, upgrade implications, reruns and verification. Configure those secrets and push the project to `main` to activate publishing; local tests are separate from hosted release verification.

## Preserved design studies

The sections below describe browser prototypes, not additional implemented native features. Their fictional credentials and simulated network/playback stay inside the mockups.

## Android Auto design study (v1)

Open `mockups/android-auto-v1.html`, or `/auto.html` on the optional preview server. The self-contained interactive board pairs a landscape car display with a companion phone, using the native app’s charcoal-and-sage palette and the existing fictional music collection. Compare both devices or focus on either one.

Now playing, recent playlists, and the queue share an in-memory demo session. Resume preserves the existing order and position; playlist names open an inspection view without replacing playback, while the shuffle action starts that playlist and returns to the player. Try transport controls, queue selection, phone-only song ratings, or the server-unavailable/retry preview. Reloading resets the demo. No audio, server requests, real phone synchronization, or persistence is implemented.

The system dock and car layout are illustrative, not a claim of Android Auto host validation or certification. The actual host controls its media presentation. This study remains preserved separately from the native integration described above. The playback action order follows Google’s media playback design reference, linked inside the study.

Run `npm run test:auto` for paired-state interaction checks, pending/retry recovery, phone ratings, and all three screens in all three review modes at 1600px, 1024px, 390px, and 320px. Screenshots and the report are written to `test-results/audio-auto-*`.

## Current review: readable player and rating popup (v5)

Open `mockups/first-install-v5-readable.html`. The 47% Services phone calibration and 390 × 875 logical viewport remain unchanged. This version makes the phone UI itself easier to read: 18–20px primary text, 15–16px supporting text, and larger controls. The player now fills its vertical space with flexible artwork, readable song details, transport, and the playlist/queue actions near the bottom.

The five inline stars move into a modal opened by a compact star button beside the song title. Its five 56 × 80px choices save on tap; Clear removes a rating, Done closes, and Escape/backdrop restore focus. Pending/failure/offline/retry and song-specific rating behavior remain simulated.

Connection fields are prefilled with fictional values, including a fixed dummy password, so setup and retry require no typing. This is a v5 mockup-only convenience: previous validation-focused versions are unchanged, no requests are sent, and no entered password is persisted. The popup and demo state use a separate v5 storage namespace; physical calibration is shared with v4.

Sources: `mockups/v5/`, `scripts/build-readable.mjs`, and the reusable calibrated wrapper builder. Run `npm run test:readable`. Prior artifacts remain preserved.

## Preserved calibrated phone size (v4)

Open `mockups/first-install-v4-phone-size.html`. This wraps the unchanged v3 app in the same Pixel 10 preview frame and **47%** monitor-size calibration used in the Services mockups. The entire device scales; the embedded app remains at a fixed 390 × 875 logical viewport instead of squeezing its contents. Hardware frame and system chrome are illustrative.

**Your size · 47%** restores the known calibration. The size slider is saved in this browser; **Large detail view** temporarily enlarges the device without overwriting that calibration. Browser zoom or display scaling changes require matching the outer width against the real phone again. Use the interactive HTML rather than chat images for physical-size review, because chat may resize screenshots.

The full v3 setup, library, playlist, rating, and restored-session flows remain interactive. V3 is preserved. Build with `npm run build:mockups`; run `npm run test:phone-size` for scale, persistence, fixed-viewport, overflow, and actual interaction tests. The preserved calibrated study is available at `/v4.html`; `/v3.html`, `/v2.html`, and `/v1.html` also remain available.

## First install, libraries, and ratings (v3)

Open `mockups/first-install-v3.html`. This extends the playlist-first direction with an interactive Welcome → Connect → Choose libraries → Review → Playlists → Player flow, keeping the same provisional visual treatment. No theme has been chosen.

Server & libraries settings include multiple demo connections, per-connection sessions, library browsing preferences with save/cancel, refresh, reconnect, a server-admin handoff explanation, and local-only removal confirmation. The player replaces the favorite heart with a one-to-five-star **song rating**, including Clear, saving, failure, offline, and retry states. No real rating is sent to a server.

Use only dummy credentials. Entered passwords are cleared and never persisted. The screen selector seeds fictional setup for review; Restart first-install demo returns to a genuine empty demo. Browser storage is separate from v2.

Build sources, protocol notes, primary documentation references, and limitations are in `mockups/v3/NOTES.md`. Run `npm run test:onboarding` to rebuild and exercise the v3 flow.

## Preserved playlist-first workflow (v2)

Open `mockups/playlist-first-v2.html`. This version shifts the primary surface from **Explore** to **Operate**: continue listening or change playlists. It intentionally uses one consistent provisional treatment rather than comparing themes.

- Open directly to the full player with the last playlist, song, elapsed position, exact queue, and shuffle/repeat settings restored. Cold opening is paused, never autoplaying.
- Resume continues the same queue, not a fresh shuffle.
- Change playlist opens recent playlists first; each row has a one-tap shuffle action that starts it and immediately returns to the full player.
- Tap a playlist name to inspect without changing the listening session.
- First listen starts with playlists; after playing once, future launches restore the player.
- Album browsing is secondary, behind Browse library. Album browsing in v2 is non-interactive context, not a complete browsing implementation.
- State is stored only in the browser's localStorage under a v2-specific key per sample phone. No real library, account, server address, or credentials are stored. Reset demo clears only these demo keys. Browser policies may prevent persistence; the prototype discloses that limitation.
- Reopen app simulates a cold launch for the displayed sample phone(s), without clearing the saved session. Reloading or opening a new tab also restores saved sessions in a browser that permits localStorage.

The desktop board compares three moments of the same flow: returning, switching, and first use. The labeled Phone options focus on an individual interactive device. Hash entry points: `#all`, `#returning`, `#switching`, `#first`. Each frame has independent state. V2 is archived; the readable v5 layout informed the current native draft, with no final theme selected.

## Open the mockups

Open `mockups/first-install-v5-readable.html` in a browser. Prior versions remain unchanged at `mockups/playlist-first-v2.html` and `mockups/index.html`. It is self-contained: CSS, JavaScript, icons, and original illustrative album artwork are embedded. No CDN, account, network connection, or build is required.

Alternatively:

```sh
npm install
npm run preview
```

The optional preview server listens only on `127.0.0.1:4173`, serving readable v5 by default, calibrated v4 at `/v4.html`, the unscaled first-install study at `/v3.html`, the playlist-first study at `/v2.html`, and the first theme study at `/v1.html`.

## Archived v1 directions

- **A — Evergreen:** familiar, Material-inspired controls and a compact album grid; dark forest palette.
- **B — Sleeve:** album-first browsing, warm paper, serif headings, and a featured record; the original visual recommendation, not a chosen theme.
- **C — Nightshift:** listening-first browsing with the current record and upcoming queue; charcoal and pale blue.

The top controls compare all three or focus on one direction. Screen selectors show Library, Album, Player, Queue, Offline, and Server. Deterministic entry points include `#all/library`, `#all/player`, and `#sleeve/player`.

Inside each phone, tap the library categories, search, album artwork, mini-player, navigation, player controls, favorite button, or queue. The player has simulated play/pause, track changes, seek, shuffle/repeat toggles, and favorites. Download and server actions are explicitly simulated. The form supports success and failure previews and never sends or persists values. Use dummy values only.

All music names, artwork, durations, storage amounts, and quality labels are illustrative. They do not represent a real library or verified playback capabilities. No real audio plays or downloads occur. No authentication, API integration, background playback, Android media session, casting, or native installation is implemented.

## Verification

```sh
npm test
```

The Playwright suite uses installed Microsoft Edge on Windows, or Playwright's Chromium elsewhere (`npx playwright install chromium` if needed). It checks all six screens in all three directions at 1440px, 390px, and 320px; exercises primary click paths and server success/failure; asserts no JavaScript errors, broken artwork, horizontal overflow, clipped player footers, or external requests. Screenshots and a machine-readable report are written to ignored `test-results/`.

The v2 workflow suite (`npm run test:workflow`) separately exercises focused and comparison layouts at 1440px, 1024px, 390px, and 320px. It tests first listen, one-tap resume, switching with direct shuffle, search/no-results, inspection without replacing playback, queue navigation, Escape, browser reload and a new tab, saved position/shuffle/queue restoration, unavailable-source recovery, and independent scenario storage. It records external requests and JavaScript errors. This verifies a browser mockup, not native Android audio restoration or a Navidrome integration.

V2 visual self-audit: 0/10 generic-layout tells identified. The composition is a player and playlist selector, not a marketing screen; no glossy gradients, decorative metrics, blur, accent rails, or feature-tile sections. A redundant current-playlist card was removed from the selector to give recent playlist actions priority; the persistent mini-player already holds the current session.

## Original v1 design notes

The primary surface is **Explore**: browsing a personal collection. Evergreen emphasizes library density, Sleeve emphasizes album selection, and Nightshift emphasizes continuing a listening session. Player screens deliberately share a familiar control layout for comparison. Library content scrolls inside each phone while the mini-player and navigation remain stationary.

The UI font is Segoe UI, selected for this Windows-rendered design study; the proposed Android implementation should evaluate Android's system typography. Sleeve adds Georgia as a portable approximation of an editorial serif. These are mockup choices, not native Android type specifications.

A design self-audit found no glossy tech gradients, feature-tile filler, accent rails, blur, giant metrics, or icon-topped sections. The one gradient is a functional dark scrim for readable cover captions. Album covers are intentionally original geometric placeholders rather than production assets.

Earlier studies remain archived for comparison. Current native implementation status and remaining verification are documented above and in `android/README.md`.
