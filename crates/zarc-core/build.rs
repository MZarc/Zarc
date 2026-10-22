//! Build script for `zarc-core`.
//!
//! Zarc does not depend on a third-party Rust binding for libsodium. Instead it
//! vendors the *official* upstream libsodium release tarball, verifies its
//! minisign signature, and compiles it from source for the current target with
//! the `cc` crate. This gives us:
//!
//! * the exact upstream C implementation (the same code every Rust binding
//!   wraps) at a pinned, signature-verified version,
//! * a hermetic build (no network access, no autoconf/`make` requirement, which
//!   matters for the Android NDK cross builds on this machine),
//! * one single code path for the host test suite and for the four Android ABIs.
//!
//! See `docs/CRYPTOGRAPHY_DECISION.md` (ADR-0002) for the rationale.

use std::env;
use std::fs::{self, File};
use std::path::{Component, Path, PathBuf};

use minisign_verify::{PublicKey, Signature};

/// Pinned upstream libsodium release.
const SODIUM_VERSION: &str = "1.0.22";
const SODIUM_TARBALL: &str = "libsodium-1.0.22.tar.gz";
const SODIUM_SIGNATURE: &str = "libsodium-1.0.22.tar.gz.minisig";

/// libsodium release signing key (Frank Denis / jedisct1).
///
/// This is the same public key that the upstream `libsodium-sys-stable` crate
/// uses to verify official release archives, so a signature that verifies here
/// also verifies for every other consumer of the upstream release.
const SODIUM_MINISIGN_PUBLIC_KEY: &str = "RWQf6LRCGA9i53mlYecO4IzT51TGPpvWucNSCh1CBM0QTaLn73Y7GFO3";

/// Path prefix inside the release tarball that holds the library sources.
const SODIUM_SRC_PREFIX: [&str; 2] = ["src", "libsodium"];

fn main() {
    let manifest_dir = PathBuf::from(
        env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR is always set by cargo"),
    );
    let vendor_dir = manifest_dir
        .join("..")
        .join("..")
        .join("third_party")
        .join("libsodium");
    let tarball = vendor_dir.join(SODIUM_TARBALL);
    let signature_path = vendor_dir.join(SODIUM_SIGNATURE);

    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed={}", tarball.display());
    println!("cargo:rerun-if-changed={}", signature_path.display());

    let out_dir = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR is always set by cargo"));
    let src_root = out_dir.join("libsodium-src");

    verify_signature(&tarball, &signature_path);
    extract_sources(&tarball, &src_root);
    generate_version_header(&src_root);

    compile_libsodium(&src_root);
}

/// Verify the minisign signature of the vendored release archive.
///
/// A verification failure is a hard build error: we never fall back to an
/// unverified archive.
fn verify_signature(tarball: &Path, signature_path: &Path) {
    let archive = fs::read(tarball).unwrap_or_else(|e| {
        panic!(
            "cannot read vendored libsodium archive {}: {e}. \
             Run `git lfs pull` (or restore third_party/libsodium) before building.",
            tarball.display()
        )
    });
    let public_key = PublicKey::from_base64(SODIUM_MINISIGN_PUBLIC_KEY)
        .expect("hard-coded libsodium minisign public key must parse");
    let signature = Signature::from_file(signature_path).unwrap_or_else(|e| {
        panic!(
            "cannot read libsodium signature {}: {e}",
            signature_path.display()
        )
    });
    public_key
        .verify(&archive, &signature, false)
        .expect("libsodium release archive signature verification FAILED - refusing to build");
}

/// Extract `src/libsodium/**` from the release archive into `dest`.
fn extract_sources(tarball: &Path, dest: &Path) {
    // Always extract from scratch: the archive is the single source of truth
    // and a partially extracted tree must never be reused.
    if dest.exists() {
        fs::remove_dir_all(dest).expect("cannot clean the libsodium source directory");
    }

    let file = File::open(tarball).expect("vendored libsodium archive must be readable");
    let decoder = flate2::read::GzDecoder::new(file);
    let mut archive = tar::Archive::new(decoder);

    archive
        .entries()
        .expect("libsodium archive must be readable")
        .filter_map(Result::ok)
        .for_each(|mut entry| {
            let path = entry
                .path()
                .expect("archive entry must have a path")
                .into_owned();
            let Some(relative) = strip_src_prefix(&path) else {
                return;
            };
            if relative.as_os_str().is_empty() {
                return;
            }
            let target = dest.join(&relative);
            let entry_type = entry.header().entry_type();
            if entry_type.is_dir() {
                fs::create_dir_all(&target).expect("cannot create libsodium source directory");
                return;
            }
            if !entry_type.is_file() {
                // Skip symlinks and other special entries: the upstream release
                // archive does not need them to build the library.
                return;
            }
            if let Some(parent) = target.parent() {
                fs::create_dir_all(parent).expect("cannot create libsodium source directory");
            }
            if let Ok(mut out) = File::create(&target) {
                let _ = std::io::copy(&mut entry, &mut out);
            }
        });

    let marker = dest
        .join("src")
        .join("libsodium")
        .join("sodium")
        .join("version.c");
    assert!(
        marker.exists(),
        "libsodium sources were not extracted from {} (expected {})",
        tarball.display(),
        marker.display()
    );
}

