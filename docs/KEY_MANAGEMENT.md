# Key management

This document describes the Zarc key hierarchy, the lifecycle of every key,
what happens when a key is lost, and the limitations of the current design.

## The hierarchy

```text
┌──────────────────────────────────────────────────────────────────┐
│ Android Keystore                                                  │
│   alias: zarc.vault.kek.v1                                       │
│   AES-256-GCM, non-exportable, GCM with a platform generated IV  │
│   hardware backed when the platform provides it,                 │
│   StrongBox when the device has one and the platform accepts it   │
└───────────────────────────┬──────────────────────────────────────┘
                            │  wraps / unwraps
                            │  (AES-256-GCM, 12 byte IV, 128 bit tag)
                            ▼
┌──────────────────────────────────────────────────────────────────┐
│ Vault Master Key (VMK)                                           │
│   256-bit, generated with SecureRandom on the device             │
│   lives in memory only, zeroized after every operation           │
│   stored on disk only in its wrapped form                        │
└───────────────────────────┬──────────────────────────────────────┘
                            │  wraps / unwraps
                            │  (XChaCha20-Poly1305-IETF, 24 byte nonce)
                            ▼
┌──────────────────────────────────────────────────────────────────┐
│ Data Encryption Key (DEK)                                        │
│   256-bit, freshly generated for every single object             │
│   exists only for the duration of one encryption                 │
│   stored on disk only in its wrapped form                        │
└───────────────────────────┬──────────────────────────────────────┘
                            │  encrypts
                            ▼
┌──────────────────────────────────────────────────────────────────┐
│ Object content                                                   │
│   XChaCha20-Poly1305 secretstream, 64 KiB chunks                 │
└──────────────────────────────────────────────────────────────────┘
```

## Layer 1: the Android Keystore key

| Property | Value |
|----------|-------|
| Alias | `zarc.vault.kek.v1` |
| Algorithm | AES-256-GCM, `KeyProperties.BLOCK_MODE_GCM`, no padding |
| Key size | 256 bit |
| Randomised encryption | required (the platform generates the IV) |
| Exportable | no, by construction: the key never leaves the Keystore |
| Storage | `noBackupFilesDir` (the wrapped VMK), never backed up |
| StrongBox | requested when the device advertises `FEATURE_STRONGBOX_KEYSTORE`; if the platform refuses, the key is generated without it and the report says so |

**What the platform actually reports.** The Security Lab screen and the
instrumented tests print the protection level that the platform reports:

* API 31 and newer: `KeyInfo.getSecurityLevel()`, mapped to
  `STRONGBOX`, `TRUSTED_ENVIRONMENT` or `SOFTWARE`,
* API 30 and older: `KeyInfo.isInsideSecureHardware()`, reported as
  `HARDWARE_BACKED_PRE_31` or `SOFTWARE`,
* **if the platform does not expose `KeyInfo` for the key, the level is
  reported as `UNAVAILABLE`** and Zarc does *not* claim hardware backing.

That last case is not hypothetical: the API 36 emulator used for the
on-device tests registers an AndroidKeyStore `KeyFactory` for `RSA` but not
for `AES`, so `KeyInfo` cannot be obtained for an AES key there. The
instrumented test asserts the honest value instead of a guess. On a device
that does expose `KeyInfo`, the level is reported from the platform.

## Layer 2: the vault master key

* **Generation.** 32 bytes from `java.security.SecureRandom` on the device.
* **Storage.** Only the wrapped form is stored, in
  `noBackupFilesDir/keys/vmk.wrapped` (12 byte IV followed by the
  GCM ciphertext and tag). The file is created with owner-only permissions.
* **Use.** The Kotlin adapter unwraps it and hands it to the Rust core as a
  byte array for the duration of one operation. The Kotlin code zeroizes the
  array afterwards; the Rust side copies it into a `zeroize` type.
* **Lifetime.** It is never written to disk in the clear, never logged and
  never included in an error message.

## Layer 3: the per-object data key

* **Generation.** 32 bytes from libsodium's `randombytes_buf` for every
  object.
* **Storage.** Only the wrapped form, in the container header
  (`wrapped_dek` + `wrapped_dek_mac`).
* **Binding.** The wrapping associated data covers the object id, the format
  version, the algorithm ids, the secretstream header, the nonce and the
  declared lengths, so a key envelope cannot be moved to another object.
* **Lifetime.** It exists in memory during one encryption or decryption and
  is zeroized when the key type is dropped.

## Layer 4: the Private Vault subkey

