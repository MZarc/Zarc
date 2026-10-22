# Cryptography decision

This document records the cryptographic primitives Zarc uses, the evidence
behind the choice, the versions that are pinned, and the alternatives that
were rejected.

Everything in this document was checked against the upstream sources and the
package registries on 2026-10-21.

## 1. Content encryption

### 1.1 The primitive

**libsodium's `crypto_secretstream_xchacha20poly1305`** (XChaCha20-Poly1305
authenticated streaming encryption).

It is used for the object content because it is the construction that the
brief asks for and because it has the properties a media vault needs:

* it is a *streaming* AEAD: a file of any size is encrypted in bounded
  memory, chunk by chunk, without ever holding the whole file in RAM,
* every chunk carries its own authentication tag, so corruption is detected
  close to where it happens instead of at the end of a multi-gigabyte file,
* the sequence number and the key are re-derived per chunk, so chunks cannot
  be reordered, duplicated, removed or substituted without detection,
* the final message carries a `TAG_FINAL`, which makes "the stream was
  completed" an authenticated property rather than a convention,
* it is a well reviewed, widely deployed construction by the author of NaCl,
  not a home grown scheme.

The secretstream header (24 bytes) is stored in the container header; it is
not secret but it must be stored unmodified.

### 1.2 The binding that is used

Zarc uses libsodium 1.0.22, built from the official release tarball. The
tarball is vendored in `third_party/libsodium/` and its **minisign signature
is verified on every build** against the upstream release key
(`RWQf6LRCGA9i53mlYecO4IzT51TGPpvWucNSCh1CBM0QTaLn73Y7GFO3`, the same key
that the upstream `libsodium-sys-stable` crate uses). A verification failure
is a hard build error; there is no fallback to an unverified archive.

The Rust side talks to libsodium through a hand written FFI layer that is
confined to `crates/zarc-core/src/sodium/ffi.rs` (10 declarations, one
`#[repr(C)]` struct). All buffer sizing, length conversion and error
translation happens in the safe wrapper in `crates/zarc-core/src/sodium/mod.rs`.
No other module in the workspace contains `unsafe`.

Rationale for the FFI approach: see [ADR-0002](adr/ADR-0002-libsodium-integration.md).

### 1.3 The key envelope

Per-object data keys are wrapped under the vault master key with
**XChaCha20-Poly1305-IETF** (`crypto_aead_xchacha20poly1305_ietf_*_detached`),
a separate, established AEAD from the same library:

* a fresh random 192-bit nonce per wrap (the nonce is stored in the container
  header and is authenticated as part of the associated data),
* a 256-bit data key is encrypted and authenticated with a 128-bit tag,
* the associated data binds the envelope to the exact object: object id,
  format version, algorithm ids, secretstream header, wrapping nonce and the
  declared lengths.

## 2. The evaluated Rust bindings

| Crate | Version | Last release | Downloads | Secretstream API | Upstream libsodium version | Android cross build in this environment | Verdict |
|-------|---------|--------------|-----------|------------------|----------------------------|----------------------------------------|---------|
| `sodiumoxide` | 0.2.7 | 2021-06-24 | 8.8 M | **no** | 1.0.18 (2019) | n/a | rejected: no secretstream, unmaintained, stale C library |
| `libsodium-sys` | 0.2.7 | 2021-06-24 | 9.6 M | raw FFI | 1.0.18 (2019) | needs `./configure` + `make` | rejected: stale C library, no build tooling here |
| `libsodium-sys-stable` | 1.24.3 | 2026-10-14 | 1.8 M | raw FFI | current stable tarball | needs `./configure` + `make` (autoconf) | rejected as a *dependency*: the build system cannot run here |
| `libsodium-rs` | 0.3.0 | 2026-10-14 | 1.1 k | yes (safe wrapper) | current stable tarball | needs `./configure` + `make` | **closest alternative**, rejected only because of the build system |
| `crypto_secretstream` | 0.2.0 | 2023-07-22 | 355 k | pure Rust | n/a | yes | **test-only**: used as an independent implementation for the interop test |

Facts that were checked, not assumed:

* `sodiumoxide` 0.2.7 has no `crypto_secretstream` module at all; it only
  reaches up to `crypto_secretbox`/`crypto_aead`.
* `libsodium-rs` 0.3.0 is published by the author of libsodium and is the
  successor of `sodiumoxide`. It is very new (published 2026-10-14, days
  before this work) and its dependency `libsodium-sys-stable` compiles
  libsodium with autoconf: `./configure --host=<triple>` followed by `make`.
* The build environment has **no `make` and no POSIX shell** (`sh`), so the
  autoconf path cannot run. `cargo zigbuild` would be an alternative, but zig
  is not installed either.
* No RustSec or OSV advisory exists for any of these crates (checked through
  the OSV API for `crates.io/<name>`; the result is empty for all of them).
* The upstream libsodium release that `libsodium-sys-stable` verifies against
  is the same tarball that Zarc vendors, so the *crypto* would have been
  identical. The difference is purely the build system and the amount of
  Rust that sits between Zarc and the C library.

