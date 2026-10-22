//! JNI bridge between the Zarc Android application and the Rust vault core.
//!
//! The bridge is deliberately tiny and file oriented:
//!
//! * media bytes never travel through the JVM - only file paths and file
//!   identifiers cross the boundary,
//! * the vault master key crosses the boundary as a byte array and is
//!   zeroized in Rust as soon as the operation finishes,
//! * every failure is turned into a Java exception with a stable, machine
//!   readable error code.
//!
//! The Java class is `com.zarc.vault.core.ZarcNative`; the native library is
//! `libzarc_jni.so`.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jstring};
use jni::JNIEnv;
use zeroize::Zeroize;

use zarc_core::container::ObjectId;
use zarc_core::keywrap::VaultMasterKey;
use zarc_core::recovery::{RecoveryEnvelope, RecoveryWitness};
use zarc_core::sodium;
use zarc_core::vault::Vault;

/// Exception class used for every error reported by the Rust core.
const EXCEPTION_CLASS: &str = "com/zarc/vault/core/ZarcException";

/// Report the libsodium version and run the full self test.
#[no_mangle]
pub extern "system" fn Java_com_zarc_vault_core_ZarcNative_nativeSelfTest(
    mut env: JNIEnv,
    _class: JClass,
    work_dir: JString,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let work_dir = read_path(&mut env, &work_dir)?;
        let report = zarc_core::selftest::run(&work_dir).map_err(|e| e.to_string())?;
        Ok::<String, String>(report.render())
    }));
    finish(&mut env, result)
}

/// Encrypt a file into the vault directory and return the object identifier.
#[no_mangle]
pub extern "system" fn Java_com_zarc_vault_core_ZarcNative_nativeEncryptFile(
    mut env: JNIEnv,
    _class: JClass,
    vault_master_key: JByteArray,
    source_path: JString,
    objects_dir: JString,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let mut key_bytes = read_key(&mut env, &vault_master_key)?;
        let source = read_path(&mut env, &source_path)?;
        let objects = read_path(&mut env, &objects_dir)?;
        let vault = Vault::new(&objects).map_err(|e| e.to_string())?;
        let key = VaultMasterKey::from_slice(&key_bytes).map_err(|e| e.to_string())?;
        key_bytes.zeroize();
        let encrypted = vault
            .encrypt_file(&key, &source)
            .map_err(|e| e.to_string())?;
        Ok::<String, String>(encrypted.id.to_hex())
    }));
    finish(&mut env, result)
}

/// Decrypt a vault object into `destination_dir/destination_name`.
#[no_mangle]
pub extern "system" fn Java_com_zarc_vault_core_ZarcNative_nativeDecryptFile(
    mut env: JNIEnv,
    _class: JClass,
    vault_master_key: JByteArray,
    object_id: JString,
    objects_dir: JString,
    destination_dir: JString,
    destination_name: JString,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let mut key_bytes = read_key(&mut env, &vault_master_key)?;
        let id_text = read_string(&mut env, &object_id)?;
        let objects = read_path(&mut env, &objects_dir)?;
        let destination_dir = read_path(&mut env, &destination_dir)?;
        let destination_name = read_string(&mut env, &destination_name)?;
        let id = ObjectId::from_hex(&id_text).map_err(|e| e.to_string())?;
        let vault = Vault::new(&objects).map_err(|e| e.to_string())?;
        let key = VaultMasterKey::from_slice(&key_bytes).map_err(|e| e.to_string())?;
        key_bytes.zeroize();
        let path = vault
            .decrypt_file(&key, &id, &destination_dir, &destination_name)
            .map_err(|e| e.to_string())?;
        Ok::<String, String>(path.to_string_lossy().into_owned())
    }));
    finish(&mut env, result)
}

/// Verify an object without producing plaintext; returns a short description.
#[no_mangle]
pub extern "system" fn Java_com_zarc_vault_core_ZarcNative_nativeVerifyObject(
    mut env: JNIEnv,
    _class: JClass,
    vault_master_key: JByteArray,
    object_id: JString,
    objects_dir: JString,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let mut key_bytes = read_key(&mut env, &vault_master_key)?;
        let id_text = read_string(&mut env, &object_id)?;
        let objects = read_path(&mut env, &objects_dir)?;
        let id = ObjectId::from_hex(&id_text).map_err(|e| e.to_string())?;
        let vault = Vault::new(&objects).map_err(|e| e.to_string())?;
        let key = VaultMasterKey::from_slice(&key_bytes).map_err(|e| e.to_string())?;
        key_bytes.zeroize();
        let info = vault.verify_object(&key, &id).map_err(|e| e.to_string())?;
        Ok::<String, String>(format!(
            "id={} plaintext={} ciphertext={} container={} chunks={}",
            info.id.to_hex(),
            info.plaintext_len,
            info.ciphertext_len,
            info.ciphertext_len + zarc_core::container::HEADER_LEN as u64,
            info.chunk_count
        ))
    }));
    finish(&mut env, result)
}

