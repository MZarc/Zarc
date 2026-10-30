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

use jni::objects::{JByteArray, JClass, JObjectArray, JString};
use jni::sys::{jboolean, jbyteArray, jint, jintArray, jobjectArray, jstring};
use jni::JNIEnv;
use zeroize::Zeroizing;

use zarc_core::archive::{self, ArchiveInputFile};
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
        let key_bytes = Zeroizing::new(read_key(&mut env, &vault_master_key)?);
        let source = read_path(&mut env, &source_path)?;
        let objects = read_path(&mut env, &objects_dir)?;
        let vault = Vault::new(&objects).map_err(|e| e.to_string())?;
        let key = VaultMasterKey::from_slice(&key_bytes).map_err(|e| e.to_string())?;
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
        let key_bytes = Zeroizing::new(read_key(&mut env, &vault_master_key)?);
        let id_text = read_string(&mut env, &object_id)?;
        let objects = read_path(&mut env, &objects_dir)?;
        let destination_dir = read_path(&mut env, &destination_dir)?;
        let destination_name = read_string(&mut env, &destination_name)?;
        let id = ObjectId::from_hex(&id_text).map_err(|e| e.to_string())?;
        let vault = Vault::new(&objects).map_err(|e| e.to_string())?;
        let key = VaultMasterKey::from_slice(&key_bytes).map_err(|e| e.to_string())?;
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
        let key_bytes = Zeroizing::new(read_key(&mut env, &vault_master_key)?);
        let id_text = read_string(&mut env, &object_id)?;
        let objects = read_path(&mut env, &objects_dir)?;
        let id = ObjectId::from_hex(&id_text).map_err(|e| e.to_string())?;
        let vault = Vault::new(&objects).map_err(|e| e.to_string())?;
        let key = VaultMasterKey::from_slice(&key_bytes).map_err(|e| e.to_string())?;
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
        let key_bytes = Zeroizing::new(read_key(&mut env, &vault_master_key)?);
        let passphrase_bytes = Zeroizing::new(read_key(&mut env, &passphrase)?);
        let key = VaultMasterKey::from_slice(&key_bytes).map_err(|e| e.to_string())?;
        let envelope =
            RecoveryEnvelope::create(&key, &passphrase_bytes).map_err(|e| e.to_string())?;
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
        let envelope_bytes = Zeroizing::new(read_key(&mut env, &envelope)?);
        let passphrase_bytes = Zeroizing::new(read_key(&mut env, &passphrase)?);
        let parsed = RecoveryEnvelope::decode(&envelope_bytes).map_err(|e| e.to_string())?;
        let key = parsed.open(&passphrase_bytes).map_err(|e| e.to_string())?;
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
        let key_bytes = Zeroizing::new(read_key(&mut env, &vault_master_key)?);
        let key = VaultMasterKey::from_slice(&key_bytes).map_err(|e| e.to_string())?;
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
        let witness_bytes = Zeroizing::new(read_key(&mut env, &witness)?);
        let key_bytes = Zeroizing::new(read_key(&mut env, &vault_master_key)?);
        let parsed = RecoveryWitness::decode(&witness_bytes).map_err(|e| e.to_string())?;
        let key = VaultMasterKey::from_slice(&key_bytes).map_err(|e| e.to_string())?;
        Ok(parsed.verify(&key).is_ok())
    }));
    finish_bool(&mut env, result)
}