/// Strip the `<release>/src/libsodium/` prefix from an archive path.
fn strip_src_prefix(path: &Path) -> Option<PathBuf> {
    let mut components = path.components();
    // The first component is the release directory name, e.g. `libsodium-1.0.22`.
    let _release = components.next()?;
    let mut relative = PathBuf::new();
    for expected in SODIUM_SRC_PREFIX {
        match components.next()? {
            Component::Normal(part) if part == expected => relative.push(part),
            _ => return None,
        }
    }
    for component in components {
        relative.push(component);
    }
    Some(relative)
}

/// Generate `sodium/version.h`, which upstream creates with autoconf.
fn generate_version_header(src_root: &Path) {
    let include_dir = src_root
        .join("src")
        .join("libsodium")
        .join("include")
        .join("sodium");
    let version_h = include_dir.join("version.h");
    let contents = format!(
        "/* Generated by zarc-core build.rs from the upstream release template. */\n\
         #ifndef sodium_version_H\n\
         #define sodium_version_H\n\
         \n\
         #include \"export.h\"\n\
         \n\
         #define SODIUM_VERSION_STRING \"{version}\"\n\
         \n\
         #define SODIUM_LIBRARY_VERSION_MAJOR {major}\n\
         #define SODIUM_LIBRARY_VERSION_MINOR {minor}\n\
         \n\
         #define SODIUM_LIBRARY_MINIMAL 0\n\
         \n\
         #ifdef __cplusplus\n\
         extern \"C\" {{\n\
         #endif\n\
         \n\
         SODIUM_EXPORT\n\
         const char *sodium_version_string(void);\n\
         \n\
         SODIUM_EXPORT\n\
         int sodium_library_version_major(void);\n\
         \n\
         SODIUM_EXPORT\n\
         int sodium_library_version_minor(void);\n\
         \n\
         SODIUM_EXPORT\n\
         int sodium_library_version_minimal(void);\n\
         \n\
         #ifdef __cplusplus\n\
         }}\n\
         #endif\n\
         \n\
         #endif\n",
        version = SODIUM_VERSION,
        major = SODIUM_VERSION.split('.').nth(1).unwrap_or("0"),
        minor = SODIUM_VERSION.split('.').nth(2).unwrap_or("0"),
    );
    fs::write(&version_h, contents).expect("cannot write sodium/version.h");
}

/// Compile the vendored libsodium sources into a static library.
fn compile_libsodium(src_root: &Path) {
    let lib_src = src_root.join("src").join("libsodium");
    let mut sources: Vec<PathBuf> = Vec::new();
    collect_c_sources(&lib_src, &mut sources);
    assert!(!sources.is_empty(), "no libsodium C sources found");

    let profile = env::var("PROFILE").unwrap_or_else(|_| "debug".to_string());
    let opt_level: u32 = if profile == "release" { 3 } else { 2 };

    let mut build = cc::Build::new();
    build
        .include(lib_src.join("include"))
        .include(lib_src.join("include").join("sodium"))
        // Static library: no dllexport/dllimport decorations.
        .define("SODIUM_STATIC", None)
        .opt_level(opt_level)
        .warnings(false);

    for source in &sources {
        build.file(source);
    }
    build.compile("sodium");

    println!(
        "cargo:warning=zarc-core linked vendored libsodium {SODIUM_VERSION} \
         ({} C files, target {})",
        sources.len(),
        env::var("TARGET").unwrap_or_else(|_| "unknown".into())
    );
}

/// Recursively collect `.c` files, skipping the public header directory.
fn collect_c_sources(dir: &Path, out: &mut Vec<PathBuf>) {
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            if path.file_name().and_then(|n| n.to_str()) == Some("include") {
                continue;
            }
            collect_c_sources(&path, out);
        } else if path.extension().and_then(|e| e.to_str()) == Some("c") {
            out.push(path);
        }
    }
}
