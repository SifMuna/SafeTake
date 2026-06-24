# SafeTake

A private, PIN-locked camera and gallery app for Android. Photos and videos
never touch shared storage: they are captured (or received via the share sheet)
and stored inside the app's own data container.

## Security model

- **Lock is a PIN or a password.** First run lets the user choose; either way it
  derives a key via PBKDF2-HmacSHA256 (300k iterations, random salt).
- **Everything is encrypted at rest** with AES-256-GCM — media, thumbnails, and
  the index. (Older versions had an optional "store unencrypted" mode; that was
  removed. New captures/imports are always encrypted, and any legacy plaintext
  `.raw` items from those versions are still read transparently via each item's
  `encrypted` flag.)
- **Key hierarchy.** A random 256-bit data key (DEK) encrypts media, thumbnails,
  and the index. The DEK is wrapped (AES-GCM) by a KEK derived from the
  PIN/password. A wrong PIN simply fails to unwrap the DEK (GCM auth-tag
  mismatch) — **no PIN hash is stored, and there is no recovery.**
- **Optional biometric unlock** (`BiometricVault`). When enabled, a *second* copy
  of the DEK is wrapped by a hardware-backed AndroidKeyStore key that is gated
  behind a Class-3 (strong) biometric. It's a convenience second door to the same
  DEK; the PIN/password always still works. Enrolling a new fingerprint/face
  invalidates the Keystore key, so the biometric copy stops working and the user
  falls back to the PIN (the vault self-disables in that case).
- **Permissions: `CAMERA` and `RECORD_AUDIO` only.** No INTERNET, no storage or
  media permissions (`ACCESS_NETWORK_STATE` from ExoPlayer is explicitly removed
  in the manifest).
- **Media gets in** by taking a photo/video in-app, or by sharing to SafeTake
  from another app's share sheet (`ShareReceiverActivity`).
- **Media gets out** only via the explicit Share button. Share-out streams
  decrypted bytes through a pipe (`DecryptingProvider`) — plaintext is never
  written to disk on the way out.
- **Auto-lock:** the in-memory key is dropped ~30s after the app goes to
  background (`SafeTakeApp` + `ProcessLifecycleOwner`). `FLAG_SECURE` blocks
  screenshots and recents thumbnails.
- **Known compromise:** MP4 recording/playback needs a seekable file, so videos
  briefly exist as plaintext temp files in the app-private `cacheDir` during
  capture and playback. Temps are deleted when done and swept on app start and on
  lock. Photos never touch disk unencrypted. `allowBackup=false`.

## Architecture & data flow

- **Single activity, Compose Navigation.** `MainActivity` (a `FragmentActivity`,
  required by the biometric prompt) shows `LockScreen` while
  `SessionVault.unlocked` is false, otherwise a `NavHost` over
  `gallery → camera | viewer/{id} | settings`.
- **`SafeTakeApp`** is the `Application`; it owns the singletons (`keyManager`,
  `biometricVault`, `prefs`, `repository`), wires auto-lock, and sweeps temp
  files.
- **Unlock flow:** `LockScreen` collects PIN/password (or biometric) →
  `KeyManager.unlock` / `BiometricVault.recoverDek` returns the DEK →
  `SessionVault.unlockWith` holds it in memory → `MediaRepository.loadIndex`
  decrypts the index.

### Module map

- `crypto/`
  - `KeyManager.kt` — PIN/password ↔ DEK (PBKDF2 KEK wraps the DEK in
    `keystore.properties`). `initialize`, `unlock`, `changePin`.
  - `BiometricVault.kt` — optional biometric door: DEK wrapped by a
    biometric-gated Keystore key in `biometric.properties`. `encryptCipher` /
    `storeWrappedDek` to enable, `decryptCipher` / `recoverDek` to unlock.
  - `VaultCipher.kt` — `STVAULT2` chunked AES-GCM file format (see its KDoc);
    still reads legacy single-stream `STVAULT1` files.
  - `SessionVault.kt` — holds the unwrapped DEK in memory; `lock()` drops it and
    notifies listeners. Source of truth for the `unlocked` state.