/// List the object identifiers stored in a vault directory.
#[no_mangle]
pub extern "system" fn Java_com_zarc_vault_core_ZarcNative_nativeListObjects(
    mut env: JNIEnv,
    _class: JClass,
    objects_dir: JString,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let objects = read_path(&mut env, &objects_dir)?;
        let vault = Vault::new(&objects).map_err(|e| e.to_string())?;
        let ids = vault.list_objects().map_err(|e| e.to_string())?;
        Ok::<String, String>(
            ids.iter()
                .map(|id| id.to_hex())
                .collect::<Vec<_>>()
                .join("\n"),
        )
    }));
    finish(&mut env, result)
}

/// Return information about the linked crypto library.
#[no_mangle]
pub extern "system" fn Java_com_zarc_vault_core_ZarcNative_nativeCryptoInfo(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let version = sodium::version().map_err(|e| e.to_string())?;
        Ok::<String, String>(format!(
            "libsodium={} secretstream=xchacha20poly1305 envelope=xchacha20poly1305-ietf",
            version
        ))
    }));
    finish(&mut env, result)
}

/// Create a recovery envelope that protects the vault master key with a
/// passphrase.
#[no_mangle]
pub extern "system" fn Java_com_zarc_vault_core_ZarcNative_nativeCreateRecoveryEnvelope(
    mut env: JNIEnv,
    _class: JClass,
    vault_master_key: JByteArray,
    passphrase: JByteArray,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let mut key_bytes = read_key(&mut env, &vault_master_key)?;
        let mut passphrase_bytes = read_key(&mut env, &passphrase)?;
        let key = VaultMasterKey::from_slice(&key_bytes).map_err(|e| e.to_string())?;
        key_bytes.zeroize();
        let envelope =
            RecoveryEnvelope::create(&key, &passphrase_bytes).map_err(|e| e.to_string())?;
        passphrase_bytes.zeroize();
        let encoded = envelope.encode();
        env.byte_array_from_slice(&encoded)
            .map(|array| array.into_raw())
            .map_err(|e| e.to_string())
    }));
    finish_bytes(&mut env, result)
}

/// Open a recovery envelope and return the vault master key it protects.
#[no_mangle]
pub extern "system" fn Java_com_zarc_vault_core_ZarcNative_nativeOpenRecoveryEnvelope(
    mut env: JNIEnv,
    _class: JClass,
    envelope: JByteArray,
    passphrase: JByteArray,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let mut envelope_bytes = read_key(&mut env, &envelope)?;
        let mut passphrase_bytes = read_key(&mut env, &passphrase)?;
        let parsed = RecoveryEnvelope::decode(&envelope_bytes).map_err(|e| e.to_string())?;
        let key = parsed.open(&passphrase_bytes).map_err(|e| e.to_string())?;
        passphrase_bytes.zeroize();
        envelope_bytes.zeroize();
        env.byte_array_from_slice(key.as_bytes())
            .map(|array| array.into_raw())
            .map_err(|e| e.to_string())
    }));
    finish_bytes(&mut env, result)
}

/// Create the witness that proves a recovered key is the vault master key.
#[no_mangle]
pub extern "system" fn Java_com_zarc_vault_core_ZarcNative_nativeCreateRecoveryWitness(
    mut env: JNIEnv,
    _class: JClass,
    vault_master_key: JByteArray,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let mut key_bytes = read_key(&mut env, &vault_master_key)?;
        let key = VaultMasterKey::from_slice(&key_bytes).map_err(|e| e.to_string())?;
        key_bytes.zeroize();
        let witness = RecoveryWitness::create(&key).map_err(|e| e.to_string())?;
        env.byte_array_from_slice(&witness.encode())
            .map(|array| array.into_raw())
            .map_err(|e| e.to_string())
    }));
    finish_bytes(&mut env, result)
}

/// Check that a key is the vault master key this witness was created for.
#[no_mangle]
pub extern "system" fn Java_com_zarc_vault_core_ZarcNative_nativeVerifyRecoveryWitness(
    mut env: JNIEnv,
    _class: JClass,
    witness: JByteArray,
    vault_master_key: JByteArray,
) -> jboolean {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let mut witness_bytes = read_key(&mut env, &witness)?;
        let mut key_bytes = read_key(&mut env, &vault_master_key)?;
        let parsed = RecoveryWitness::decode(&witness_bytes).map_err(|e| e.to_string())?;
        let key = VaultMasterKey::from_slice(&key_bytes).map_err(|e| e.to_string())?;
        key_bytes.zeroize();
        witness_bytes.zeroize();
        Ok(parsed.verify(&key).is_ok())
    }));
    finish_bool(&mut env, result)
}

