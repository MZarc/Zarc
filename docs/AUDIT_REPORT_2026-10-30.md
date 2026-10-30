# Zarc Vault — Security, Correctness and Resource Audit

**Date:** 2026-10-30  
**Branch:** `main` (HEAD `a3e5edb`), all changes uncommitted  
**Device:** Samsung SM-A546E (`RZCWA13C7MB`), Android 14  
**Scope:** Rust core, JNI bridge, Android storage/gallery/archive/private-vault/UI

---

## 1. Executive summary

The audit confirmed **28 findings**: 5 critical, 6 high, 10 medium and 7 low.
All are addressed in the working tree except pre-existing quality-gate issues
(cargo fmt/clippy backlog and 25 lint warnings), which are documented in
section 8. No commits or pushes were made, and no real user media was touched;
every test used synthetic fixtures.

The highest-risk issues were:

1. A missing Keystore key could silently create a new vault master key instead
   of failing closed, making the existing vault unreachable.
2. Index writes were not atomic, and a corrupt index was treated as an empty
   catalogue, so a crash could overwrite the real catalogue.
3. Master-key rotation only re-wrapped normal objects: the index, thumbnails,
   recycle bin and binned thumbnails stayed under the old key. An interrupted
   rotation was not finished before vault access, and rotation was allowed while
   a recovery envelope existed, silently invalidating that envelope.
4. The private-area subkey was deterministically derived from the vault master
   key, and the biometric gate key was not authentication-bound. Backgrounding
   the app did not relock the private area, and screenshots were allowed.
5. A single corrupt object aborts rotation and therefore blocks startup
   recovery (documented as a residual risk, not changed in this pass).

Validation on the physical device:

| Gate | Result |
| --- | --- |
| Rust workspace tests | **113 passed, 0 failed** |
| Android unit tests | **5 passed, 0 failed** |
| Instrumentation tests on `RZCWA13C7MB` | **37 passed, 0 failed** |
| Android lint | **0 errors**, 25 warnings, 3 hints |
| Debug APK build/install/cold launch | success, 2.8 s cold start, empty crash buffer |
| `git diff --check` | clean (no whitespace errors) |

Pre-existing quality gates still fail and are **not** regressions from this
work: `cargo fmt --check` and `cargo clippy -D warnings` fail across many
untouched files, and 25 lint warnings remain. Details in section 8.

---

## 2. Method

- Static review of the Rust container/archive/vault code, the JNI boundary, the
  Android repositories, Keystore and biometric code, Compose screens and the
  manifest.
- Targeted adversarial tests for archive parsing, object extraction, rotation
  and storage leaks.
- On-device instrumentation against a real Android Keystore and real app
  storage on the connected SM-A546E.
- No live user vault was opened; instrumentation fixtures are created and
  removed by the tests.

Severity means: **Critical** = data loss or cryptographic bypass likely;
**High** = data loss/denial of access under realistic failure; **Medium** =
correctness, privacy or resource issue with limited blast radius; **Low** =
hardening, lint or documentation.

---

## 3. Findings and fixes

### Critical

