# ADR-0002: Integrating upstream libsodium through a narrow FFI layer

* Status: Accepted
* Date: 2026-10-21

## Context

The content encryption primitive is libsodium's
`crypto_secretstream_xchacha20poly1305` construction. The brief requires the
selection to be based on the current state of the available Rust bindings, on
their maintenance status, on known vulnerabilities and on whether they can be
built for the intended Android targets.

The candidates that were evaluated (see `docs/CRYPTOGRAPHY_DECISION.md` for
the full table and the evidence):

| Crate | Version | Last release | Secretstream | Android build |
|-------|---------|--------------|--------------|---------------|
| `sodiumoxide` | 0.2.7 | 2021-06 | no | n/a |
| `libsodium-sys` | 0.2.7 | 2021-06 | yes (raw) | needs `make`/`sh` |
| `libsodium-sys-stable` | 1.24.3 | 2026-10 | yes (raw) | needs `make`/`sh` |
| `libsodium-rs` | 0.3.0 | 2026-10 | yes | needs `make`/`sh` |
| `crypto_secretstream` | 0.2.0 | 2023-07 | pure Rust | yes |

## Decision

Zarc **vendors the official upstream libsodium release** (1.0.22, minisign
verified at build time), compiles it from source for every target with the
`cc` crate, and talks to it through a **hand written FFI layer that is
confined to one module** (`crates/zarc-core/src/sodium/ffi.rs`).

## Rationale

* **The crypto is upstream's, not ours.** The vendored C code is byte for byte
  the official 1.0.22 release, verified against the upstream minisign key at
  every build. The Rust code never implements a cipher, a nonce scheme or a
  chunk authentication rule.
* **The build has to work on this machine.** The maintained bindings
  (`libsodium-sys-stable`, `libsodium-rs`) build libsodium through
  `./configure` and `make`. Neither `make` nor a POSIX shell is available in
  this environment, and no prebuilt Android artefacts exist. The `cc` based
  build has no such requirement and produces identical objects for the host
  and for all four Android ABIs.
* **One code path.** The host test suite and the device run exactly the same
  C code, so a passing desktop test is evidence about the device build.
* **The FFI surface is small and auditable.** Ten functions and one
  `#[repr(C)]` struct. All of them are wrapped by safe Rust in
  `crates/zarc-core/src/sodium/mod.rs`, which owns the buffer sizes, the
  length conversions and the error translation. `unsafe` appears nowhere else
  in the workspace.
* **The format is validated against an independent implementation.** The
  interop test in `crates/zarc-core/tests/interop.rs` exchanges streams with
  `crypto_secretstream`, a pure Rust implementation of the same construction
  from the RustCrypto `nacl-compat` project. A mistake in the FFI layer
  (wrong argument order, wrong tag handling) could not survive that test.

## Consequences

* Zarc carries a build script that extracts and compiles libsodium. The
  extraction is hermetic: the tarball is vendored in `third_party/libsodium`
  and no network access is required.
* The Rust bindings maintained by the libsodium author (`libsodium-rs`) are a
  good alternative. They were not chosen because their build system cannot
  produce Android artefacts in this environment. Upgrading to them later is a
  contained change: only `crates/zarc-core/src/sodium/` is affected.
* Zarc tracks a C library rather than a pure Rust crate. A future migration to
  a maintained Rust binding would remove the C build, but not the file format,
  the key hierarchy or the tests.
* The `unsafe` surface is 10 declarations and 20 call sites, all in one file
  with documented preconditions.
