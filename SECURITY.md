# Security policy

## Reporting a vulnerability

Zarc is a prototype. If you find a vulnerability in the crypto core, the
container format or the Android adapter, please report it privately to the
maintainer instead of opening a public issue. Include:

* a description of the problem and its impact,
* the steps to reproduce it, ideally with a test,
* the version or commit you tested.

There is no formal SLA while the project is in the prototype stage. Please do
not publish a proof of concept against a vault that contains data you are not
authorised to access.

## What Zarc protects, and what it does not

Zarc encrypts media at rest in the application private storage. The short
version:

* **Protected:** the content of the vault objects, against an attacker who has
  the files but not the keys (a stolen device, a copied backup, an offline
  analysis of the ciphertext).
* **Not protected:** a compromised operating system, an attacker with root on
  the device, malware running inside the application, plaintext that has been
  decrypted and left in the clear, or copies of the media that the application
  does not control (camera roll, cloud backup, screenshots).

The full analysis is in [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md).

## Known limitations

These are documented, not hidden. See
[`docs/VALIDATION_REPORT.md`](docs/VALIDATION_REPORT.md) for the current
status of each item.

| # | Limitation |
|---|------------|
| 1 | The plaintext length is stored in the clear in the container header. |
| 2 | The number of objects, their sizes and the access pattern are observable. |
| 3 | The vault is recoverable only with the **recovery passphrase**. A factory reset, a wiped keystore or a device replacement destroys the device-bound copy of the vault master key. A recovery envelope (Argon2id + XChaCha20-Poly1305) restores it, but the user is tied to a single passphrase and a server side recovery share does not exist yet. |
| 4 | The vault master key is in the process memory (JVM and Rust) while an operation runs. Zeroization limits the window, it does not eliminate it. |
| 5 | The key rotation *operations* exist and are tested, but there is no rotation *policy* and no server side recovery share. |
| 6 | Deleting a file does not guarantee forensic erasure on flash storage. |
| 7 | The project has **not** been independently audited, certified or formally verified. |
| 8 | The Android Keystore protection level is only as good as what the platform reports. Zarc reports the platform's own value (`TRUSTED_ENVIRONMENT` / `SOFTWARE` / `STRONGBOX`) and reports `UNAVAILABLE` instead of guessing when the platform exposes nothing. |

## Cryptographic choices

* Content: libsodium's `crypto_secretstream_xchacha20poly1305`.
* Key envelope: libsodium's `crypto_aead_xchacha20poly1305_ietf`.
* Keystore: AES-256-GCM, non-exportable, via the Android platform. Verified
  hardware backed (`TRUSTED_ENVIRONMENT`) on a Samsung Galaxy A54.
* Recovery envelope: Argon2id (libsodium) + XChaCha20-Poly1305-IETF.
* Everything else: see [`docs/CRYPTOGRAPHY_DECISION.md`](docs/CRYPTOGRAPHY_DECISION.md).

The rationale for each choice, the alternatives that were rejected and the
versions that are pinned are documented there.