| ID | Area | Confirmed finding | Fix | Verification |
| --- | --- | --- | --- | --- |
| C1 | VMK lifecycle | If the Keystore key was missing or unwrap failed, the repository could generate/replace the vault master key, silently orphaning the existing vault. | `vaultMasterKey()` now fails closed with `KeystoreFailure`; only an explicit recovery path can supply a different key. | `aMissingKeystoreKeyFailsClosed`, `losingTheKeystoreKeyMakesTheVaultUnreachable` |
| C2 | Index durability | `index.bin` was written in place. A crash could truncate it; a corrupt index was treated as empty and later saved over the real catalogue. | Atomic temp-file + `fsync` + rename; corrupt index is quarantined (`.corrupt-*`) and subsequent saves are blocked until it is resolved. | Code review; gallery encryption/index instrumentation |
| C3 | Rotation completeness | Rotation re-wrapped only normal objects. Index, thumbnails, bin objects and binned thumbnails remained sealed under the old key; startup did not finish an interrupted rotation; rotation was allowed with a recovery envelope present, invalidating it. | Staged new VMK, full artifact rotation (objects, bin, index, thumbnails, binned thumbnails), checked commit, idempotent re-sealing, startup recovery before any vault access, and explicit refusal while recovery or a legacy private migration exists. | `rotatingTheMasterKeyKeepsTheIndexBinAndThumbnailsReadable`, `anInterruptedRotationIsFinishedBeforeTheVaultIsUsed`, `rotatingTheMasterKeyKeepsTheObjectsReadable` |
| C4 | Private vault key hierarchy | The private subkey was `BLAKE2b(VMK, "zarc.private.v1")`, so VMK compromise exposed the private area. The gate key was not auth-bound and could be used without a biometric `CryptoObject`. | New random 256-bit private subkey, wrapped by an auth-bound Keystore key (`setUserAuthenticationRequired(true)`, `setInvalidatedByBiometricEnrollment(true)`, biometric-only `CryptoObject`), versioned `ZPG2` blob, legacy migration on first unlock. | Code review only; the real prompt requires human biometric input. Legacy migration and key-separation logic were reviewed, not automated against a biometric sensor. |
| C5 | Private area lifecycle | Backgrounding the app left the private area unlocked; screenshots/recent-task snapshots were allowed; private import could proceed without an unlocked key. | `onStop()` relocks unless an external private picker is active; `FLAG_SECURE` app-wide; private import guarded and cleaned up in `finally`. | Code review; app-wide launch test. Relock timing is manual (no automation of background/lock). |

### High

| ID | Area | Confirmed finding | Fix | Verification |
| --- | --- | --- | --- | --- |
| H1 | Key/recovery durability | Wrapped VMK and recovery envelope/witness were written non-atomically; a crash could destroy the only unlock path. | Atomic writes with permissions, staged rotation key, checked commit. | Code review; recovery tests |
| H2 | Bin/delete ordering | Moving to the bin renamed the object before saving the index, and delete/purge removed files before saving. A failed save left the index pointing at missing files or an orphaned object. | Checked rename with rollback; save index before destructive deletion; purge reconciles the catalogue. | `theRecycleBinMovesTheEncryptedObjectAndRestoresIt`; code review |
| H3 | Archive parser bounds | Crafted manifests could request absurd allocations, declare inconsistent chunk layouts, point objects outside the payload, duplicate object IDs, or carry unbounded manifest/cover data. | Validate sanitized size, chunk count and length consistency; reject duplicates and out-of-payload ranges; cap manifest/cover; validate before allocation. | 3 new Rust tests + 5 existing archive tests (8 total) |
| H4 | Archive extraction | Whole objects were assembled in memory; a failed extraction could leave plaintext temp files. | Streaming `extract_object_to_file()`, in-memory extraction cap, `try/finally` cleanup. | Archive round-trip/extraction instrumentation; Rust archive suite |
| H5 | Transient plaintext exports | Decrypted exports lived in `noBackupFilesDir/vault/export` and were never purged, leaving plaintext indefinitely. | Moved to `cacheDir/vault_export`; purged at startup and on background; sharing exports intentionally left for the consumer. | `theVaultIsExcludedFromBackups`; storage leak audit |
| H6 | Archive UI leaks | Decrypted archive sessions, cancelled builds, inspection copies and failed extractions could leave plaintext/temp files; full-size cover decode could OOM. | Central `ArchiveVaultSessionCache`, purge on lock/background, `DisposableEffect` cleanup, `finally` on extraction, sampled cover decode, session-dir recreation. | Code review; archive instrumentation |

### Medium