* **Independent random key.** A private area created by the current app generates a random 256-bit subkey (`newPrivateSubkey`). It is unrelated to the Vault Master Key, so compromising the VMK alone does not expose the private media.
* **Hardware biometric envelope.** The subkey is wrapped by an AES-256-GCM key in the Android Keystore (`PrivateGate`). The gate key is created with `setUserAuthenticationRequired(true)`, `setInvalidatedByBiometricEnrollment(false)` and `AUTH_BIOMETRIC_STRONG`. The wrapped blob on disk is `ZPG2` magic + a 12 byte IV + the GCM ciphertext and tag. The key deliberately survives adding a new fingerprint or face: the random subkey has no other wrapping, so invalidation on a routine enrollment change would permanently destroy the private media. Every use still requires a fresh strong biometric.
* **Authentication gate.** The wrap/unwrap `Cipher` is handed to `BiometricPrompt` as a `CryptoObject`; `doFinal` only succeeds after the platform authorises the operation. Device-credential authentication cannot authorise a `CryptoObject`, so the private gate deliberately accepts strong biometrics only.
* **Unlock required for writes.** Importing or moving objects into the private area requires an unlocked private subkey. The app never falls back to a key derived from the VMK for a write.
* **Legacy migration.** Private areas created before the independent subkey used `nativeDeriveSubkey(vmk, "zarc.private.v1")`. On the first unlock the app wraps a fresh random subkey, writes the `vault/private/migrating` marker, migrates every object, the private index and the thumbnails, and only then removes the marker. The migration is idempotent; master-key rotation refuses to run while it is pending.
* **Auto-locking lifecycle.** Leaving the foreground (`onStop`) zeroes `unlockedPrivateKey`, clears the thumbnail, viewer and archive memory caches, and deletes transient decrypted files. `FLAG_SECURE` is enabled for the whole app, so screenshots and unredacted recent-task snapshots are blocked.

## Key transitions

| Transition | Mechanism | Nonce / IV | Authentication |
|------------|-----------|-----------|---------------|
| random -> KEK | Android Keystore key generation | platform generated | platform |
| VMK -> wrapped VMK | AES-256-GCM (platform cipher) | 12 byte platform IV | GCM tag (128 bit) |
| wrapped VMK -> VMK | AES-256-GCM (platform cipher) | stored IV | GCM tag verified |
| random -> DEK | libsodium `randombytes_buf` | - | - |
| DEK -> wrapped DEK | XChaCha20-Poly1305-IETF (detached) | fresh random 24 byte nonce | Poly1305 (128 bit) over the header |
| wrapped DEK -> DEK | XChaCha20-Poly1305-IETF (detached) | stored nonce | Poly1305 verified |
| DEK -> content | XChaCha20-Poly1305 secretstream | derived per chunk by the construction | per chunk tag + final tag |

## Persistence across a restart

The VMK is re-derived from the wrapped file with the Keystore key. The
instrumented test `theVaultMasterKeySurvivesAProcessRestart` creates a fresh
`VaultRepository` and a fresh `KeystoreKeyManager` and checks that the
unwrapped VMK is byte for byte the same. The objects are then decrypted with
it.

## Loss, invalidation and failure behaviour

| Event | Behaviour |
|-------|-----------|
| The wrapped VMK file is corrupted | The GCM tag does not verify: `KeystoreFailure`, every operation fails. No fallback. |
| The Keystore alias is missing but the wrapped VMK exists | The key is recreated, the wrapped VMK can no longer be unwrapped: the vault is reported as unrecoverable and every operation fails. |
| The Keystore key is invalidated (`KeyPermanentlyInvalidatedException`) | Reported as a `KeystoreFailure`; no fallback. |
| The Keystore key cannot be recovered (`UnrecoverableKeyException`) | Reported as a `KeystoreFailure`; no fallback. |
| StrongBox is advertised but unavailable | The key is generated without StrongBox; the report says so. |
| The Keystore key and the wrapped VMK are both gone | The vault starts over with a *new* VMK. The old objects are unreadable unless a recovery envelope exists. |
| A new biometric is enrolled | The private-area gate key is **not** invalidated; the private area remains reachable with the new biometric. This is deliberate: the random private subkey has no other wrapping. |
| Every biometric is removed, or the secure lock screen is removed | The private-area gate key is invalidated by the platform: the private area is unrecoverable. Normal vault objects remain readable through the Keystore-bound VMK. |
| The device is wiped, reset or replaced | The Keystore key and the wrapped VMK are gone: the vault is recoverable **only** with the recovery passphrase. |
| The app data is cleared | Same as above. |