- `data/`
  - `MediaRepository.kt` — encrypted media, thumbs, and index under `filesDir`.
    `savePhotoAsync` / `saveVideoAsync` run captures on a process-lifetime
    `ioScope` so a long encrypt finishes even if the camera screen is torn down.
    Deletion is undoable in three phases — `requestDelete` (hide from `items`),
    `undoDelete`, `commitDelete` (erase files on `ioScope`) — backing the Undo
    snackbar. Still reads legacy plaintext `.raw` items via each `encrypted` flag.
  - `Prefs.kt` — non-secret policy (credential *shape*, show-PIN-length). Plain
    SharedPreferences; never stores the secret itself.
- `share/` — `DecryptingProvider` (share-out content provider), `ShareOut`
  (chooser intent).
- `ui/`
  - `LockScreen.kt` — setup + unlock; `PinPad` / `PasswordPad` (reused by
    Settings), `CredentialChooser`, biometric affordance.
  - `GalleryScreen.kt`, `CameraScreen.kt` (CameraX photo/video, lens/flash),
    `ViewerScreen.kt` (ExoPlayer video; photos are pinch-to-zoom / double-tap /
    pan via `ZoomableImage`), `SettingsScreen.kt` (show-PIN-length, biometric
    toggle, change credential), `Theme.kt`.
  - `Biometrics.kt` — `BiometricAuth` (strong-biometric + CryptoObject prompt
    wrapper) and `Context.findFragmentActivity()`.
- `ShareReceiverActivity.kt` — share-sheet import target.

## Build & test

```sh
./gradlew test           # JVM crypto tests
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Verify encryption at rest (debug build):

```sh
adb shell run-as app.safetake sh -c 'xxd files/media/*.enc | head -2'
# encrypted items show a STVAULT2 (or legacy STVAULT1) header, not FFD8 (JPEG) or ftyp (MP4)
```

## Conventions & gotchas

- **minSdk 31, targetSdk 36, Kotlin + Compose + Gradle KTS.** Dependencies are in
  `gradle/libs.versions.toml` (version catalog), not inline in `build.gradle.kts`.
- **The PIN/password is the source of truth for the DEK.** Biometrics only ever
  wrap a copy of the DEK — never gate the PIN path, and never assume biometrics
  are present. Changing the PIN rewraps the same DEK, so the biometric copy stays
  valid; media files are untouched.
- **Conscrypt AES-GCM is single-shot and caps at ~64 MiB.** It buffers the whole
  message and hard-fails just past 64 MiB (this silently dropped videos longer
  than ~30s). `VaultCipher`'s `STVAULT2` format encrypts the file as independent
  ~1 MiB GCM chunks (random IV + `index‖isFinal` AAD per chunk) so size is
  unbounded and memory stays flat. Don't reintroduce a single `doFinal` over a
  whole file. Legacy `STVAULT1` (single-GCM) files are still read.
- **Captures must be saved on a non-UI scope.** Encrypting a long video takes
  seconds; if that work runs on a screen's `rememberCoroutineScope` it's cancelled
  when the user leaves the camera, silently dropping the save. Always route saves
  through `MediaRepository`'s `ioScope` (`savePhotoAsync` / `saveVideoAsync`).
- **Delete is an Undo snackbar, not a dialog.** The `SnackbarHostState` and the
  request→commit/undo logic live in `SafeTakeNav` (nav level), not in a screen, so
  a delete started in the viewer survives the pop back to the gallery and stays
  interactive. Locking abandons any uncommitted delete (items reappear next unlock).
- `BiometricPrompt` requires a `FragmentActivity`; that's why `MainActivity`
  extends it. Reach the activity from Compose via `Context.findFragmentActivity()`.
  Note: `androidx.biometric:1.1.0` drags in an ancient `androidx.fragment:1.2.5`
  whose `FragmentActivity` crashes the Activity Result permission API ("Can only
  use lower 16 bits for requestCode"); the catalog pins `androidx.fragment` to a
  modern version to fix it — don't drop that pin.
- Devices used for manual testing connect over **wireless adb** (no emulator);
  the biometric and camera paths need a real device anyway.