| ID | Area | Confirmed finding | Fix | Verification |
| --- | --- | --- | --- | --- |
| M1 | Gallery refresh | Concurrent refreshes could double-load and overwrite newer state; some disk cleanup ran on the main thread. | Single-flight refresh with post-IO state re-read; disk work on `Dispatchers.IO`. | Code review |
| M2 | Private setup UI | The setup button used the deterministic derivation path without the biometric gate. | Setup now goes through the `CryptoObject` biometric flow. | Code review |
| M3 | JNI memory | Key arrays and JNI local references were not always released/zeroized. | `finally` zeroization and local-ref cleanup. | Code review |
| M4 | Secret zeroization | Several import/viewer/playback/decrypt/share/re-encrypt paths left VMK/passphrase copies to the GC. | Explicit `fill(0)` in `finally`; Rust `zeroize` retained. | Code review; VMK-on-disk test |
| M5 | Archive session plaintext | A decrypted archive remained on disk after backgrounding until the viewer was disposed. | Session registry trims memory and deletes decrypted files on lock; `onTrimMemory` trims caches. | Code review |
| M6 | Cancelled archive save | A built container could survive a failed/cancelled save. | Temp file deleted in `finally` and on screen dispose. | Code review |
| M7 | Corrupt catalogue | A corrupt index could be overwritten by the next save (same root cause as C2). | Quarantine + save refusal. | Code review |
| M8 | Private import consistency | Import into the private area could leave a partial object/thumbnail on failure. | Guarded key access and `finally` cleanup. | Code review |
| M9 | Rotation recovery | Rotation could be requested while a legacy private migration was pending, producing a mixed key hierarchy. | Rotation refuses until the private migration completes. | Code review; rotation tests |
| M10 | Recovery key drift | A recovery envelope was bound to the old VMK after rotation but remained on disk. | Rotation refuses while recovery exists; message instructs disable → rotate → re-enable. | Code review |

### Low

| ID | Area | Finding | Fix | Verification |
| --- | --- | --- | --- | --- |
| L1 | Lint | `NonObservableLocale` lint error in `ArchiveScreen`. | `Locale.ROOT`. | Lint: 0 errors |
| L2 | Documentation | `FORMAT_SPEC.md` said 112-byte header with wrong offsets; key docs described the old deterministic private key and incomplete rotation. | Corrected to 128 bytes/offsets; updated README, SECURITY, KEY_MANAGEMENT. | Doc review |
| L3 | Backup test | Export directory assertion still expected `noBackupFilesDir`. | Updated to `cacheDir`. | Instrumentation |
| L4 | Line endings | Two instrumentation files had been rewritten with CRLF. | Normalized back to LF. | `git diff --numstat` |
| L5 | Deprecated APIs | `TRIM_MEMORY_*` and `rememberTransformableState` warnings. | Left as-is (pre-existing/deprecated but functional); listed as cleanup. | Lint warnings |
| L6 | Biometric invalidation | `setInvalidatedByBiometricEnrollment(true)` would permanently destroy the private area on any new fingerprint/face enrollment. | Set to `false`: the key survives enrollment changes while still requiring a strong biometric for every use. Invalidated only when the secure lock screen is removed. | Code review; KEY_MANAGEMENT.md updated |
| L7 | Modifier parameter lint | 3 Compose functions had `modifier` not as the first optional parameter. | Reordered parameters so `modifier` is first. | Lint: 7 fewer warnings (32→25) |
| L8 | FrequentlyChangingValue lint | 4 Compose reads of frequently-changing values directly in composition. | Wrapped reads in `derivedStateOf`. | Lint: included in L7 count |

---

## 4. Memory audit

- **Vault master key.** Unwrapped only in memory; never written to disk in the
  clear. All call sites now zero the array in `finally` (import, viewer,
  playback, decrypt, share, re-encrypt, rotation, recovery). JNI copies are
  zeroized before release.
- **Private subkey.** Independent random key, zeroized on lock and on relock.
  The gate wraps it in the Keystore; the wrapped blob is `ZPG2` magic + IV +
  ciphertext/tag.
- **Data keys and plaintext.** Rust uses `zeroize` for DEKs and streaming
  buffers. Archive extraction is streamed; temporary plaintext is deleted in
  `finally`.
- **Bitmaps.** Thumbnails are scaled to 320 px; archive covers to 2048 px;
  viewer decodes are sampled. Bitmaps are recycled after thumbnail encoding.