/// Re-wrap every data key of the vault under a new vault master key.
///
/// The content of the objects is not touched: only the key envelopes are
/// replaced. Returns the number of objects that were re-wrapped.
#[no_mangle]
pub extern "system" fn Java_com_zarc_vault_core_ZarcNative_nativeRotateMasterKey(
    mut env: JNIEnv,
    _class: JClass,
    old_vault_master_key: JByteArray,
    new_vault_master_key: JByteArray,
    objects_dir: JString,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let mut old_bytes = read_key(&mut env, &old_vault_master_key)?;
        let mut new_bytes = read_key(&mut env, &new_vault_master_key)?;
        let objects = read_path(&mut env, &objects_dir)?;
        let old_key = VaultMasterKey::from_slice(&old_bytes).map_err(|e| e.to_string())?;
        let new_key = VaultMasterKey::from_slice(&new_bytes).map_err(|e| e.to_string())?;
        old_bytes.zeroize();
        new_bytes.zeroize();
        let vault = Vault::new(&objects).map_err(|e| e.to_string())?;
        let rotated = vault
            .rotate_master_key(&old_key, &new_key)
            .map_err(|e| e.to_string())?;
        Ok(rotated as jint)
    }));
    finish_int(&mut env, result)
}

/// Re-encrypt an object with a fresh data key, keeping its identity.
#[no_mangle]
pub extern "system" fn Java_com_zarc_vault_core_ZarcNative_nativeReencryptObject(
    mut env: JNIEnv,
    _class: JClass,
    vault_master_key: JByteArray,
    object_id: JString,
    objects_dir: JString,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let mut key_bytes = read_key(&mut env, &vault_master_key)?;
        let id_text = read_string(&mut env, &object_id)?;
        let objects = read_path(&mut env, &objects_dir)?;
        let id = ObjectId::from_hex(&id_text).map_err(|e| e.to_string())?;
        let key = VaultMasterKey::from_slice(&key_bytes).map_err(|e| e.to_string())?;
        key_bytes.zeroize();
        let vault = Vault::new(&objects).map_err(|e| e.to_string())?;
        let info = vault
            .reencrypt_object(&key, &id)
            .map_err(|e| e.to_string())?;
        Ok::<String, String>(format!(
            "id={} plaintext={} ciphertext={}",
            info.id.to_hex(),
            info.plaintext_len,
            info.ciphertext_len
        ))
    }));
    finish(&mut env, result)
}

/// Seal an arbitrary blob under the vault master key.
///
/// The gallery uses this for the metadata index and for the thumbnails: they
/// are protected exactly like the media itself.
#[no_mangle]
pub extern "system" fn Java_com_zarc_vault_core_ZarcNative_nativeSealBlob(
    mut env: JNIEnv,
    _class: JClass,
    vault_master_key: JByteArray,
    plaintext: JByteArray,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let mut key_bytes = read_key(&mut env, &vault_master_key)?;
        let mut plain = read_key(&mut env, &plaintext)?;
        let key = VaultMasterKey::from_slice(&key_bytes).map_err(|e| e.to_string())?;
        key_bytes.zeroize();
        let sealed = zarc_core::keywrap::seal_blob(&key, &plain).map_err(|e| e.to_string())?;
        plain.zeroize();
        env.byte_array_from_slice(&sealed)
            .map(|array| array.into_raw())
            .map_err(|e| e.to_string())
    }));
    finish_bytes(&mut env, result)
}

/// Open a blob that was sealed with the vault master key.
#[no_mangle]
pub extern "system" fn Java_com_zarc_vault_core_ZarcNative_nativeOpenBlob(
    mut env: JNIEnv,
    _class: JClass,
    vault_master_key: JByteArray,
    blob: JByteArray,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let mut key_bytes = read_key(&mut env, &vault_master_key)?;
        let mut sealed = read_key(&mut env, &blob)?;
        let key = VaultMasterKey::from_slice(&key_bytes).map_err(|e| e.to_string())?;
        key_bytes.zeroize();
        let plain = zarc_core::keywrap::open_blob(&key, &sealed).map_err(|e| e.to_string())?;
        sealed.zeroize();
        env.byte_array_from_slice(&plain)
            .map(|array| array.into_raw())
            .map_err(|e| e.to_string())
    }));
    finish_bytes(&mut env, result)
}

