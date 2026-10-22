//! A self test that exercises the real cryptographic code paths.
//!
//! The same routine runs on the desktop (as an integration test) and on the
//! device (from the Security Lab screen), so the on-device report is evidence
//! about the actual build, not about a mock.
//!
//! This module contains no `unsafe` code.
//!
//! The only `unsafe` in the workspace is the libsodium FFI layer in
//! [`crate::sodium::ffi`].

#![forbid(unsafe_code)]

use std::path::Path;

use crate::container::ObjectId;
use crate::error::Result;
use crate::fsutil;
use crate::keywrap::{unwrap_data_key, wrap_data_key, DataEncryptionKey, VaultMasterKey};
use crate::sodium::{self, Decryptor, Encryptor, SecretStreamKey, TAG_FINAL};
use crate::vault::Vault;

/// A distinctive marker that must never appear inside a stored object.
const MARKER: &[u8] = b"ZARC-SELFTEST-PLAINTEXT-MARKER-0123456789";

/// The outcome of a single self-test check.
#[derive(Clone, Debug)]
pub struct Check {
    /// Human readable name of the check.
    pub name: String,
    /// Whether the check passed.
    pub passed: bool,
    /// Detail about the check outcome.
    pub detail: String,
}

impl Check {
    fn new(name: &str, passed: bool, detail: String) -> Self {
        Self {
            name: name.to_string(),
            passed,
            detail,
        }
    }
}

/// The result of a full self-test run.
#[derive(Clone, Debug)]
pub struct SelfTestReport {
    /// The version of the linked libsodium.
    pub libsodium_version: String,
    /// The individual checks.
    pub checks: Vec<Check>,
}

impl SelfTestReport {
    /// Whether every check passed.
    #[must_use]
    pub fn all_passed(&self) -> bool {
        self.checks.iter().all(|check| check.passed)
    }

    /// Render the report as a human readable, multi-line string.
    #[must_use]
    pub fn render(&self) -> String {
        use std::fmt::Write;
        let mut out = String::new();
        let _ = writeln!(out, "libsodium {}", self.libsodium_version);
        for check in &self.checks {
            let _ = writeln!(
                out,
                "[{}] {}: {}",
                if check.passed { "PASS" } else { "FAIL" },
                check.name,
                check.detail
            );
        }
        out.push_str(if self.all_passed() {
            "RESULT: PASS\n"
        } else {
            "RESULT: FAIL\n"
        });
        out
    }
}

/// # Errors
/// #
/// # Returns [`crate::Error`] when the operation cannot be completed; see
/// # the error taxonomy in [`crate::error`] for the individual failure modes.
/// Run the self test, using `work_dir` for the file level checks.
pub fn run(work_dir: &Path) -> Result<SelfTestReport> {
    let mut checks = Vec::new();

    let libsodium_version = check_library(&mut checks);
    check_random(&mut checks);
    check_secretstream(&mut checks);
    let (vmk, dek) = check_key_envelope(&mut checks)?;
    check_vault(&mut checks, work_dir, &vmk)?;
    let _ = dek;

    Ok(SelfTestReport {
        libsodium_version,
        checks,
    })
}

/// The library is initialised and reports its version.
fn check_library(checks: &mut Vec<Check>) -> String {
    match sodium::version() {
        Ok(version) => {
            checks.push(Check::new("libsodium version", true, version.to_string()));
            version.to_string()
        }
        Err(error) => {
            checks.push(Check::new("libsodium version", false, error.to_string()));
            String::from("unknown")
        }
    }
}

/// The random number generator produces different output every time.
fn check_random(checks: &mut Vec<Check>) {
    let mut first = [0u8; 32];
    let mut second = [0u8; 32];
    if sodium::random_bytes(&mut first).is_err() || sodium::random_bytes(&mut second).is_err() {
        checks.push(Check::new(
            "csprng",
            false,
            "the random number generator failed".into(),
        ));
        return;
    }
    checks.push(Check::new(
        "csprng",
        first != second && first.iter().any(|byte| *byte != 0),
        "two 32 byte draws differ and are not all zero".into(),
    ));
}