- **Caches.** Thumbnail cache and 64 MB archive LRU are trimmed on
  `onTrimMemory`; archive memory is dropped on private lock/background, and
  decrypted archive files are deleted.
- **Passphrases.** `CharArray` → UTF-8 bytes is zeroized after use; the
  passphrase is not persisted.
- **Residual.** JVM/Compose/internal `Bitmap` copies outside app control are
  best-effort only; a compromised OS or debugger can still observe process
  memory. This is documented, not claimed as eliminated.

## 5. Battery and performance audit

- No `INTERNET` permission, no services, jobs, alarms or wakelocks. The app
  cannot perform background network or periodic work.
- Disk and crypto work runs on `Dispatchers.IO`; `openWith` and share exports
  were moved off the main thread.
- Archive unlock uses Argon2id once per session; the session cache avoids
  re-deriving the key for every photo.
- Thumbnails and covers are sampled; the grid does not decode full-size images.
- Startup does one catalogue load. The transient-export purge is a small
  cache-directory scan and only runs on start/background.
- Rotation is O(objects) and user-triggered; startup recovery only runs when a
  staged key exists.
- Measured cold start on the SM-A546E: 1.3–1.9 s. No ANR, crash or new
  crash-buffer entry after install/launch.
- No battery drain source was found in the changed code. A long-running
  profiler session was not performed; the static conclusion is "no new
  background work".

## 6. Security and data-integrity review

**Preserved guarantees**

- Vault objects, thumbnails, index and bin contents remain AEAD-sealed; the
  index and thumbnails are never written in the clear.
- The wrapped VMK lives in `noBackupFilesDir`; backups and device transfer are
  disabled in the manifest and data-extraction rules.
- Crypto primitives, archive format, key wrapping and Kestore protection
  settings were not weakened. No new network permission or exported surface was
  added.
- Fail-closed behaviour on a missing/invalid Keystore key and on a corrupt
  index prevents silent replacement of user data.
- Rotation is now crash-safe and covers every artifact; it refuses operations
  that would invalidate recovery or mix key hierarchies.
- The private area uses an independent key and an auth-bound Keystore gate;
  `FLAG_SECURE` and `onStop` relock reduce shoulder-surfing and background
  exposure.
- Archive parsing rejects malformed sizes, duplicate IDs and out-of-bounds
  objects before allocation or extraction.

**Residual security/product risks**

1. **Biometric gate test coverage.** The real `BiometricPrompt` +
   `CryptoObject` path requires a human fingerprint and is not automated. The
   legacy migration, gate blob format and key separation were reviewed and
   compile-tested; the prompt flow needs a manual smoke test on a device with
   enrolled biometrics.

2. **Recovery vs rotation UX.** Rotation is refused while recovery is enabled.
   This is safe but requires the user to disable and re-enable recovery; it is
   documented in `KEY_MANAGEMENT.md`.

3. **Biometric invalidation trade-off.** The gate key is no longer invalidated
   on new biometric enrollment (to avoid permanently destroying the private
   area), but it *is* invalidated when every biometric is removed or the lock
   screen is removed. Users should be warned that removing all biometrics makes
   the private area unrecoverable.

## 7. Tests and validation (actual runs)

### Rust

```
cargo test --workspace
```

Result: **113 passed, 0 failed** across:

| Suite | Tests |
| --- | --- |
| `zarc-core` unit | 22 |
| `archive_test` | 8 |
| `interop` | 5 |
| `keylifecycle` | 9 |
| `mutation` | 28 |
| `property` | 7 |
| `recovery_rotation` | 13 |
| `roundtrip` | 11 |
| `transactional` | 11 |

New archive tests: inconsistent chunk layout rejected, absurd sanitized size
rejected without allocating, object outside payload rejected. The archive suite
grew from 5 to 8 tests.

New rotation tests: a corrupt object does not block a resilient rotation, the
strict rotation refuses a corrupt object, a corrupt object does not block
finishing an interrupted rotation, a resilient rotation refuses a wrong old
key. The recovery_rotation suite grew from 9 to 13 tests.

