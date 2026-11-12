# APK releases and Git-count versions

Workflow: `.github/workflows/android-release.yml`.

## What runs

A push to `main` builds and publishes a signed Android release. You can also select **Actions → Build and release Android APK → Run workflow**, choosing `main`. Other branches and pull requests cannot run the signing/publication jobs.

The workflow checks out full history, derives the version, installs Java 21 and the pinned Android SDK packages, runs release-tool tests plus native JVM tests/lint, and builds/verifies `assembleRelease`. It does not run Android emulator instrumentation in CI; that suite remains a separate local verification step.

The resulting GitHub release contains:

- `Audio-0.1.<count>.apk` — the signed, non-debuggable APK.
- `Audio-0.1.<count>.apk.sha256` — its SHA-256 checksum.
- `release.json` — exact source commit, Git count, version, APK filename, size and checksum.

A 30-day workflow artifact also retains those three files. GitHub release assets do not use that artifact expiration policy. A new release is drafted, its uploaded bytes are downloaded and checked, then it is published and verified. Only a release for the current `main` tip is promoted to Latest. Publication is serialized; GitHub may coalesce intermediate pending publication jobs during a burst of pushes. The built artifacts remain available, and a workflow can be rerun if needed.

## Version rule

The sole build counter is `git rev-list --count HEAD` across the complete reachable history, including merged commits:

| Field | Rule | Example at count 42 |
| --- | --- | --- |
| Android `versionCode` | Commit count | `42` |
| Android `versionName` | `0.1.<count>` | `0.1.42` |
| Release tag | `v<versionName>` | `v0.1.42` |
| APK filename | `Audio-<versionName>.apk` | `Audio-0.1.42.apk` |

CI passes that exact count to Gradle as `-PaudioCommitCount=<count>`, then checks the generated APK metadata against the same history before packaging. Local builds calculate the count from Git when no override is supplied. The override is useful for local build verification, not a second production counter. Shallow histories are rejected; use `fetch-depth: 0`. `github.run_number` is not used, so rerunning a workflow does not invent a new app version.

Before this repository has its first commit, local debug builds retain `0.1.0` with Android code 1. Releases require a positive count. Do not rewrite published history or reset the counter: Android updates need increasing codes. Different branches can have identical counts, which is why automatic releases are restricted to `main`. A tag already pointing at another commit is an error, never silently overwritten.

## One-time signing setup

Configure the following signing secrets before the first hosted build. Until configured, the workflow deliberately fails with the missing secret names rather than publishing an unsigned APK or generating a new key each run.

1. In Android Studio, choose **Build → Generate Signed App Bundle or APK → APK → Create new**. Create a project-specific keystore **outside the repository**, select a key alias, and use a validity of at least 25 years. This step only creates the keystore; you can cancel the wizard afterward without completing a local release build. Keep an encrypted backup of the keystore, its passwords and alias. Do not reuse the disposable key from local workflow verification.
2. Open this GitHub repository's **Settings → Secrets and variables → Actions** and add the following **repository secrets**:

   | Secret | Value |
   | --- | --- |
   | `AUDIO_KEYSTORE_BASE64` | Base64 encoding of the complete keystore file. This is sensitive key material, not encryption. |
   | `AUDIO_KEYSTORE_PASSWORD` | The keystore password. |
   | `AUDIO_KEY_ALIAS` | The exact alias of the signing key inside the keystore. |
   | `AUDIO_KEY_PASSWORD` | That key's password; it may match the keystore password. |

   For Windows, this PowerShell command copies the encoded file directly to your clipboard instead of printing it. Replace the example path with your keystore's actual location, paste into the GitHub secret field, and clear your clipboard afterward:

   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\Users\YOUR_USER\Keys\audio-release.jks")) | Set-Clipboard
   ```

3. Commit and push the project, including this workflow, to `main`. Monitor both build and publish jobs. No personal access token is needed: only the publish job receives the built-in token's `contents: write` permission. Repository/organization policy must allow that permission.

The runner decodes the key into a permission-restricted temporary file only for signing, passes passwords through environment variables, and removes the file on exit. Neither the key nor its passwords go into the repository, APK assets, or release metadata. Do not share the keystore/base64/passwords in chat. Gradle requires `AUDIO_KEYSTORE_PATH` plus the other three signing variables for local release builds; debug builds need none.

**Keep the same key for all updates.** Changing it prevents ordinary APK upgrades. If you installed the earlier locally signed debug APK, switching to the new release signer requires a one-time uninstall/reinstall, which removes that installation's saved accounts/session.

## Reruns and partial failures

- A partially uploaded draft for the exact same commit can be repaired and verified before publication.
- A published release is not overwritten. Reruns validate its source tag and download/check its existing assets; a new build's potentially different bytes do not replace published APKs.
- An unrelated/mismatched tag, draft target, corrupted upload, or unverified published state fails closed.
- Failed/incomplete releases are not automatically deleted. Inspect the workflow before changing a draft or tag manually.
- Signing keys must be preserved independently of artifacts/caches. Losing the private key is not fixed by rerunning CI.

## Local verification

From the repository root:

```sh
python -m unittest discover -s tests -p test_android_release.py -v
```

Requires Git and Python 3.11+. Tests use disposable Git histories and mocked GitHub commands, including fresh releases, partial drafts, immutable published assets, shallow history, wrong tags and corrupted downloads. They do not create real tags/releases or upload secrets. The workflow is also validated with `actionlint`; its signing path has been exercised by a real local release build using a disposable key and an explicit count-42 test override. This is not a claim that a GitHub-hosted release has run before the workflow is committed/pushed and signing is configured.