/// The secretstream round trips and rejects a modified ciphertext.
fn check_secretstream(checks: &mut Vec<Check>) {
    let key = SecretStreamKey::generate().expect("the CSPRNG must work");
    let (mut encryptor, header) = Encryptor::init(&key).expect("init_push must work");
    let message = b"zarc self test payload";
    let ad = b"zarc.selftest";
    let ciphertext = encryptor
        .push(message, ad, TAG_FINAL)
        .expect("push must work");
    let mut decryptor = Decryptor::init(&header, &key).expect("init_pull must work");
    let (plaintext, tag) = decryptor.pull(&ciphertext, ad).expect("pull must work");
    checks.push(Check::new(
        "secretstream round trip",
        plaintext == message && tag == TAG_FINAL,
        format!(
            "{} byte message, {} byte ciphertext",
            message.len(),
            ciphertext.len()
        ),
    ));

    let mut tampered = ciphertext.clone();
    tampered[0] ^= 0x01;
    let mut decryptor = Decryptor::init(&header, &key).expect("init_pull must work");
    let rejected = decryptor.pull(&tampered, ad).is_err();
    checks.push(Check::new(
        "secretstream rejects modification",
        rejected,
        "a flipped ciphertext byte must fail authentication".into(),
    ));
}

/// The key envelope round trips and rejects the wrong master key.
fn check_key_envelope(checks: &mut Vec<Check>) -> Result<(VaultMasterKey, DataEncryptionKey)> {
    let vmk = VaultMasterKey::generate()?;
    let dek = DataEncryptionKey::generate()?;
    let envelope_aad = b"zarc.dek.v1|selftest";
    let envelope_nonce = [0x5au8; 24];
    let wrapped = wrap_data_key(&vmk, &dek, &envelope_nonce, envelope_aad)?;
    let unwrapped = unwrap_data_key(&vmk, &wrapped, envelope_aad)?;
    let other_vmk = VaultMasterKey::generate()?;
    let wrong_key_rejected = unwrap_data_key(&other_vmk, &wrapped, envelope_aad).is_err();
    checks.push(Check::new(
        "key envelope",
        unwrapped.as_bytes() == dek.as_bytes() && wrong_key_rejected,
        "unwrap with the right key succeeds, with the wrong key fails".into(),
    ));
    Ok((vmk, dek))
}

/// A full file round trip through the vault, and the rejection of a modified
/// object.
fn check_vault(checks: &mut Vec<Check>, work_dir: &Path, vmk: &VaultMasterKey) -> Result<()> {
    let vault = Vault::new(work_dir.join("selftest-vault"))?;
    let source = work_dir.join("selftest-input.bin");
    let mut payload: Vec<u8> = (0u8..=255).cycle().take(70_000).collect();
    payload[..MARKER.len()].copy_from_slice(MARKER);
    std::fs::write(&source, &payload)?;

    let encrypted = vault.encrypt_file(vmk, &source)?;
    let stored = std::fs::read(&encrypted.path)?;
    let decrypted = vault.decrypt_file(
        vmk,
        &encrypted.id,
        &work_dir.join("selftest-out"),
        "restored.bin",
    )?;
    let restored = std::fs::read(&decrypted)?;
    let identical = restored == payload;

    // The stored object must not contain the plaintext.
    let plaintext_leaked = stored.windows(MARKER.len()).any(|window| window == MARKER);

    checks.push(Check::new(
        "vault file round trip",
        identical,
        format!(
            "{} plaintext bytes restored byte-for-byte (object {} bytes)",
            restored.len(),
            stored.len()
        ),
    ));

    // A modified object must be rejected.
    let mut corrupted = stored.clone();
    let last = corrupted.len() - 1;
    corrupted[last] ^= 0x80;
    let corrupted_path = work_dir.join("selftest-corrupted.obj");
    std::fs::write(&corrupted_path, &corrupted)?;
    let corrupted_id = ObjectId::from_hex(&"f".repeat(32))?;
    // Point the vault at the corrupted file by copying it under its own name.
    let corrupted_target = vault.object_path(&corrupted_id);
    std::fs::copy(&corrupted_path, &corrupted_target)?;
    let rejected = vault.verify_object(vmk, &corrupted_id).is_err();
    let _ = fsutil::remove_if_exists(&corrupted_target);
    checks.push(Check::new(
        "vault rejects a modified object",
        rejected && !plaintext_leaked,
        "a modified ciphertext byte must fail verification; the stored object must not \
         contain the plaintext"
            .into(),
    ));

    // Clean up the temporary artefacts of the self test.
    let _ = std::fs::remove_file(&source);
    let _ = std::fs::remove_file(&corrupted_path);
    let _ = std::fs::remove_dir_all(work_dir.join("selftest-out"));
    let _ = std::fs::remove_dir_all(work_dir.join("selftest-vault"));

    Ok(())
}
