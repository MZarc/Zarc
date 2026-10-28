use crate::archive::format::*;
use crate::sodium::ffi;
use zeroize::Zeroizing;

/// Ensure libsodium is initialized.
pub fn ensure_crypto_initialized() -> Result<(), String> {
    let ret = unsafe { ffi::sodium_init() };
    if ret < 0 {
        return Err("libsodium initialization failed".into());
    }
    Ok(())
}

/// Generate cryptographically secure random bytes into a slice.
pub fn fill_random_bytes(buf: &mut [u8]) {
    unsafe {
        ffi::randombytes_buf(buf.as_mut_ptr(), buf.len());
    }
}

/// Derive a 256-bit key from a password and salt using Argon2id via libsodium.
pub fn derive_argon2id_key(
    password: &[u8],
    salt: &[u8; SALT_LEN],
    ops_limit: u32,
    mem_limit_kib: u32,
) -> Result<Zeroizing<[u8; KEY_LEN]>, String> {
    ensure_crypto_initialized()?;
    let mut key = Zeroizing::new([0u8; KEY_LEN]);
    let mem_limit_bytes = (mem_limit_kib as usize) * 1024;
    let ret = unsafe {
        ffi::crypto_pwhash_argon2id(
            key.as_mut_ptr(),
            KEY_LEN as u64,
            password.as_ptr(),
            password.len() as u64,
            salt.as_ptr(),
            ops_limit as u64,
            mem_limit_bytes,
            ffi::ARGON2ID_ALG,
        )
    };
    if ret != 0 {
        return Err("Argon2id key derivation failed".into());
    }
    Ok(key)
}

/// Holds derived subkeys for content objects and the archive manifest.
pub struct DerivedKeys {
    pub content_key: Zeroizing<[u8; KEY_LEN]>,
    pub manifest_key: Zeroizing<[u8; KEY_LEN]>,
}

/// Derive domain-separated content and manifest keys from DEK using HKDF-SHA256.
pub fn derive_subkeys(dek: &[u8; KEY_LEN], container_id: &[u8; CONTAINER_ID_LEN]) -> Result<DerivedKeys, String> {
    ensure_crypto_initialized()?;
    let mut prk = Zeroizing::new([0u8; KEY_LEN]);
    let ret_extract = unsafe {
        ffi::crypto_kdf_hkdf_sha256_extract(
            prk.as_mut_ptr(),
            container_id.as_ptr(),
            container_id.len(),
            dek.as_ptr(),
            dek.len(),
        )
    };
    if ret_extract != 0 {
        return Err("HKDF-SHA256 extract failed".into());
    }

    let mut content_key = Zeroizing::new([0u8; KEY_LEN]);
    let ctx_content = b"zarc-content-v1";
    let ret_exp1 = unsafe {
        ffi::crypto_kdf_hkdf_sha256_expand(
            content_key.as_mut_ptr(),
            KEY_LEN,
            ctx_content.as_ptr(),
            ctx_content.len(),
            prk.as_ptr(),
        )
    };
    if ret_exp1 != 0 {
        return Err("HKDF-SHA256 expand for content key failed".into());
    }

    let mut manifest_key = Zeroizing::new([0u8; KEY_LEN]);
    let ctx_manifest = b"zarc-manifest-v1";
    let ret_exp2 = unsafe {
        ffi::crypto_kdf_hkdf_sha256_expand(
            manifest_key.as_mut_ptr(),
            KEY_LEN,
            ctx_manifest.as_ptr(),
            ctx_manifest.len(),
            prk.as_ptr(),
        )
    };
    if ret_exp2 != 0 {
        return Err("HKDF-SHA256 expand for manifest key failed".into());
    }

    Ok(DerivedKeys {
        content_key,
        manifest_key,
    })
}

/// Encrypt plaintext with XChaCha20-Poly1305.
/// Output format: `[ciphertext (plaintext.len() bytes)] [tag (16 bytes)]`.
pub fn aead_encrypt(
    key: &[u8; KEY_LEN],
    nonce: &[u8; AEAD_NONCE_LEN],
    plaintext: &[u8],
    aad: &[u8],
) -> Result<Vec<u8>, String> {
    ensure_crypto_initialized()?;
    let mut out = vec![0u8; plaintext.len() + AEAD_TAG_LEN];
    let (ciphertext_part, tag_part) = out.split_at_mut(plaintext.len());
    let mut maclen: u64 = 0;

    let ret = unsafe {
        ffi::crypto_aead_xchacha20poly1305_ietf_encrypt_detached(
            ciphertext_part.as_mut_ptr(),
            tag_part.as_mut_ptr(),
            &mut maclen,
            plaintext.as_ptr(),
            plaintext.len() as u64,
            aad.as_ptr(),
            aad.len() as u64,
            std::ptr::null(),
            nonce.as_ptr(),
            key.as_ptr(),
        )
    };
    if ret != 0 {
        return Err("XChaCha20-Poly1305 encryption failed".into());
    }
    Ok(out)
}

/// Decrypt ciphertext with XChaCha20-Poly1305.
/// Input format: `[ciphertext] [tag (16 bytes)]`.
pub fn aead_decrypt(
    key: &[u8; KEY_LEN],
    nonce: &[u8; AEAD_NONCE_LEN],
    ciphertext_and_tag: &[u8],
    aad: &[u8],
) -> Result<Vec<u8>, String> {
    ensure_crypto_initialized()?;
    if ciphertext_and_tag.len() < AEAD_TAG_LEN {
        return Err("Ciphertext too short to contain authentication tag".into());
    }
    let pt_len = ciphertext_and_tag.len() - AEAD_TAG_LEN;
    let (ciphertext_part, tag_part) = ciphertext_and_tag.split_at(pt_len);
    let mut plaintext = vec![0u8; pt_len];

    let ret = unsafe {
        ffi::crypto_aead_xchacha20poly1305_ietf_decrypt_detached(
            plaintext.as_mut_ptr(),
            std::ptr::null_mut(),
            ciphertext_part.as_ptr(),
            pt_len as u64,
            tag_part.as_ptr(),
            aad.as_ptr(),
            aad.len() as u64,
            nonce.as_ptr(),
            key.as_ptr(),
        )
    };
    if ret != 0 {
        return Err("Authentication failed: incorrect password or corrupted/tampered archive data".into());
    }
    Ok(plaintext)
}

/// Wrap (encrypt) the DEK using the password-derived KEK.
pub fn wrap_dek(
    kek: &[u8; KEY_LEN],
    nonce: &[u8; AEAD_NONCE_LEN],
    dek: &[u8; KEY_LEN],
    aad: &[u8],
) -> Result<[u8; WRAPPED_DEK_LEN], String> {
    let encrypted = aead_encrypt(kek, nonce, dek, aad)?;
    let mut wrapped = [0u8; WRAPPED_DEK_LEN];
    wrapped.copy_from_slice(&encrypted);
    Ok(wrapped)
}

/// Unwrap (decrypt) the DEK using the password-derived KEK.
pub fn unwrap_dek(
    kek: &[u8; KEY_LEN],
    nonce: &[u8; AEAD_NONCE_LEN],
    wrapped: &[u8; WRAPPED_DEK_LEN],
    aad: &[u8],
) -> Result<Zeroizing<[u8; KEY_LEN]>, String> {
    let decrypted = aead_decrypt(kek, nonce, wrapped, aad)?;
    if decrypted.len() != KEY_LEN {
        return Err("Unwrapped DEK length mismatch".into());
    }
    let mut dek = Zeroizing::new([0u8; KEY_LEN]);
    dek.copy_from_slice(&decrypted);
    Ok(dek)
}
