# Threat model

This document describes what Zarc protects against, what it does not, and
where the residual risk lies. It is written for the current phase: a
cryptographic engine with a Security Lab screen, not a finished gallery
application.

## 1. Assets

| Asset | Where it lives | Why it matters |
|-------|----------------|----------------|
| The original media | the user's storage, outside the vault | the private content itself |
| The vault objects | `noBackupFilesDir/vault/objects/<id>` | the encrypted form of the media |
| The vault master key | in memory; wrapped in `noBackupFilesDir/keys/vmk.wrapped` | the key that opens every object |
| The Keystore key | the Android Keystore | the key that unwraps the VMK |
| The per-object data key | in memory; wrapped in the object header | the key that opens one object |
| The object identifiers | the file names in the vault directory | the mapping from file to object |
| The container format | the source and this documentation | the thing an attacker has to break |

## 2. Attackers

| Attacker | Capability | In scope? |
|----------|-----------|-----------|
| Another ordinary Android app | cannot read the app's private storage, cannot use its Keystore alias | yes |
| Someone who copies the vault files off the device (backup extraction, a file manager bug, a stolen device with unlocked storage, an ADB backup) | has the ciphertext, does not have the keys | yes, this is the main scenario |
| An offline attacker with the stolen ciphertext | can run any analysis they like on the files | yes |
| Someone who tries to open the vault files with common decoders | can feed the files to image/video parsers | yes |
| Someone who modifies, truncates, reorders, duplicates or substitutes vault files | can write arbitrary bytes | yes |
| An attacker who triggers an error, a crash, a low storage condition or a process kill during an operation | can observe the intermediate state | yes |
| A user who loses the device or wipes the app | loses the Keystore key | yes (as a *loss* case) |
| The operating system itself, or an attacker with root/privileges on a compromised device | can read the app's memory, the Keystore and the files | **no** |
| Malware running inside the application process | can call the same Keystore APIs and read the same memory | **no** |
| Someone who photographs the screen or exports a decrypted file | has the plaintext outside the vault | **no** |
| The camera roll, the cloud photo backup, screenshots | copies the app does not control | **no** |

## 3. Security objectives

Zarc establishes the following properties, and the test suite in
`crates/zarc-core/tests/` and `android/app/src/androidTest/` is the evidence
for each of them.

| # | Objective | Evidence |
|---|-----------|----------|
| O1 | An attacker who has the vault files but not the keys cannot recover the media | `mutation.rs` (wrong VMK, wrong DEK, modified envelope), `keylifecycle.rs` |
| O2 | A modified, truncated, reordered, duplicated or substituted object is rejected, and no plaintext is produced | `mutation.rs` (28 tests) |
| O3 | A malformed or hostile container is rejected by the parser, not by luck | `property.rs` (the parser never panics), `mutation.rs` (length, version, algorithm, reserved byte tests) |
| O4 | An incomplete operation never publishes an object | `transactional.rs` (interrupted encryption and decryption, staging cleanup) |
| O5 | Two encryptions of the same input produce different ciphertext | `roundtrip.rs`, `keylifecycle.rs`, the on-device test `twoEncryptionsOfTheSameInputProduceDifferentObjects` |
| O6 | Every object has an independent data key | `keylifecycle.rs` (`different_objects_receive_independent_data_keys`) |
| O7 | Stored objects carry no descriptive metadata and no media extensions | `roundtrip.rs` (`stored_objects_use_opaque_names_without_extensions`), `keylifecycle.rs` (`the_header_carries_no_descriptive_metadata`) |
| O8 | The plaintext does not appear in the vault | `roundtrip.rs` (media fixtures), the on-device test `theStoredObjectDoesNotContainThePlaintext` |
| O9 | The vault master key is never written to disk in the clear | `keylifecycle.rs`, the on-device test `theUnwrappedVaultMasterKeyIsNeverWrittenToDisk` |
| O10 | The vault is not backed up | the manifest (`allowBackup=false`, data extraction rules) and `noBackupFilesDir`; the on-device test `theVaultIsExcludedFromBackups` |
| O11 | The platform's real protection level is reported, never guessed | the on-device test `theKeystoreKeyIsCreatedAndThePlatformReportsItsProtection` |
| O12 | A missing or invalidated Keystore key fails closed | the on-device tests `aMissingKeystoreKeyFailsClosed` and `losingTheKeystoreKeyDestroysTheVault` |
| O13 | A lost device can be recovered with the passphrase | the on-device test `theRecoveryEnvelopeCanUnlockTheVaultAfterTheKeystoreKeyIsLost` |
| O14 | A wrong passphrase never produces a usable key | `aWrongPassphraseIsRejected` (device) and `a_wrong_passphrase_is_rejected` (host) |
| O15 | The master key can be rotated without re-encrypting the content | `rotating_the_master_key_keeps_the_content_and_changes_the_envelope` |
| O16 | An interrupted rotation can be finished and never corrupts an object | `the_rotation_is_idempotent`, `a_rotation_with_the_wrong_old_key_changes_nothing` |