/// Derive a purpose bound subkey from the vault master key.
///
/// The private area uses a subkey, so that its media is protected by a key
/// that is separate from the key of the normal area.
#[no_mangle]
pub extern "system" fn Java_com_zarc_vault_core_ZarcNative_nativeDeriveSubkey(
    mut env: JNIEnv,
    _class: JClass,
    vault_master_key: JByteArray,
    info: JString,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let mut key_bytes = read_key(&mut env, &vault_master_key)?;
        let label = read_string(&mut env, &info)?;
        let key = VaultMasterKey::from_slice(&key_bytes).map_err(|e| e.to_string())?;
        key_bytes.zeroize();
        let subkey =
            zarc_core::keywrap::derive_subkey(&key, label.as_bytes()).map_err(|e| e.to_string())?;
        env.byte_array_from_slice(&subkey)
            .map(|array| array.into_raw())
            .map_err(|e| e.to_string())
    }));
    finish_bytes(&mut env, result)
}

/// Turn a byte array result into a Java byte array.
fn finish_bytes(
    env: &mut JNIEnv,
    result: std::thread::Result<Result<jbyteArray, String>>,
) -> jbyteArray {
    match result {
        Ok(Ok(value)) => value,
        Ok(Err(message)) => {
            throw(env, &message);
            std::ptr::null_mut()
        }
        Err(panic) => {
            throw(env, &panic_message(panic));
            std::ptr::null_mut()
        }
    }
}

/// Turn an integer result into a Java integer.
fn finish_int(env: &mut JNIEnv, result: std::thread::Result<Result<jint, String>>) -> jint {
    match result {
        Ok(Ok(value)) => value,
        Ok(Err(message)) => {
            throw(env, &message);
            0
        }
        Err(panic) => {
            throw(env, &panic_message(panic));
            0
        }
    }
}

/// Turn a boolean result into a Java boolean.
fn finish_bool(env: &mut JNIEnv, result: std::thread::Result<Result<bool, String>>) -> jboolean {
    match result {
        Ok(Ok(value)) => jboolean::from(value),
        Ok(Err(message)) => {
            throw(env, &message);
            jboolean::from(false)
        }
        Err(panic) => {
            throw(env, &panic_message(panic));
            jboolean::from(false)
        }
    }
}

/// Render a panic payload as a message.
fn panic_message(panic: Box<dyn std::any::Any + Send>) -> String {
    let detail = panic
        .downcast_ref::<&str>()
        .map(|s| (*s).to_string())
        .or_else(|| panic.downcast_ref::<String>().cloned())
        .unwrap_or_else(|| "unknown panic".to_string());
    format!("PANIC: {detail}")
}

/// Read a Java string into a Rust `String`.
fn read_string(env: &mut JNIEnv, value: &JString) -> Result<String, String> {
    env.get_string(value)
        .map(|text| text.into())
        .map_err(|error| format!("JNI: cannot read string: {error}"))
}

/// Read a Java string into a `PathBuf`.
fn read_path(env: &mut JNIEnv, value: &JString) -> Result<PathBuf, String> {
    read_string(env, value).map(PathBuf::from)
}

/// Read the vault master key from a Java byte array.
///
/// The returned buffer is zeroized by the caller once the key has been
/// imported into the Rust key type.
fn read_key(env: &mut JNIEnv, value: &JByteArray) -> Result<Vec<u8>, String> {
    env.convert_byte_array(value)
        .map_err(|error| format!("JNI: cannot read key array: {error}"))
}

/// Turn the result of an operation into a Java return value or exception.
fn finish(env: &mut JNIEnv, result: std::thread::Result<Result<String, String>>) -> jstring {
    let outcome = match result {
        Ok(value) => value,
        Err(panic) => {
            let detail = panic
                .downcast_ref::<&str>()
                .map(|s| (*s).to_string())
                .or_else(|| panic.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "unknown panic".to_string());
            Err(format!("PANIC: {detail}"))
        }
    };
    match outcome {
        Ok(value) => match env.new_string(value) {
            Ok(text) => text.into_raw(),
            Err(error) => {
                throw(env, &format!("JNI: cannot create return value: {error}"));
                std::ptr::null_mut()
            }
        },
        Err(message) => {
            throw(env, &message);
            std::ptr::null_mut()
        }
    }
}

/// Throw a Java exception with the given message.
fn throw(env: &mut JNIEnv, message: &str) {
    if let Ok(class) = env.find_class(EXCEPTION_CLASS) {
        let _ = env.throw_new(class, message);
        return;
    }
    if let Ok(class) = env.find_class("java/lang/RuntimeException") {
        let _ = env.throw_new(class, message);
    }
}