### Why not Tink (Kotlin/Java streaming AEAD)?

Google Tink provides a well audited streaming AEAD and would remove the FFI
layer entirely. It was not chosen because:

* the brief asks for a primarily Rust implementation, and the content
  encryption is the part that most benefits from being in Rust (streaming,
  bounded memory, one process),
* Tink would push the file bytes through the JVM, which is exactly what the
  brief asks to avoid,
* Tink is a large dependency (and pulls in Guava and protobuf) for a
  construction that libsodium already provides in 500 lines of C.

Tink remains a reasonable alternative for a future implementation; it is
documented here so that the choice can be revisited.

### Why no additional algorithm layers?

There is exactly one content algorithm and one envelope algorithm. Zarc does
not add a second "extra" encryption layer, does not combine algorithms, and
does not derive keys with a custom scheme. The key hierarchy is documented
in `docs/KEY_MANAGEMENT.md`.

## 2b. The recovery envelope: Argon2id

The passphrase protected copy of the vault master key uses **Argon2id**, the
password based key derivation function of the same library
(`crypto_pwhash_argon2id`):

* it is an established, reviewed KDF - not a home grown one,
* it comes from the library that already provides the content encryption, so
  no new dependency is added,
* the parameters (3 operations, 64 MiB) are slightly above libsodium's
  "interactive" profile and are stored inside the envelope, where they are
  authenticated - they cannot be lowered without breaking the envelope,
* the salt is 16 random bytes from the same CSPRNG as everything else.

Alternatives that were rejected: PBKDF2 (too fast for a stolen envelope),
scrypt (not provided by libsodium, so it would have added a dependency) and a
memory hard KDF that is not in the library (the same argument).

The wrapped master key is then protected with XChaCha20-Poly1305-IETF, the
same AEAD that wraps the per-object data keys.

## 3. Randomness

All key material, nonces and object identifiers come from libsodium's
`randombytes_buf`, which is backed by the operating system CSPRNG:

* Linux/Android: `getrandom(2)` when available, otherwise `/dev/urandom`,
* Windows: `RtlGenRandom` (via `advapi32`).

The Rust side never uses `rand`, `getrandom` or any other source of
randomness. The test suite checks that two 32 byte draws differ and are not
all zero.

## 4. Memory hygiene

* Key material is held in `zeroize` types (`VaultMasterKey`,
  `DataEncryptionKey`, `SecretStreamKey`) that zeroize themselves on drop.
* The libsodium secretstream state (which contains the per-stream key) is
  wiped with `sodium_memzero` when the encryptor/decryptor is dropped.
* The plaintext buffer of a chunk is zeroized when the chunk could not be
  authenticated.
* The **media buffers** are wiped as well: the chunk buffer of the encryptor
  and the decrypted chunk buffer of the decryptor are zeroized as soon as the
  chunk has been written to the file, so the plaintext does not linger in the
  heap until the next chunk overwrites it.

**Limitation, stated plainly:** zeroization in a managed runtime is a
best-effort measure. The Rust compiler may keep copies of a buffer in
registers or on the stack, the operating system may have paged the memory to
disk, and a process dump can capture it. Zeroization shortens the window in
which a key sits in a heap buffer; it does not make the key unrecoverable
from a running process. The vault master key is passed into the Rust core as
a byte array from the JVM, so a copy of it also exists in the JVM heap for
the duration of the call. `docs/THREAT_MODEL.md` lists this as a residual
risk.

## 5. Hashing

The test suite compares SHA-256 digests of the original and the restored
bytes. The digest is computed with the `sha2` crate (RustCrypto) in the
*tests* only; it is not part of the production code path.

A digest equality proves **data fidelity**, not cryptographic strength. The
strength of the construction comes from the primitive and from the format
design, not from the hash used in the test.

## 6. Version pinning

| Component | Version | Pinning |
|-----------|---------|---------|
| libsodium | 1.0.22 | vendored tarball + minisign signature, verified at build time |
| Rust | 1.93.1 | `rust-toolchain.toml` |
| `jni` | 0.21.1 | `Cargo.lock` |
| `zeroize` | 1.9.1 | `Cargo.lock` |
| `thiserror` | 2.0.21 | `Cargo.lock` |
| AGP | 9.3.1 | `android/build.gradle.kts` |
| Gradle | 9.6.1 | `android/gradle/wrapper/gradle-wrapper.properties` |
| NDK | 27.1.12297006 | `tools/build-android.ps1` (auto-detected from `ANDROID_NDK_ROOT`) |

`jni` 0.21 is used rather than the newer 0.22 line because 0.22 replaced the
`JNIEnv` receiver with a new `EnvUnowned`/`EnvOutcome` error policy model. The
0.21 API is the documented, stable native method ABI. The dependency is pinned
in `Cargo.lock` and the API surface that Zarc uses is six functions.

## 7. Cryptographic review status

Zarc has **not** been independently audited, reviewed or formally verified.
The evidence in `docs/VALIDATION_REPORT.md` is the project's own test
evidence, not a third party assessment.