## 4. Explicitly outside the protection guarantee

These are not weaknesses that were overlooked; they are the boundaries of the
design.

* **A compromised operating system, or an attacker with sufficient privileges
  on an already compromised device.** Root can read the app's memory, the
  Keystore and the files. No application level encryption survives that.
* **Malware or code executing inside the application** while it has access to
  the decrypted media or the keys. Such code can call the Keystore, ask for
  the VMK and decrypt every object.
* **Plaintext captured while a file is being viewed, exported or otherwise
  used.** Once a decrypted file exists outside the vault, the vault no longer
  protects it. Zarc does not attempt to control the media pipeline (that is
  a later phase) and does not claim to.
* **Camera images, cloud copies, screenshots and other copies the application
  does not control.**
* **Hardware vulnerabilities, future cryptographic breakthroughs and
  implementation flaws that have not been discovered.** XChaCha20-Poly1305 is
  believed to be secure, but that is a belief about the current literature, not
  a proof.
* **A denial of service.** An attacker who can delete the vault files or fill
  the device's storage can destroy the data. Encryption does not prevent that.

## 5. Residual risks inside the scope

These are risks that Zarc *does* address partially, and that remain:

| Risk | Description | Mitigation today |
|------|-------------|-------------------|
| R1 Length leakage | The plaintext length is stored in the clear | None. A padding scheme would leak a padding oracle; see the open items |
| R2 Object count and access patterns | The number of objects, their sizes and the times they are read are observable | None |
| R3 No key separation between devices | A single VMK opens every object of a vault | Per-object DEKs limit the blast radius of a wrapped key leak only |
| R4 Process memory exposure | The VMK is in the JVM heap and the Rust heap during an operation | Zeroization on drop, short key lifetime; the media chunk buffers are wiped as soon as they are written to the file |
| R5 Application level attacker | Malware running as the app defeats the Keystore | Out of scope |
| R6 No key rotation | The VMK cannot be re-wrapped without re-encrypting everything | Not implemented |
| R7 Deletion is not erasure | Deleting a file does not wipe the flash | The app never deletes the original file; the vault objects are the only copies |
| R8 No anti-rollback | An attacker who can restore an old snapshot of the app's data can restore old objects | Out of scope |
| R9 Unreviewed implementation | The code has not been audited by a third party | Documented honestly; see `docs/VALIDATION_REPORT.md` |

## 6. Out of scope for this phase

The brief excludes these, and they are not implemented:

* the One UI inspired gallery, albums, animations, the media editor and the
  video player,
* AI features, cloud sync, sharing and remote APIs,
* broad media permissions,
* a server side recovery share (the passphrase recovery envelope exists),
* a release build with a production signing configuration.

## 7. Open items for the next phase

1. Decide whether the plaintext length may be hidden, and if so, with which
   padding scheme - and how the padding oracle is avoided.
2. Define the media pipeline: how a decrypted file reaches a viewer, how long
   it lives, and how it is deleted.
3. ~~Add a recovery envelope~~ - done (ADR-0005). The next step is a server
   side recovery share, so that the user is not tied to a single passphrase.
4. A key rotation *schedule* for long lived vaults (the rotation operations
   exist; what is missing is the policy that triggers them).
5. Obtain an independent security review of the container format and the key
   hierarchy before any production use.