The instrumented test `losingTheKeystoreKeyDestroysTheVault` demonstrates
the last two rows on the device.

## Recovery: the passphrase recovery envelope

**A vault whose Keystore key is lost can be recovered with a passphrase.**

```text
passphrase --Argon2id(salt, ops=3, mem=64 MiB)--> wrapping key
          --XChaCha20-Poly1305--> vault master key
```

* The KDF is libsodium's own `crypto_pwhash_argon2id` - the password based
  KDF of the same library that provides the content encryption. No new
  dependency, no home grown KDF.
* The envelope (`noBackupFilesDir/keys/vmk.recovery`) stores the format
  version, a random 16 byte salt, the Argon2id cost parameters, a random
  24 byte nonce, the wrapped master key and its 128 bit tag. **The passphrase
  is never stored.**
* The cost parameters are covered by the envelope's associated data, so they
  cannot be lowered without breaking the authentication.
* A **witness** (`noBackupFilesDir/keys/vmk.witness`) - a constant sealed
  under the master key - proves that a recovered key is the vault master key
  of *this* vault.
* The passphrase bytes, the derived key and the recovered key are zeroized
  after use. The passphrase is entered in a masked field and is never logged.
* A wrong passphrase is detected by the authentication tag; it can never
  produce a usable key and never damages the vault.

**What recovery does not fix.** Recovery restores access to the *data*; it does
not restore the device bound protection. After a recovery the vault is still
protected by the device envelope, and the user should re-establish a fresh
Keystore binding (which the application does automatically: the recovered key
is re-wrapped under a newly generated Keystore key).

**What is still missing.** A *server side* recovery share (a split secret
stored outside the device) is not implemented: it would need a service, an
account system and a network permission, which the brief excludes.

## Key rotation

| Operation | What changes | What does not | Cost |
|-----------|--------------|---------------|------|
| Master key rotation | the wrapping of every data key | the content of the objects, their identity | rewrites every object file |
| Object re-encryption | the data key, the secretstream header, the envelope nonce, the content | the identity of the object | one object |
| Keystore key rotation | the Keystore key and the wrapped master key | the master key itself | one file |

* The master key rotation is **idempotent**: an object that already unwraps
  with the new key is skipped, so an interrupted rotation can simply be
  repeated. The staged key is written before the rotation and committed after
  it, and a pending rotation is finished before any vault access on the next
  start.
* The rotation covers **every** artifact: the normal objects, the recycle bin
  objects, the sealed index, the sealed thumbnails and the binned thumbnails.
  Objects keep their identity and content; only the wrapping of their data key
  changes.
* Rotation is refused while a recovery envelope exists, because the envelope is
  bound to the current master key and can only be recreated with the user's
  passphrase. Disable recovery, rotate, then enable recovery again. Rotation is
  also refused while a legacy private-area migration is pending.
* The object re-encryption verifies the object before it replaces it, and
  replaces it atomically, so a failed re-encryption leaves the old object
  untouched. It keeps the object's identity, so the index does not have to
  change.

See [ADR-0005](adr/ADR-0005-recovery-envelope-and-key-rotation.md).

## Residual risks

* **Process memory.** The VMK exists in the JVM heap and in the Rust heap
  while an operation runs. A process dump, a debugger or a memory-reading
  attacker with sufficient privileges can recover it. Zeroization shortens
  the window; it does not eliminate it.
* **No key separation between devices.** Every vault is protected by exactly
  one key. There is no per-object or per-album key, so a single VMK
  compromise opens every object. Per-object DEKs limit the blast radius of a
  *wrapped* key leak, not of a VMK leak.
* **A recovery passphrase is weaker than a 256-bit key.** The Argon2id cost
  parameters slow down guessing, but a weak passphrase makes the envelope
  attackable offline. The user interface requires a minimum length; the real
  recommendation is a generated passphrase.
* **The recovery envelope is on the device.** It is protected by Argon2id, but
  an attacker who copies the device also copies the *encrypted* master key.
* **The Keystore key can be used by the application.** Anything that runs
  inside the application process can ask the Keystore to unwrap the VMK.
  Malware with the app's UID defeats this protection entirely.
* **Key rotation.** Master key rotation (rotateVaultMasterKey), object re-encryption under fresh DEKs (reencryptObject), and Android Keystore hardware re-wrapping are fully supported and exposed in Settings.
