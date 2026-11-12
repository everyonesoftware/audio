# First install, libraries, and song ratings (v3)

## Review

Open `../first-install-v3.html`, which is self-contained and requires no server or build. The fresh-install entry has no connection or listening history after **Restart first-install demo**. The labeled screen selector can jump to later screens by seeding fictional setup data. These are review shortcuts, not an authorization bypass in a real app.

Hash entry points: `#welcome`, `#connect`, `#libraries`, `#ready`, `#playlists`, `#player`, `#settings`, `#queue`. The desktop layout places the interactive Android phone between journey notes and screen-specific explanations; the mobile layout keeps the same functional phone and moves explanations below it. This is not a proposed desktop music client.

## Proposed path

1. **Welcome:** one primary Connect to Navidrome action, no separate Audio account.
2. **Connect:** connection label, HTTPS server base URL (including any installation subpath), existing Navidrome username, and password. Only dummy credentials are permitted in the prototype. Password visibility, missing-field/URL validation, checking, bad credentials, unreachable-server, retry, and cancel are interactive.
3. **Discover and choose libraries:** available libraries are tied to that account. Choose at least one library to browse; a no-library-access state directs the user to an administrator and supports refresh. Saving and canceling library preferences have different effects.
4. **Review:** connection and selected browsing libraries, before sample playlists are loaded.
5. **Playlists:** first use has no invented recent history. Shuffle a playlist to enter the full player. Later use promotes recent playlists.
6. **Player:** restored playlist/track/order/position/shuffle state, cold start paused, with one-to-five-star song rating instead of a favorite heart.
7. **Server & libraries:** add/switch connections, reconnect an account, select browse libraries, refresh access/list, explain the server-admin handoff, and confirm local-only connection removal. Existing listening sessions are isolated per connection.

The current prototype intentionally validates HTTPS-only connection addresses; cleartext HTTP opt-in is not designed here. Do not infer native Android network-security behavior from this browser form.

## Library boundaries

- A saved server/account connection and a Navidrome music library are different concepts. One connection may expose multiple libraries.
- Browse selection is a client preference, not access control. Navidrome still decides what the account is authorized to see.
- Cross-library playlists and the existing listening queue are not silently rewritten by a browse-only preference. This avoids promising a global library filter on API endpoints that do not accept one. A production integration must explicitly handle any unavailable playlist entries and re-check server permissions.
- Creation/deletion of server libraries, folder paths, scan administration, and user grants are not implemented via a fabricated Subsonic endpoint. The mockup provides a simulated browser handoff to Navidrome administration.
- Removing a connection affects this device only, not server music, playlists, accounts, or server-side ratings. Cancel and Escape preserve it.

## Rating semantics

The five-star row means **the current user's rating of this song**, not its album or playlist and not a binary favorite. Intended protocol mapping is `setRating(id=<songId>, rating=1..5)`; Clear maps to rating `0`. Read the current user rating from the authenticated server's song data; do not substitute the community average or the `starred` flag.

The demo keeps a confirmed value for each connection + song. While a simulated write is pending, repeat writes are disabled. On save failure or offline, the last confirmed value stays displayed and Retry retains the intended new value. Changing tracks during a pending operation cannot attach that rating to the new song. A changed server/account creates a separate connection rather than retargeting an existing listening session. Browser storage holds only fictional connection display metadata, queue data, and sample ratings—never entered passwords.

All 'saved to Navidrome' labels are suffixed **simulated**. No live authentication, permission discovery, server provisioning, music playback, library mutation, or rating write occurs. The ready UI is not evidence of compatibility with a particular deployed server.

## Primary references checked

- Navidrome, **Multi-Library Support**: https://navidrome.org/docs/usage/multi-library — available libraries, administrator vs regular-user access, cross-library playlists, Settings → Libraries / Users, and `getMusicFolders`. Official indexed content was available; direct page extraction was blocked with HTTP 403.
- Navidrome, **Subsonic API Compatibility**: https://www.navidrome.org/docs/developers/subsonic-api — accessible music folders and implemented media-annotation endpoints. Official indexed content was available; direct page extraction was blocked with HTTP 403.
- OpenSubsonic, **setRating**: https://opensubsonic.netlify.app/docs/endpoints/setrating/ — successfully fetched directly. Defines a song/folder ID, rating 1–5, and 0 to remove; an empty successful response is not an authoritative readback of the resulting song rating.

A production integration must authenticate against the user's actual server, check supported capabilities and permissions, and read back the exact song rating after a write before claiming synchronization. API support is not established merely by this design research.

## Build and verify

Sources live in `shell.html`, `flow.css`, and `flow.js`. `scripts/build-onboarding.mjs` reuses the v2 visual tokens and original sample artwork, then embeds these sources into the portable v3 HTML. The older mockups remain unchanged.

```sh
npm run build:mockups
npm run test:onboarding
```

Tests exercise the actual first-install path at desktop, 390px, and 320px; screen shortcuts also run at 1024px. They cover password clearing/non-persistence, success/failure/retry/no-access, library cancel/save, queue retention, two-server isolation, star/clear/offline/retry/keyboard interactions, in-flight target identity, browser reload, and removal scope/focus. Browser requests are observed to ensure disconnected simulation. `test-results/first-install-verification.json` records the executed groups.

Visual audit: retains the v2 palette, avoids new theme options, and uses Configure for setup followed by Operate for listening. No decorative stats, fake admin feature grid, blur, or glossy gradients were added. Screenshots are reviewed separately from geometry assertions. Native Android keyboard insets, secure storage, background media sessions, streaming, and actual Navidrome synchronization remain unverified and unimplemented.
