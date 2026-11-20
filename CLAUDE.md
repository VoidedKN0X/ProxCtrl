# ProxCtrl

Android app for managing a Proxmox VE 8/9 server. Part of the VKNXWorks Ctrl series: same look, structure and libraries as NaviCtrl, FrigateCtrl and the other Ctrl apps (Kotlin, Compose, Material 3, manual `AppContainer` DI, OkHttp + kotlinx.serialization, Ctrl / Signal look with a floating tab bar). No Firebase or Google Play Services: it must build for F-Droid.

## Working on this repo

- Don't commit. Leave changes uncommitted for review. Push straight to `main` only when asked, with no branches or pull requests.
- Every push needs a detailed `CHANGELOG.md` entry.
- Every user-visible string goes in `app/src/main/res/values/strings.xml`.
- Check Proxmox endpoints and privileges against the Proxmox source or API viewer; never guess field names. Parse API responses leniently (missing fields must never crash).
- Test write actions on a throwaway container, never on TrueNAS (103), HAOS (106) or Vaultwarden.

## Encryption at rest

- `noBackupFilesDir/cache/guests-<id>.json`, `cache/overview-<id>.json` and `notify/<id>.json` are sealed with AES-256-GCM (`data/crypto/SealedFile`, marker `PROXCTRL-REST1`) under the data key (`DataKeyStore`: 32 random bytes in `filesDir/data.key`, wrapped by the Keystore key `proxctrl_data_at_rest` through `KeystoreWrapper`/`KeystoreAes`; no user authentication, so background checks work while locked). It is separate from the app lock vault and from `proxctrl_secrets`; never touch those for this.
- All of these are caches the app can make again, so they go through `data/crypto/SealedCache` (the simple tier): reads accept sealed and ordinary files; writes seal when there is a data key and write an ordinary file when there isn't; a sealed file that can't be opened is a cache miss and is deleted. Never put anything the user typed in, or anything that can't be fetched again, through `SealedCache`: that needs the full treatment (block writes, keep the file, tell the user), as in RideCtrl.
- An ordinary file only becomes a sealed one through `SealedFile.sealInPlace` (temp file, sync, read back, compare, atomic rename); `AppContainer.sealStoredFiles()` runs that over `cache/` and `notify/` at every start, on a background thread. Never replace an existing `data.key`, and never create a Keystore key on the decrypt path (`KeystoreAes.existing`).
- Keystore work stays off the main thread: `SealedCache.readText`/`writeText`/`sealAll` are only called from `Dispatchers.IO`; `delete` doesn't touch the Keystore and may run anywhere.
- Not sealed on purpose: the `servers` preferences (server list, read synchronously at start), the push registration preferences (read on the main thread in the push receiver), plain settings, and WorkManager's database. See the changelog entry for the reasons.
- `SealedFileTest` covers the format, the migration step, the key rules and `SealedCache` (`./gradlew :app:testDebugUnitTest`; CI doesn't run unit tests).

## Dependency verification

Gradle checks every library and plugin download against its SHA-256 in `gradle/verification-metadata.xml` (checksums only, no PGP). When a dependency or plugin changes, run the `verification-metadata.yml` workflow (`gh api -X POST repos/VoidedKN0X/ProxCtrl/actions/workflows/verification-metadata.yml/dispatches -f ref=main`), decode the file from its log, check the diff (only Maven Central and Google's Maven) and commit it in the same push. Never turn verification off or make it lenient.

`settings.gradle.kts` lists only `google()` and `mavenCentral()` for plugins, not `gradlePluginPortal()`: the portal serves different files for the Kotlin plugin markers, which made verification fail whenever Maven Central had a hiccup. Don't add it back unfiltered (see the comment in that file).

## Build and signing (CI)

`.github/workflows/build.yml` has four jobs: `version` (is this a release?), `build` (Gradle, **no secrets**, `UNSIGNED_RELEASE=true` gives `app-release-unsigned.apk`; runs every check), `sign` (no checkout, no Gradle: `zipalign` + `apksigner` with the release key, then checks the signer against `EXPECTED_CERT_SHA256` and that the APK's contents didn't change) and `release` (attestation, release notes, publish).

- Never give the `build` job a secret, and never run Gradle or check out the repo in `sign`. New build checks (tests, lint, APK checks) go in `build`.
- `EXPECTED_CERT_SHA256` in `build.yml` must match the fingerprint in `SECURITY.md` and `README.md`; the build fails if it doesn't.
- No Gradle cache in any workflow (`cache-disabled: true`): a cache saved by a failed run used to break every later build.

## Releasing

- A release is published only when `versionName` in `app/build.gradle.kts` is bumped. Bump `versionCode` by 1 at the same time.
- Tags are `vX.Y.Z` and releases are named "ProxCtrl X.Y.Z". Never add a build number (`-build.N`, "(build N)").
- Use the same version for every change that ships together; README, licence and CI changes don't need a bump.
- CI signs with the `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD` and `KEY_PASSWORD` secrets and the `KEY_ALIAS` variable (`prox`). Never commit a keystore, its base64 or its passwords.

## Changelog

Every new version needs a detailed entry at the top of `CHANGELOG.md`, under a heading for that version (match the existing headings). CI refuses to release without one, and the entry becomes the release notes on GitHub, in Obtainium and in the CtrlF-Droid repo.

- Write for people using the app, not for developers: what's new, what changed, what was fixed.
- Cover every user-visible change in the version, one bullet each, with enough detail to understand it without opening the app.
- Group with `### New`, `### Changed`, `### Fixed` and `### Security` when there's more than a couple of items.
