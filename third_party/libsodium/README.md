# Vendored libsodium

This directory contains the **official upstream libsodium release archive** and
its signature.

| File | Content |
|------|---------|
| `libsodium-1.0.22.tar.gz` | the upstream release archive, unmodified |
| `libsodium-1.0.22.tar.gz.minisig` | the upstream minisign signature |

## Provenance

```text
Release:  libsodium 1.0.22 (tag 1.0.22-RELEASE)
Published: 2026-04-09
Source:    https://github.com/jedisct1/libsodium/releases/download/1.0.22-RELEASE/libsodium-1.0.22.tar.gz
Signature: https://github.com/jedisct1/libsodium/releases/download/1.0.22-RELEASE/libsodium-1.0.22.tar.gz.minisig
```

The release is signed by Frank Denis (jedisct1), the author of libsodium, with
the minisign key

```text
RWQf6LRCGA9i53mlYecO4IzT51TGPpvWucNSCh1CBM0QTaLn73Y7GFO3
```

This is the same key that the upstream `libsodium-sys-stable` crate uses to
verify official release archives.

## Verification

`crates/zarc-core/build.rs` verifies the signature **on every build**:

* the archive is read and its minisign signature is checked against the key
  above,
* a verification failure aborts the build with a panic; there is no fallback
  to an unverified archive and no "skip the check" switch.

To verify the archive by hand:

```powershell
# the build script does this on every build; the output is the proof:
cargo build -p zarc-core -vv 2>&1 | Select-String "libsodium"
```

## What is built from it

The build script extracts `src/libsodium/**` and compiles it with the `cc`
crate for the current target:

* the host (Windows, macOS, Linux) for the test suite,
* `aarch64-linux-android`, `armv7-linux-androideabi`, `i686-linux-android`
  and `x86_64-linux-android` for the APK.

The extraction is hermetic: no network access is needed at build time, and the
same sources produce the same objects for every target.

The only file that Zarc generates itself is `sodium/version.h`, which upstream
creates with autoconf; it is written from the version constants in the
release.

## License

libsodium is distributed under the ISC license. The license text is inside the
release archive at `LICENSE` and is reproduced in the build output directory
(`target/**/build/zarc-core-*/out/libsodium-src/LICENSE`).