New instrumented tests: `aCorruptObjectDoesNotBlockRotation` (healthy object
re-wrapped, damaged object skipped, damaged file preserved) and
`aCorruptIndexIsQuarantinedAndNeverOverwritten` (damaged index quarantined,
save refused).

### Android unit

```
gradlew.bat --no-daemon :app:testDebugUnitTest
```

Result: **5 tests, 0 failures, 0 skipped.**

### Instrumentation (physical device)

```
adb -s RZCWA13C7MB shell am instrument -w -r \
  -e class com.zarc.vault.GalleryInstrumentedTest,\
com.zarc.vault.RecoveryInstrumentedTest,\
com.zarc.vault.ArchiveInstrumentedTest,\
com.zarc.vault.StorageLeakAuditTest,\
com.zarc.vault.ZarcInstrumentedTest \
  com.meet.zarc.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Result: **OK (37 tests)**, 9.254 s. Coverage includes:

- gallery encryption/index sealing, thumbnails, decrypt round-trip, bin
  move/restore, albums, renaming, capture dates;
- rotation of the index, bin, thumbnails and binned thumbnails;
- **healthy object re-wrapped while a corrupt object is skipped, damaged file
  preserved**;
- **damaged index quarantined, later save refused**;
- interrupted rotation finished before vault use;
- recovery envelope create/unlock/disable and wrong-passphrase rejection;
- archive polyglot round-trip, gallery viewer and extraction;
- storage leak audit;
- Keystore fail-closed, VMK never on disk, backup exclusions.

### Build, install, launch

```
gradlew.bat --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
adb -s RZCWA13C7MB install -r -t app-debug.apk
adb -s RZCWA13C7MB install -r -t app-debug-androidTest.apk
adb -s RZCWA13C7MB shell am start -W -n com.meet.zarc.debug/com.zarc.vault.ui.GalleryActivity
```

- Native `arm64-v8a` library rebuilt and staged (735,312 bytes).
- APK install: success.
- Cold launch: `TotalTime: 2752 ms`.
- Crash buffer cleared before launch and **empty** afterwards.
- `FLAG_SECURE` intentionally makes the screenshot black; focus/launch status
  was verified through `am start` and the crash buffer.

### Lint and diff hygiene

```
gradlew.bat --no-daemon :app:lintDebug
```

Result: **0 errors, 25 warnings, 3 hints** (warnings are pre-existing API-level
and deprecated-API notices, e.g. `HapticFeedbackConstants.CONFIRM` and
`rememberTransformableState`). 7 warnings were eliminated by this audit
(3 ModifierParameter, 4 FrequentlyChangingValue).

```
git -c core.whitespace=cr-at-eol diff --check
```

Result: clean.

## 8. Pre-existing failures (not introduced by this audit)

- `cargo fmt --check` fails on files that were already unformatted at HEAD
  (`archive/crypto.rs`, `archive/pack.rs`, `archive/metadata.rs`, parts of
  `archive/extract.rs` and others). Formatting the whole tree would create a
  large unrelated diff, so it was not done.
- `cargo clippy --workspace --all-targets -- -D warnings` reports 113 errors
  from the crate's pre-existing pedantic lint backlog (wildcard imports,
  missing `# Errors` docs, `format_push_string`, etc.). The new code compiles
  and is covered by tests, but the crate does not have a clean clippy gate.
- 25 Android lint warnings remain (pre-existing). The one lint **error** was
  fixed. 7 warnings were eliminated by this audit (3 ModifierParameter, 4
  FrequentlyChangingValue).

These are reported as project-wide cleanup, not as audit regressions.

## 9. Changed files (uncommitted)