/// Re-wrap every data key of the vault under a new vault master key.
///
/// The content of the objects is not touched: only the key envelopes are
/// replaced. Objects that were already damaged before the rotation are left
/// untouched instead of blocking the whole vault.
///
/// Returns a two-element array: `[rotated, unreadable]`.
#[no_mangle]
pub extern "system" fn Java_com_zarc_vault_core_ZarcNative_nativeRotateMasterKey(
    mut env: JNIEnv,
    _class: JClass,
    old_vault_master_key: JByteArray,
    new_vault_master_key: JByteArray,
    objects_dir: JString,
) -> jintArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let old_bytes = Zeroizing::new(read_key(&mut env, &old_vault_master_key)?);
        let new_bytes = Zeroizing::new(read_key(&mut env, &new_vault_master_key)?);
        let objects = read_path(&mut env, &objects_dir)?;
        let old_key = VaultMasterKey::from_slice(&old_bytes).map_err(|e| e.to_string())?;
        let new_key = VaultMasterKey::from_slice(&new_bytes).map_err(|e| e.to_string())?;
        let vault = Vault::new(&objects).map_err(|e| e.to_string())?;
        let report = vault
            .rotate_master_key_resilient(&old_key, &new_key)
            .map_err(|e| e.to_string())?;
        let counts = [
            jint::try_from(report.rotated).unwrap_or(jint::MAX),
            jint::try_from(report.unreadable.len()).unwrap_or(jint::MAX),
        ];
        let array = env
            .new_int_array(2)
            .map_err(|e| format!("JNI: cannot allocate the rotation report: {e}"))?;
        env.set_int_array_region(&array, 0, &counts)
            .map_err(|e| format!("JNI: cannot fill the rotation report: {e}"))?;
        Ok(array.into_raw())
    }));
    finish_int_array(&mut env, result)
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
        let key_bytes = Zeroizing::new(read_key(&mut env, &vault_master_key)?);
        let id_text = read_string(&mut env, &object_id)?;
        let objects = read_path(&mut env, &objects_dir)?;
        let id = ObjectId::from_hex(&id_text).map_err(|e| e.to_string())?;
        let key = VaultMasterKey::from_slice(&key_bytes).map_err(|e| e.to_string())?;
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
        let key_bytes = Zeroizing::new(read_key(&mut env, &vault_master_key)?);
        let plain = Zeroizing::new(read_key(&mut env, &plaintext)?);
        let key = VaultMasterKey::from_slice(&key_bytes).map_err(|e| e.to_string())?;
        let sealed = zarc_core::keywrap::seal_blob(&key, &plain).map_err(|e| e.to_string())?;
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
        let key_bytes = Zeroizing::new(read_key(&mut env, &vault_master_key)?);
        let sealed = Zeroizing::new(read_key(&mut env, &blob)?);
        let key = VaultMasterKey::from_slice(&key_bytes).map_err(|e| e.to_string())?;
        let plain = zarc_core::keywrap::open_blob(&key, &sealed).map_err(|e| e.to_string())?;
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
        let key_bytes = Zeroizing::new(read_key(&mut env, &vault_master_key)?);
        let label = read_string(&mut env, &info)?;
        let key = VaultMasterKey::from_slice(&key_bytes).map_err(|e| e.to_string())?;
        let subkey =
            zarc_core::keywrap::derive_subkey(&key, label.as_bytes()).map_err(|e| e.to_string())?;
        env.byte_array_from_slice(&subkey)
            .map(|array| array.into_raw())
            .map_err(|e| e.to_string())
    }));
    finish_bytes(&mut env, result)
}

/// Package multiple photos into an encrypted `.zarc` or polyglot `.jpg` container.
#[no_mangle]
pub extern "system" fn Java_com_zarc_vault_core_ZarcNative_nativeArchiveCreate(
    mut env: JNIEnv,
    _class: JClass,
    archive_path: JString,
    source_paths: jobjectArray,
    display_names: jobjectArray,
    cover_index: jint,
    passphrase: JByteArray,
    outer_cover_jpeg: JByteArray,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let out_path = read_path(&mut env, &archive_path)?;
        let pass_bytes = Zeroizing::new(read_key(&mut env, &passphrase)?);

        let cover_bytes = if outer_cover_jpeg.as_raw().is_null() {
            None
        } else {
            let bytes = read_key(&mut env, &outer_cover_jpeg)?;
            if bytes.is_empty() {
                None
            } else {
                Some(bytes)
            }
        };

        let sp_obj = unsafe { JObjectArray::from_raw(source_paths) };
        let dn_obj = unsafe { JObjectArray::from_raw(display_names) };

        let num_files = env
            .get_array_length(&sp_obj)
            .map_err(|e| format!("JNI source_paths length: {e}"))?;
        let num_names = env
            .get_array_length(&dn_obj)
            .map_err(|e| format!("JNI display_names length: {e}"))?;
        if num_files != num_names {
            return Err("source_paths and display_names length mismatch".into());
        }

        let mut inputs = Vec::with_capacity(num_files as usize);
        for i in 0..num_files {
            let p_elem = env
                .get_object_array_element(&sp_obj, i)
                .map_err(|e| format!("JNI get elem {i}: {e}"))?;
            let d_elem = env
                .get_object_array_element(&dn_obj, i)
                .map_err(|e| format!("JNI get elem {i}: {e}"))?;
            let p_str = JString::from(p_elem);
            let d_str = JString::from(d_elem);
            let path = read_path(&mut env, &p_str)?;
            let display_name = read_string(&mut env, &d_str)?;
            // Free both local references before the next iteration: two
            // references per photo would exhaust the JNI local reference
            // table at the 250-photo archive limit.
            env.delete_local_ref(p_str)
                .map_err(|e| format!("JNI release source path {i}: {e}"))?;
            env.delete_local_ref(d_str)
                .map_err(|e| format!("JNI release display name {i}: {e}"))?;

            inputs.push(ArchiveInputFile {
                path,
                display_name,
                is_cover: i == cover_index,
            });
        }

        let res = archive::create_archive(
            &out_path,
            cover_bytes.as_deref(),
            &inputs,
            &pass_bytes,
            3,
            64 * 1024,
            None::<fn(f32, &str)>,
        );
        res?;

        Ok::<String, String>(out_path.to_string_lossy().into_owned())
    }));
    finish(&mut env, result)
}

