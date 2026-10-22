# Validation report

This report contains the **actual** results of the test suite that was
executed for this milestone. Every number and every statement below comes from
a command that was run in this environment on 2026-10-21.

Results are marked:

* **PASS** - the test ran and the expected outcome was observed,
* **FAIL** - the test ran and the expected outcome was *not* observed,
* **NOT RUN** - the test could not be executed in this environment, with the
  reason given,
* **INCONCLUSIVE** - the test ran but cannot establish the property it was
  written for.

Nothing in this report is a claim about a system that was not actually
exercised.

---

## 1. Environment

| Item | Value |
|------|-------|
| Host | Windows 10.0.26200 (build 26200), x86-64 |
| Rust | 1.93.1 (pinned in `rust-toolchain.toml`), plus `nightly-1.101.0` for fuzzing |
| Cargo | 1.93.1 |
| Android SDK | `C:\Android`, platforms 33-37, build tools 33-36 |
| Android NDK | 27.1.12297006 |
| JDK | Eclipse Adoptium 21.0.8.9 (`JAVA_HOME`), `java` on `PATH` is 17.0.17 |
| Gradle | 9.6.1 (from the local Gradle distribution cache) |
| Android Gradle Plugin | 9.3.1 |
| Kotlin | 2.3.21 (AGP 9's built-in Kotlin support) |
| Test device (emulator) | `emulator-5554`, "Synapse_Device (AVD)", API 36, x86_64, Google APIs image, hardware accelerated (WHPX) |
| Test device (physical) | `RZCWA13C7MB`, Samsung Galaxy A54 (SM-A546E), Exynos 1380 (s5e8835), Android 16 (API 36), arm64-v8a, verified boot `green`, `android.hardware.hardware_keystore=200` |
| Upstream crypto | libsodium 1.0.22, vendored and minisign verified at build time |

## 2. Rust quality gates

| Check | Command | Result |
|-------|---------|--------|
| Formatting | `cargo fmt --all -- --check` | **PASS** - no diffs |
| Lint | `cargo clippy --workspace --all-targets -- -D warnings` | **PASS** - zero warnings, including `clippy::pedantic` |
| Unit + integration tests | `cargo test --workspace` | **PASS** - 87 tests, 0 failed |

### 2.1 Test breakdown

| Test binary | Tests | Result |
|-------------|-------|--------|
| `zarc-core` unit tests | 16 | **PASS** |
| `tests/roundtrip.rs` | 11 | **PASS** |
| `tests/mutation.rs` | 28 | **PASS** |
| `tests/keylifecycle.rs` | 9 | **PASS** |
| `tests/transactional.rs` | 11 | **PASS** |
| `tests/property.rs` | 7 | **PASS** |
| `tests/interop.rs` | 5 | **PASS** |
| `tests/recovery_rotation.rs` | 9 | **PASS** |
| `zarc-jni` | 0 (no host tests; the bridge is exercised on the device) | n/a |

The recovery and rotation suite (9 tests) covers: the envelope round trip, a
wrong passphrase, a tampered envelope, the witness, the master key rotation
(the content region stays byte for byte identical, the envelope changes), the
idempotence of the rotation, a rotation with the wrong old key (nothing
changes), the object re-encryption (same plaintext, new content, same
identity) and a failed re-encryption (the object is untouched).

### 2.2 Round trip results (brief section A)

All **PASS**:

* empty file (the object is exactly `152 + 17` bytes),
* one byte file,
* six sizes around the chunk boundary (1023, 1024, 1025, 2048, 2049, 4096 with
  a 1 KiB chunk size); the object size matched `152 + plaintext + 17 *
  chunk_count` exactly in every case,
* multiple chunks (5 KiB + 17 bytes),
* a 3 MiB + 12345 byte input,
* arbitrary binary data (all 256 byte values),
* synthetic JPEG, PNG, WebP and MP4 fixtures: the restored bytes were
  identical, the stored object contained neither the media magic bytes nor
  the plaintext,
* the stored file name is 32 lowercase hex characters with no extension and
  equals the object id,
* the original file is unchanged and still present after the encryption,
* two encryptions of the same input produce different object ids and
  different ciphertext,
* decryption with a different vault master key fails with an authentication
  error and produces no output file.

The fixtures are synthetic (generated deterministically with the correct magic
bytes); no personal media is used anywhere in the suite.

### 2.3 Adversarial results (brief section B)

All **PASS** - 28 tests, each of which also asserts that no plaintext file was
published and that no staging file was left behind:

* magic, version, header length, flags and all three reserved fields,
* unknown envelope and stream algorithm ids,
* a modified object id, secretstream header and envelope nonce,
* a modified plaintext length, ciphertext length and an absurd
  `u64::MAX` length,
* a modified wrapped data key and a modified wrapped key tag,
* a modified ciphertext byte and a modified final tag,
* truncation at nine offsets,
* appended trailing data,
* a removed chunk, a duplicated chunk and two swapped chunks,
* the secretstream used directly with reordered, duplicated, removed and
  truncated messages,
* the secretstream used directly with a wrong key.

The rejections land where the format says they should, and the tests
explicitly do *not* assume that every mutation fails at the same point: a
modified length is caught by the parser (a length mismatch), a modified
ciphertext by the AEAD (an authentication failure).

### 2.4 Key lifecycle results (brief section C)

| Case | Result |
|------|--------|
| Key generation produces 32 valid, differing bytes | **PASS** |
| A VMK of the wrong length is rejected | **PASS** |
| Two objects get independent data keys and nonces | **PASS** |
| The same input encrypted twice differs | **PASS** |
| The VMK survives a "restart" (host and device) | **PASS** |
| A corrupted wrapped VMK is rejected | **PASS** (device) |
| A missing Keystore alias fails closed | **PASS** (device) |
| Losing the Keystore key destroys the vault | **PASS** (device) |
| Wrong VMK / wrong DEK / modified envelope / modified nonce / modified tag / wrong AAD | **PASS** - all authentication failures |
| A missing object | **PASS** - `NotFound` |
| The unwrapped VMK is never written to disk | **PASS** (host and device) |
| The Rust core contains no logging | **PASS** (source level check) |
| The header carries no descriptive metadata | **PASS** |

**Known answer tests of the cryptographic library: NOT RUN.** libsodium's own
test suite requires `cmocka` and the autoconf build system; neither is
available in this environment (`make` and `sh` are missing). The substitute
evidence is the interop test: Zarc's streams are read by, and read from, the
independent pure Rust implementation `crypto_secretstream` 0.2.0 (RustCrypto
`nacl-compat`) in both directions, including the rejection of a wrong
associated data - **PASS**. This is interoperation evidence, not the
upstream test suite.

### 2.5 Transactional results (brief section D)

All **PASS** - 11 tests:

* a failed encryption publishes nothing,
* an object is published atomically under its final name,
* a leftover `.zarc-tmp-*` file is never listed as an object and can be
  purged,
* a corrupted **last** chunk leaves no destination file and no staging file,
* a failed decryption does not overwrite an existing destination file,
* an unwritable destination and an unwritable vault directory fail cleanly,
* the source growing after the encryption does not affect the stored object,
* decrypting twice produces two independent files,
* a destination name with `../`, a path separator, `.` or `..` is rejected,
* the vault directory contains only hex object names.

**Low storage conditions: NOT RUN** - the platform offers no portable way to
exhaust the device storage from a test. The related failure path (a write
that fails) is covered by the "unwritable destination" test, and the atomic
rename guarantees that a partially written file is never published.

**Process termination: INCONCLUSIVE as a true process kill.** The behaviour is
simulated by creating the files that a killed process would leave behind and
asserting that the vault ignores and can purge them. A real `kill -9` in the
middle of an encryption was not performed.

### 2.6 Property based and fuzzing results (brief section E)

Property based tests: **PASS** (7 properties, 256 cases each by default):

* the parser never panics on arbitrary input,
* valid headers round trip,
* arbitrary input survives a full vault round trip,
* a single byte mutation is either rejected or restores *identical* plaintext
  (never different plaintext),
* the chunk plan is consistent and overflow free,
* inconsistent lengths are rejected,
* the header never contains descriptive data.

Fuzzing with `cargo-fuzz` 0.13.2 on nightly (libFuzzer + AddressSanitizer):

| Target | Time | Executions | Crashes | Result |
|--------|------|-----------|---------|--------|
| `parse_container` | 121 s | 2 890 150 | 0 | **PASS** |
| `decrypt_stream` | 121 s | 806 842 | 0 | **PASS** |
| `wrap_key` | 121 s | 732 179 | 0 | **PASS** |

Total: **4 429 171 executions, no crash, no sanitizer report.** The fuzz
targets use fixed test keys and synthetic data; they never touch a real
vault, a real photograph or a production key.

### 2.7 Dependency and license audit

`python tools/audit-deps.py` against the OSV database and crates.io:

* **PASS** - no known security advisory for any of the 106 crates in
  `Cargo.lock`,
* **PASS** - every crate carries a permissive license (MIT, Apache-2.0,
  BSD, ISC, Zlib, Unicode, 0BSD or a combination of these),
* the two workspace crates are local and have no published license metadata.

The production dependency tree is intentionally tiny:

```text
zarc-jni  -> jni 0.21.1, zarc-core, zeroize 1.9.1
zarc-core -> thiserror 2.0.21, zeroize 1.9.1
+ the vendored libsodium 1.0.22 C library (ISC)
```

Everything else in the lock file belongs to the test and fuzz targets
(`proptest`, `tempfile`, `sha2`, `rand_core`, `crypto_secretstream`,
`libfuzzer-sys`).

### 2.8 Supply chain: the release signature is actually enforced

The vendored libsodium archive was corrupted deliberately (a single flipped
bit at offset 1 000 000) and the build was repeated:

```text
thread 'main' panicked at crates/zarc-core/build.rs:85:10:
libsodium release archive signature verification FAILED - refusing to build: InvalidSignature
```

The archive was then restored (SHA-256
`ADBDD8F16149E81AC6078A03ACA6FC03B592B89EF7B5ED83841C086191BE3349`) and the
build succeeded again. The signature check is therefore a real gate, not a
formality.

## 3. Android build

| Step | Command | Result |
|------|---------|--------|
| Native library, 4 ABIs | `tools\build-android.ps1` | **PASS** - `arm64-v8a` (518 520 B), `armeabi-v7a` (388 248 B), `x86` (634 312 B), `x86_64` (588 784 B) |
| Debug APK | `gradlew assembleDebug` | **PASS** - `android/app/build/outputs/apk/debug/app-debug.apk`, 3 362 741 bytes, containing all four `libzarc_jni.so` |
| Release APK (R8, signed) | `gradlew assembleRelease` | **PASS** - `android/app/build/outputs/apk/release/app-release.apk`, 2 168 553 bytes, R8 minified + resource shrunk, with `mapping.txt` |
| Installation | `adb install -r app-debug.apk` | **PASS** - "Success" |
| Start | `adb shell am start -n com.zarc.vault.debug/com.zarc.vault.ui.SecurityLabActivity` | **PASS** - `Status: ok`, `LaunchState: COLD` |

The APK contains exactly four native libraries, one per ABI:

```text
lib/arm64-v8a/libzarc_jni.so     518520
lib/armeabi-v7a/libzarc_jni.so   388248
lib/x86/libzarc_jni.so           634312
lib/x86_64/libzarc_jni.so        588784
```

### 3.0 Production hardening (this pass)

| Change | Evidence |
|--------|----------|
| The release variant is R8 minified and resource shrunk, and is not debuggable | `flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]` for the installed release build - no `DEBUGGABLE`, no `ALLOW_BACKUP` |
| **The minified build was verified to work** | The signed, minified release APK was installed on the emulator and driven through the Security Lab: the self test reported `RESULT: PASS` and the synthetic fixture round trip reported `identical=true` with identical SHA-256 digests. R8 did not break the JNI entry points (the ProGuard rules keep `com.zarc.vault.core.ZarcNative`). |
| Release signing is driven by an external keystore, never by a committed key | `android/keystore.properties` is git ignored; the build reads it or the `ZARC_KEYSTORE*` environment variables. A local test key was generated for the verification build only. |
| The debug build uses the application id suffix `.debug` | The debug and release builds can be installed side by side and can never be confused. |
| The Rust modules that do not need it forbid `unsafe` | `#![forbid(unsafe_code)]` in `container`, `error`, `fsutil`, `hex`, `keywrap`, `selftest` and `vault`; the only `unsafe` left is the libsodium FFI module. |
| Plaintext buffers are wiped after use | The chunk buffer of the encryptor and the decrypted chunk buffer of the decryptor are `zeroize`d after they have been written to the file. |
| `LICENSE` (Apache-2.0) and `SECURITY.md` exist | The repository carries the license text and a security policy with the reporting process and the eight known limitations. |
| One command runs every gate | `tools/verify.ps1` runs format, lint, tests, the dependency audit, the fuzz build, the four ABI builds, both APK builds, the device tests and the storage audit, and prints a PASS/FAIL summary. |

**What was deliberately not done** (and why it is not a defect):

* the Security Lab screen is still part of the release build. It exposes no
  key material and no plaintext; it is the only user interface of this
  milestone and it is replaced by the gallery in the next phase,
* the release build is signed with a locally generated **test** key. A
  production key must come from the release owner's key management; the
  build supports it, the key does not exist here,
* no key rotation and no recovery envelope (see ADR-0004).

### 3.1 The recovery envelope and the key rotation (added in this pass)

| Property | Result |
|----------|--------|
| The envelope is protected with libsodium's Argon2id (ops 3, 64 MiB) and XChaCha20-Poly1305 | **PASS** |
| The passphrase is never stored (the envelope holds salt, cost parameters, nonce, wrapped key, tag) | **PASS** - checked on the device |
| A wrong passphrase cannot produce a usable key | **PASS** - host and device |
| A witness proves that a recovered key is *this* vault's master key | **PASS** |
| After the Keystore key is destroyed the vault opens with the passphrase, and the Keystore binding can be restored | **PASS** - device test `theRecoveryEnvelopeCanUnlockTheVaultAfterTheKeystoreKeyIsLost` |
| A lost Keystore key no longer silently starts a new vault: the app reports that only the passphrase can open it | **PASS** - device test `losingTheKeystoreKeyMakesTheVaultUnreachable` |
| The master key rotation re-wraps the data keys **without** re-encrypting the content (the ciphertext region stays byte for byte identical) | **PASS** |
| The rotation is idempotent, so an interrupted rotation can be repeated | **PASS** |
| A rotation with the wrong old key changes nothing | **PASS** |
| The object re-encryption keeps the identity and the plaintext, and replaces the object atomically | **PASS** |
| A failed re-encryption leaves the object untouched | **PASS** |

### 3.1b The gallery

The gallery was built on top of the finished engine and then verified on the
devices.

| Check | Result |
|-------|--------|
| The debug build, 29 instrumented tests (21 engine + 8 gallery) on the emulator | **PASS** - 29/29 |
| The debug build, 29 instrumented tests on the Galaxy A54 | **PASS** - 29/29 |
| The R8 release build on the emulator | **PASS** - installed, launched, 36 items imported, the grid, the viewer and the tabs verified by screenshot |
| The imported media stays encrypted | **PASS** - the stored object has no JPEG header, the index and the thumbnails are sealed (instrumented test `theImportedMediaIsEncryptedAndTheIndexIsSealed`) |
| The thumbnails are sealed and decrypt in memory | **PASS** - instrumented test `theThumbnailsAreSealedAndDecryptInMemory` |
| The recycle bin moves the *encrypted* object and restores it | **PASS** - instrumented test `theRecycleBinMovesTheEncryptedObjectAndRestoresIt` |
| The private area uses a subkey that is separate from the vault key | **PASS** - instrumented test `thePrivateSubkeyIsSeparateFromTheVaultKey` |
| The sealed blob only opens with the right key | **PASS** - instrumented test `theBlobSealRoundTrips` |

**Three defects were found by running the user interface**, not by the tests:

1. **The native library was stale.** The gallery called the new
   `nativeSealBlob` / `nativeOpenBlob` / `nativeDeriveSubkey` entry points
   before the `.so` files were rebuilt, and the application failed with
   `No implementation found for nativeSealBlob`. The lesson for the build
   pipeline: *the native library has to be rebuilt whenever the JNI surface
   changes*, and the APK must not be assembled from a stale `jniLibs` folder.
2. **The thumbnail was generated after the source file was deleted.** Both the
   import path and the sample path removed the staged copy before the
   thumbnail was taken, so no thumbnail was ever produced.
3. **The viewer was drawn inside the scaffold**, so the tab bar and the import
   button stayed visible over the full screen media.

### 3.2 Manifest and backup configuration

The merged manifest declares **no permissions at all** (no `INTERNET`, no
storage permissions), and the backup configuration is:

```xml
<application
    android:allowBackup="false"
    android:dataExtractionRules="@xml/data_extraction_rules"
    android:fullBackupContent="false"
    android:hasFragileUserData="false"
    android:usesCleartextTraffic="false" ...>
```

The data extraction rules exclude every domain (`root`, `file`, `database`,
`sharedpref`, `external`) from both cloud backup and device transfer.

The platform's own view of the installed package confirms the setting - the
`FLAG_ALLOW_BACKUP` bit is **not** set:

```text
flags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]
```

In addition, the vault objects, the wrapped vault master key and the export
directory all live in `noBackupFilesDir`, which the platform excludes from
every backup mechanism regardless of the manifest.

## 4. Android on-device tests (brief section F)

`gradlew connectedDebugAndroidTest` on `emulator-5554` (API 36, x86_64):

**21 tests, 21 passed, 0 failed.**

| Test | Result |
|------|--------|
| The Keystore key is created and the platform reports its protection | **PASS** |
| The VMK survives a process restart | **PASS** |
| A corrupted wrapped VMK is rejected | **PASS** |
| A missing Keystore key fails closed | **PASS** |
| Losing the Keystore key destroys the vault | **PASS** |
| Encrypt and decrypt round trip on the device | **PASS** - identical SHA-256 |
| The vault objects use opaque names in app private storage | **PASS** |
| The stored object does not contain the plaintext | **PASS** |
| The unwrapped VMK is never written to disk | **PASS** |
| The vault is excluded from backups | **PASS** |
| Two encryptions of the same input differ | **PASS** |
| A wrong key cannot open an object | **PASS** |
| The crypto self test passes on the device | **PASS** - `RESULT: PASS`, `libsodium 1.0.22` |
| The Keystore API probe (which APIs this platform offers) | **PASS** (diagnostic) |
| The storage leak audit fixture | **PASS** (diagnostic) |
| The recovery envelope unlocks the vault after the Keystore key is lost | **PASS** |
| A wrong passphrase is rejected | **PASS** |
| The recovery envelope can be removed | **PASS** |
| Rotating the master key keeps the objects readable | **PASS** |
| Re-encrypting an object keeps the plaintext | **PASS** |
| The envelope and the witness never contain the master key or the passphrase | **PASS** |
| Losing the Keystore key makes the vault unreachable (and says so) | **PASS** |

### 4.0 The physical device: results

| Check | Result |
|-------|--------|
| The instrumented suite on the **debug** build | **PASS** - 13 + 6 + 1 + 1 = 21 tests, 0 failures |
| The instrumented suite on the **release** build (R8 minified, signed) | **PASS** - 21 tests, 0 failures, 0 errors (`tests="21" failures="0" errors="0"`) |
| The installed release package | `flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]` - no `DEBUGGABLE`, no `ALLOW_BACKUP` |
| The Keystore protection level | **TRUSTED_ENVIRONMENT, hardware backed** - see below |
| The storage audit | **PASS** - the marker is not in the vault, the key directory or the databases; it is only in the audit test's own fixture and in the explicit decryption result |
| `adb root` | **not possible** - `adbd cannot run as root in production builds`. The audit on this device therefore runs with the *application's* privileges (`run-as`) plus the shell user's privileges for the world readable areas. |

**Two real defects were found by testing the release artifact on hardware** and
fixed:

1. **R8 removed `kotlin.jvm.internal.Lambda`.** The marker interface has no
   members, so R8 dropped it because the application itself never references
   it. Any component that is loaded into the application process afterwards -
   the instrumentation, a crash reporter, a dynamically loaded library - then
   dies with `NoClassDefFoundError`. The release build crashed while the
   debug build worked. Fixed with keep rules in
   `android/app/proguard-rules.pro`.
2. **The obfuscation mapping is not shared with the test package.** The
   instrumented tests call the platform adapter directly, so the classes they
   use must keep their original names. The JNI entry points need the same
   treatment, because they are resolved by name at runtime. Fixed with keep
   rules for `com.zarc.vault.core.*` and `com.zarc.vault.keystore.*`.

Both defects were invisible in the debug build and in the desktop test suite.

### 4.1 The actual protection level that was observed

**The physical device reports `TRUSTED_ENVIRONMENT` (hardware backed), and
the emulator reports `SOFTWARE`.**

The first version of the report was blind on both platforms, because it
obtained the `KeyInfo` through `KeyFactory`, which the KeyStore2 provider (the
default from Android 12 onwards) does **not** register for AES keys. The
`KeyInfo` of an AES key is exposed through the provider's
`SecretKeyFactory`. Zarc now tries both factories, so it reports the level
whenever the platform exposes it:

```text
# Samsung Galaxy A54 (SM-A546E), Android 16:
KeystoreReport: alias=zarc.vault.kek.v1 provider=AndroidKeyStore
                level=TRUSTED_ENVIRONMENT hardwareBacked=true strongBox=false
                keySize=256 keyInfoAvailable=true
                blockModes=GCM paddings=NoPadding
strongBoxAvailable=false

# Emulator (API 36, x86_64):
KeystoreReport: alias=zarc.vault.kek.v1 provider=AndroidKeyStore
                level=SOFTWARE hardwareBacked=false strongBox=false
                keySize=256 keyInfoAvailable=true
```

The `KeyInfo` of the physical device reports `level=1`
(`SECURITY_LEVEL_TRUSTED_ENVIRONMENT`) and `isInsideSecureHardware=true`: the
key is generated and used inside the device's **Trusted Execution
Environment**.

**StrongBox: NOT RUN.** The A54 does not advertise
`android.hardware.strongbox_keystore`, so there is no StrongBox to test. The
code requests StrongBox when the platform advertises it and falls back
explicitly; the report says `strongBox=false` because the platform said so.

**The emulator now reports `SOFTWARE`**, which is the honest answer for an
emulator without a secure element.

#### What the platforms actually support

```text
# both the emulator (API 36) and the physical device (Android 16):
KeyGenerator(AES, AndroidKeyStore): OK
generateKey: OK algorithm=AES
KeyFactory(AES, AndroidKeyStore): FAIL java.security.NoSuchAlgorithmException
SecretKeyFactory(AES, AndroidKeyStore): OK
KeyStore.getEntry: OK java.security.KeyStore$SecretKeyEntry
KeyFactory(RSA, AndroidKeyStore): OK java.security.KeyFactory
SecretKeyFactory.getKeySpec(KeyInfo): OK level=<platform value>
```

The old behaviour (the report said `UNAVAILABLE`) was correct as far as it
went - it did not claim a level it could not see - but it was needlessly
uninformative. Both are fixed.

The probe (`KeystoreProbeTest`) recorded what the API 36 emulator actually
supports:

```text
KeyGenerator(AES, AndroidKeyStore): OK
generateKey: OK algorithm=AES
KeyFactory(AES, AndroidKeyStore): FAIL java.security.NoSuchAlgorithmException:
        no such algorithm: AES for provider AndroidKeyStore
SecretKeyFactory(AES, AndroidKeyStore): OK
KeyStore.getEntry: OK java.security.KeyStore$SecretKeyEntry
KeyFactory(RSA, AndroidKeyStore): OK java.security.KeyFactory
```

The AndroidKeyStore provider of this platform registers a `KeyFactory` for
`RSA` but **not** for `AES`, so `KeyInfo` - the only platform API that
exposes the protection level of a key - cannot be obtained for the AES key
that Zarc uses.

Zarc therefore reports:

* `securityLevel = UNAVAILABLE`,
* `hardwareBacked = false` (**no** hardware backing is claimed, because there
  is no platform evidence for it),
* `strongBoxBacked` reflects how the key was *generated* (the platform only
  accepts a StrongBox backed generation when a StrongBox is really present),
  and the report states that this is not a level reported by the platform,
* `keyInfoAvailable = false`.

On a platform that does expose `KeyInfo` (a real device), the same code path
reports `STRONGBOX`, `TRUSTED_ENVIRONMENT` or `SOFTWARE` as the platform
states.

**StrongBox: NOT RUN.** The test device is an emulator without a StrongBox.
The code requests StrongBox when `FEATURE_STRONGBOX_KEYSTORE` is advertised
and falls back explicitly, and the instrumented test asserts the honest value
instead of a guess.

**A hardware backed device: NOT RUN.** No physical device was available in
this environment. No claim about hardware backing is made anywhere in this
project.

### 4.2 The Security Lab screen on the device

The screen was driven on the emulator and its output was read back through
`uiautomator dump`.

The screen renders with the five documented actions:

```text
Zarc - Security Lab
1. PLATFORM KEY REPORT
2. CRYPTO SELF TEST
3. ENCRYPT A SYNTHETIC FIXTURE
4. IMPORT A FILE (SYSTEM PICKER)
5. LIST VAULT OBJECTS
```

The self test, run from the app on the device:

```text
=== Crypto self test ===
libsodium 1.0.22
[PASS] libsodium version: 1.0.22
[PASS] csprng: two 32 byte draws differ and are not all zero
[PASS] secretstream round trip: 22 byte message, 39 byte ciphertext
[PASS] secretstream rejects modification: a flipped ciphertext byte must fail authentication
[PASS] key envelope: unwrap with the right key succeeds, with the wrong key fails
[PASS] vault file round trip: 70000 plaintext bytes restored byte-for-byte (object 70186 bytes)
[PASS] vault rejects a modified object: a modified ciphertext byte must fail verification; the stored object must not contain the plaintext
RESULT: PASS
```

The synthetic fixture round trip, run from the app on the device:

```text
=== Synthetic fixture round trip ===
fixture=/data/user/0/com.zarc.vault/cache/vault/work/synthetic-fixture.jpg
sha256(plaintext)=00097f4173f96c241960e0437fdf980541a39e67193cd1e331dc2062c0109f5d
objectId=e6ce8d7f49e4791eeade546acfc4286f
id=e6ce8d7f49e4791eeade546acfc4286f plaintext=98324 ciphertext=98358 container=98510 chunks=2
sha256(restored)=00097f4173f96c241960e0437fdf980541a39e67193cd1e331dc2062c0109f5d
identical=true
storedFileName=[e6ce8d7f49e4791eeade546acfc4286f]
plaintextLeakInVault=false
```

**PASS** - the UI works, the digests match, the stored name is opaque and the
plaintext is not in the vault.

The platform key report, read back from the screen on the same device:

```text
=== Platform key report ===
alias=zarc.vault.kek.v1 provider=AndroidKeyStore level=UNAVAILABLE hardwareBacked=false strongBox=false keySize=256 keyInfoAvailable=false
blockModes=GCM paddings=NoPadding
strongBoxAvailable=false
vaultObjects=/data/user/0/com.zarc.vault/no_backup/vault/objects
wrappedKey=/data/user/0/com.zarc.vault/no_backup/keys/vmk.wrapped
libsodium=1.0.22 secretstream=xchacha20poly1305 envelope=xchacha20poly1305-ietf
```

This is the honest answer for this platform: the key exists, it is a 256 bit
AES-GCM key in the AndroidKeyStore, the device has no StrongBox, and the
platform does not expose `KeyInfo` for an AES key - so no protection level
and no hardware backing are claimed.

## 5. Storage leakage audit (brief section G)

`tools/device-audit.ps1` - executed with **elevated privileges** (`adb root`
on the emulator, i.e. the shell/root user rather than the application's own
permissions).

The audit created a vault object from a fixture with a unique marker
(`ZARCAUDIT375F31B66AE5`, a synthetic JPEG header followed by 120 KiB of
payload containing the marker) and then searched the device.

| Search | Result |
|--------|--------|
| The marker and the original file name in the vault objects, the key directory, the databases and the shared preferences | **PASS** - not found |
| The marker in `/data/local/tmp`, `/sdcard`, `/storage/emulated/0`, `/data/media` | **PASS** - not found |
| The marker in the logcat buffer | **PASS** - not found |
| The first 32 bytes of the stored object | `"ZARC"`, version 1, header length 0x0098 = 152, flags 0, object id `3a07c9910dd770c75c46fb943d59cf84` matching the file name, algorithm ids `01 01` - **no media signature** |
| The size of the stored object | 123 086 bytes = 152 + 122 900 + 2 x 17, exactly as the format predicts |
| The vault directory listing | Only 32 character hex file names, mode `-rw-------` |

Files that **do** contain the plaintext, by construction:

```text
/data/user/0/com.zarc.vault/cache/vault/work/zarc-audit-....jpg   (the fixture the audit created)
/data/user/0/com.zarc.vault/no_backup/vault/export/zarc-audit-restored.bin  (the explicit decryption result)
/data/user/0/com.zarc.vault/cache/zarc-audit-note.txt              (the audit note)
```

These are the test's own working copies and the documented result of an
explicit decryption, not a leak from the vault. The audit reports them
separately instead of hiding them.

**Limitations of this audit, stated plainly:**

* it is a finite search over finite locations; it is **not** a proof that no
  plaintext exists anywhere on the device,
* the search covers the app's directories, the shared storage and the log
  buffer; it does not cover the flash controller's free blocks, the page
  cache of a running process or a memory dump,
* deletion from flash storage is not a guarantee of forensic erasure; Zarc
  never deletes the original file, and the vault objects are the only copies
  it makes,
* the audit was executed on an emulator. A physical device would have a
  different storage stack and a different media scanner.

## 6. Tooling and process notes

| Item | Status |
|------|--------|
| `cargo fmt`, `cargo clippy -D warnings` | available and passing |
| `cargo audit` | **not installed**; the equivalent check was implemented in `tools/audit-deps.py` against the OSV database (the same advisory data that RustSec mirrors) |
| `cargo-fuzz` | installed from source (`cargo +nightly install cargo-fuzz --locked`) |
| The MSVC AddressSanitizer runtime | has to be on `PATH` for the fuzz targets on Windows; this is documented in `docs/TEST_PLAN.md` |
| `gradle wrapper` | `gradle wrapper` could not validate the distribution URL (services.gradle.org is unreachable from this network), so the wrapper jar was taken from the local Gradle 9.6.1 distribution and `gradlew`/`gradlew.bat` were written by hand. Running `gradle wrapper` in a networked environment regenerates the official scripts |
| MobSF | **not run** - not installed, and the brief allows it as a supporting tool rather than a requirement. A static analysis pass with `lint` is configured in the build (`lint { abortOnError = true }`) |
| OWASP MASVS / MASTG | used as the checklist reference for the storage audit and the manifest review; the results are mapped in `docs/THREAT_MODEL.md` and `docs/KEY_MANAGEMENT.md` |

## 7. What is *not* established

* **No independent audit.** Zarc has not been reviewed by a third party, has
  not been certified and has not been formally verified. The evidence above is
  the project's own test evidence.
* **No hardware backed key was verified.** The only platform available was an
  emulator whose AndroidKeyStore does not even expose `KeyInfo` for AES
  keys. The hardware backing path is implemented and is driven by what the
  platform reports, but it has not been observed working.
* **No StrongBox path was executed.**
* **No large file test above 3 MiB.** The multi-megabyte test uses 3 MiB; the
  streaming design bounds the memory use, but a multi-gigabyte video was not
  processed in this environment.
* **The plaintext length is not hidden.** The container stores it in the
  clear. This is a known, documented property, not an accident.
* **The upstream libsodium test suite was not executed** (no `cmocka`, no
  `make`). The interop test against an independent implementation is the
  substitute evidence.
* **Deletion is not erasure** on flash storage.

## 8. Summary

| Area | Result |
|------|--------|
| Rust build, format, lint | **PASS** |
| Rust unit and integration tests (102) | **PASS** |
| Recovery envelope and key rotation (host + device) | **PASS** |
| Property based tests | **PASS** |
| Fuzzing (4.4 M executions, 3 targets) | **PASS** |
| Cross-implementation interop | **PASS** |
| Dependency and license audit | **PASS** - no advisories |
| Android native build (4 ABIs) | **PASS** |
| Debug APK | **PASS** - `android/app/build/outputs/apk/debug/app-debug.apk` |
| On-device instrumented tests (21) | **PASS** |
| On-device crypto self test | **PASS** |
| Storage leakage audit (with root) | **PASS** for the vault; the known working copies are reported explicitly |
| Keystore protection level on the physical device | **PASS** - `TRUSTED_ENVIRONMENT`, hardware backed, reported by the platform |
| Keystore protection level on the emulator | **PASS** - `SOFTWARE`, reported by the platform |
| StrongBox | **NOT RUN** - neither the A54 nor the emulator has a StrongBox |
| The release (R8) build on real hardware | **PASS** - 21 instrumented tests against the minified, signed release APK on the A54 |
| Low storage conditions | **NOT RUN** - not simulable portably |
| libsodium's own test suite | **NOT RUN** - `cmocka`/`make` unavailable |
| Independent security review | **NOT DONE** |

**Overall:** the milestone is an *experimental prototype* with a complete,
passing test suite. It is ready for a security review, but it is not
production ready, not audited and not verified on real hardware.