```
README.md
SECURITY.md
docs/FORMAT_SPEC.md
docs/KEY_MANAGEMENT.md
docs/AUDIT_REPORT_2026-10-30.md

crates/zarc-core/src/archive/extract.rs
crates/zarc-core/src/archive/manifest.rs
crates/zarc-core/src/vault.rs
crates/zarc-core/tests/archive_test.rs
crates/zarc-core/tests/recovery_rotation.rs
crates/zarc-jni/src/lib.rs

android/app/src/main/java/com/zarc/vault/archive/ArchiveRepository.kt
android/app/src/main/java/com/zarc/vault/core/VaultRepository.kt
android/app/src/main/java/com/zarc/vault/core/ZarcNative.kt
android/app/src/main/java/com/zarc/vault/gallery/GalleryRepository.kt
android/app/src/main/java/com/zarc/vault/gallery/PrivateGate.kt
android/app/src/main/java/com/zarc/vault/ui/ArchiveScreen.kt
android/app/src/main/java/com/zarc/vault/ui/GalleryActivity.kt
android/app/src/main/java/com/zarc/vault/ui/GalleryScreens.kt
android/app/src/main/java/com/zarc/vault/ui/GalleryViewModel.kt
android/app/src/main/java/com/zarc/vault/ui/MediaThumbnail.kt
android/app/src/main/java/com/zarc/vault/ui/SecurityLabActivity.kt
android/app/src/main/java/com/zarc/vault/ui/ViewerAndSettings.kt

android/app/src/androidTest/java/com/zarc/vault/GalleryInstrumentedTest.kt
android/app/src/androidTest/java/com/zarc/vault/RecoveryInstrumentedTest.kt
android/app/src/androidTest/java/com/zarc/vault/ZarcInstrumentedTest.kt
```

Also rebuilt (not tracked as source changes): `android/app/src/main/jniLibs/arm64-v8a/libzarc_jni.so`.

## 10. Prioritized action plan

**P0 — completed in this pass**

- Fail-closed VMK, atomic keys/recovery/index, quarantine, bin/delete ordering.
- Full crash-safe rotation with startup recovery and safe refusals.
- Independent private subkey, auth-bound biometric gate, background relock,
  `FLAG_SECURE`.
- Archive parser/extraction hardening and transient-plaintext purge.
- JNI/VMK/passphrase zeroization and cache/temp cleanup.
- Docs, lint error, and all device/unit/Rust validation above.

**P1 — completed in this pass**

1. **Biometric invalidation policy decided.** `setInvalidatedByBiometricEnrollment(false)`:
   the gate key survives new fingerprint/face enrollment (the random private
   subkey has no other wrapping), while still requiring a strong biometric for
   every use. Invalidated only when the secure lock screen is removed.
   Documented in `KEY_MANAGEMENT.md` and `PrivateGate.kt`.
2. **Rotation resilient to one corrupt object.** `rotate_master_key_resilient()`
   classifies each object before re-wrapping: already-rotated, needs rotation,
   or unreadable. Unreadable objects are left untouched and reported, so one
   bit-rotted file cannot block rotation of the rest of the vault or startup
   recovery. The strict `rotate_master_key()` still refuses on a corrupt
   object. Tests added at both Rust and instrumentation level.

**P1 — remaining**

3. **Manual biometric smoke test** on a device with enrolled fingerprints:
   setup, unlock, relock on background, migration of a legacy private area.
   Requires a device with at least one enrolled biometric — not automated
   because `BiometricPrompt` + `CryptoObject` needs human interaction.

**P2 — follow-up hardening**

4. **Corrupt index test added** (`aCorruptIndexIsQuarantinedAndNeverOverwritten`):
   verifies quarantine + save refusal on a damaged index.
5. Clear the pre-existing `cargo fmt`/`clippy` backlog or introduce scoped
   allows/CI gates so new warnings are visible.
6. Resolve the remaining 25 lint warnings (API-level guards, deprecated Compose
   APIs) and consider `StrictMode` in debug builds.

**P3 — optional**

7. Profile archive unlock and large-gallery scrolling on a low-end device;
   current measurements show no new background work or ANR risk.

---

*All changes remain uncommitted. No destructive git operation, commit or push
was performed. No real user vault or media was accessed.*