/// Read the unencrypted outer cover JPEG from a polyglot archive without password.
#[no_mangle]
pub extern "system" fn Java_com_zarc_vault_core_ZarcNative_nativeArchiveReadOuterCover(
    mut env: JNIEnv,
    _class: JClass,
    archive_path: JString,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let path = read_path(&mut env, &archive_path)?;
        let maybe_cover = archive::read_outer_cover_jpeg(&path)?;
        match maybe_cover {
            Some(bytes) => {
                let array = env
                    .byte_array_from_slice(&bytes)
                    .map_err(|e| format!("JNI byte array conversion: {e}"))?;
                Ok(array.into_raw())
            }
            None => Ok(std::ptr::null_mut()),
        }
    }));
    finish_bytes(&mut env, result)
}

/// Unlock a `.zarc` archive and return the encrypted manifest as JSON.
#[no_mangle]
pub extern "system" fn Java_com_zarc_vault_core_ZarcNative_nativeArchiveReadManifest(
    mut env: JNIEnv,
    _class: JClass,
    archive_path: JString,
    passphrase: JByteArray,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let path = read_path(&mut env, &archive_path)?;
        let pass_bytes = Zeroizing::new(read_key(&mut env, &passphrase)?);

        let unlocked = archive::unlock_archive(&path, &pass_bytes);
        let unlocked = unlocked?;

        let json = serde_json::to_string(&unlocked.manifest)
            .map_err(|e| format!("Failed to serialize manifest to JSON: {e}"))?;

        Ok::<String, String>(json)
    }));
    finish(&mut env, result)
}

/// Extract a single photo from a `.zarc` archive to a destination file.
#[no_mangle]
pub extern "system" fn Java_com_zarc_vault_core_ZarcNative_nativeArchiveExtractEntry(
    mut env: JNIEnv,
    _class: JClass,
    archive_path: JString,
    passphrase: JByteArray,
    object_id: JString,
    destination_path: JString,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let path = read_path(&mut env, &archive_path)?;
        let dest = read_path(&mut env, &destination_path)?;
        let obj_id = read_string(&mut env, &object_id)?;
        let pass_bytes = Zeroizing::new(read_key(&mut env, &passphrase)?);

        let unlocked = archive::unlock_archive(&path, &pass_bytes);
        let unlocked = unlocked?;

        archive::extract_object_to_file(&unlocked, &obj_id, &dest)?;

        Ok::<String, String>(dest.to_string_lossy().into_owned())
    }));
    finish(&mut env, result)
}

/// Extract all photos from a `.zarc` archive into a destination directory.
#[no_mangle]
pub extern "system" fn Java_com_zarc_vault_core_ZarcNative_nativeArchiveExtractAll(
    mut env: JNIEnv,
    _class: JClass,
    archive_path: JString,
    passphrase: JByteArray,
    destination_dir: JString,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let path = read_path(&mut env, &archive_path)?;
        let dest_dir = read_path(&mut env, &destination_dir)?;
        let pass_bytes = Zeroizing::new(read_key(&mut env, &passphrase)?);

        let unlocked = archive::unlock_archive(&path, &pass_bytes);
        let unlocked = unlocked?;

        let extracted = archive::extract_all_to_dir(&unlocked, &dest_dir, None::<fn(f32, &str)>)?;
        let paths: Vec<String> = extracted
            .into_iter()
            .map(|p| p.to_string_lossy().into_owned())
            .collect();
        let json = serde_json::to_string(&paths)
            .map_err(|e| format!("Failed to serialize paths to JSON: {e}"))?;

        Ok::<String, String>(json)
    }));
    finish(&mut env, result)
}

/// Extract the designated cover image to a cache file for fast UI display.
#[no_mangle]
pub extern "system" fn Java_com_zarc_vault_core_ZarcNative_nativeArchiveExtractCoverThumbnail(
    mut env: JNIEnv,
    _class: JClass,
    archive_path: JString,
    passphrase: JByteArray,
    cache_destination_file: JString,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let path = read_path(&mut env, &archive_path)?;
        let cache_file = read_path(&mut env, &cache_destination_file)?;
        let pass_bytes = Zeroizing::new(read_key(&mut env, &passphrase)?);

        let unlocked = archive::unlock_archive(&path, &pass_bytes);
        let unlocked = unlocked?;

        let cover_id = unlocked
            .manifest
            .cover_object_id
            .as_ref()
            .or_else(|| unlocked.manifest.objects.first().map(|o| &o.id))
            .ok_or_else(|| "No cover image available in archive".to_string())?;

        archive::extract_object_to_file(&unlocked, cover_id, &cache_file)?;

        Ok::<String, String>(cache_file.to_string_lossy().into_owned())
    }));
    finish(&mut env, result)
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

/// Turn an integer-array result into a Java integer array.
fn finish_int_array(
    env: &mut JNIEnv,
    result: std::thread::Result<Result<jintArray, String>>,
) -> jintArray {
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
