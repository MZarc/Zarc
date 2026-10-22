//! The recovery envelope: a passphrase protected copy of the vault master key.
//!
//! The vault master key is normally only unwrapped by the Android Keystore,
//! which makes it device bound: a factory reset, a wiped keystore or a
//! replaced device destroys the vault. A recovery envelope is an *additional*
//! way to obtain the *same* master key:
//!
//! ```text
//! passphrase --Argon2id--> wrapping key --XChaCha20-Poly1305--> vault master key
//! ```
//!
//! Properties:
//!
//! * the passphrase is never stored; only a random salt, the Argon2id cost
//!   parameters, a random nonce, the wrapped key and its authentication tag
//!   are stored,
//! * the derived key and the passphrase bytes are zeroized as soon as they
//!   have been used,
//! * a wrong passphrase is detected by the authentication tag, so a wrong
//!   passphrase can never produce a usable key,
//! * a *witness* (a constant sealed under the master key) lets the code
//!   verify that a recovered key is really the vault master key and not a
//!   different key that happened to decrypt.

use zeroize::{Zeroize, ZeroizeOnDrop};

use crate::error::{Error, Result};
use crate::keywrap::VaultMasterKey;
use crate::sodium;

/// The Argon2id salt size in bytes.
pub const SALT_BYTES: usize = 16;

/// The recovery envelope format version.
pub const FORMAT_VERSION: u8 = 1;

/// The salt length as it is stored in the envelope.
const SALT_LEN_U16: u16 = 16;

/// The Argon2id cost parameters used when a recovery envelope is created.
///
/// These are slightly above libsodium's "interactive" profile
/// (2 operations, 64 MiB) so that a stolen envelope is expensive to attack
/// on a phone while staying usable on low end devices.
pub const ARGON2_OPSLIMIT: u64 = 3;

/// The Argon2id memory limit in bytes (64 MiB).
pub const ARGON2_MEMLIMIT: usize = 67_108_864;

/// The minimum length of a recovery passphrase in bytes.
pub const MIN_PASSPHRASE_BYTES: usize = 8;

/// The constant that is sealed under the master key to build the witness.
const WITNESS_PLAINTEXT: &[u8] = b"zarc.vmk.witness.v1";

/// A passphrase protected copy of the vault master key.
#[derive(Clone)]
pub struct RecoveryEnvelope {
    salt: [u8; SALT_BYTES],
    opslimit: u64,
    memlimit: usize,
    nonce: [u8; 24],
    ciphertext: Vec<u8>,
    mac: [u8; 16],
}

impl RecoveryEnvelope {
    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// Create an envelope for `vmk`, protected by `passphrase`.
    pub fn create(vmk: &VaultMasterKey, passphrase: &[u8]) -> Result<Self> {
        if passphrase.len() < MIN_PASSPHRASE_BYTES {
            return Err(Error::InvalidArgument(format!(
                "a recovery passphrase must be at least {MIN_PASSPHRASE_BYTES} bytes long"
            )));
        }
        let mut salt = [0u8; SALT_BYTES];
        sodium::random_bytes(&mut salt)?;
        let mut nonce = [0u8; 24];
        sodium::random_bytes(&mut nonce)?;

        let mut derived =
            sodium::derive_key_argon2id(passphrase, &salt, ARGON2_OPSLIMIT, ARGON2_MEMLIMIT)?;
        let ad = envelope_aad(&salt, ARGON2_OPSLIMIT, ARGON2_MEMLIMIT);
        let result = sodium::aead_seal(&derived, &nonce, &ad, vmk.as_bytes());
        derived.zeroize();
        let (ciphertext, mac) = result?;

        let mut mac_array = [0u8; 16];
        if mac.len() != mac_array.len() {
            return Err(Error::Crypto("unexpected AEAD tag length".into()));
        }
        mac_array.copy_from_slice(&mac);

        Ok(Self {
            salt,
            opslimit: ARGON2_OPSLIMIT,
            memlimit: ARGON2_MEMLIMIT,
            nonce,
            ciphertext,
            mac: mac_array,
        })
    }

    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// Unwrap the vault master key with `passphrase`.
    ///
    /// A wrong passphrase is reported as an authentication failure; the
    /// caller cannot distinguish "wrong passphrase" from "modified envelope"
    /// without a second factor, and neither case produces a key.
    pub fn open(&self, passphrase: &[u8]) -> Result<VaultMasterKey> {
        if passphrase.len() < MIN_PASSPHRASE_BYTES {
            return Err(Error::InvalidArgument(
                "the recovery passphrase is too short".into(),
            ));
        }
        let mut derived =
            sodium::derive_key_argon2id(passphrase, &self.salt, self.opslimit, self.memlimit)?;
        let ad = envelope_aad(&self.salt, self.opslimit, self.memlimit);
        let result = sodium::aead_open(&derived, &self.nonce, &ad, &self.ciphertext, &self.mac);
        derived.zeroize();
        let plaintext = result?;
        if plaintext.len() != 32 {
            return Err(Error::Crypto(
                "the recovered key has an unexpected length".into(),
            ));
        }
        let mut bytes = [0u8; 32];
        bytes.copy_from_slice(&plaintext);
        Ok(VaultMasterKey::from_bytes(bytes))
    }

    /// The Argon2id cost parameters that were used.
    #[must_use]
    pub fn parameters(&self) -> (u64, usize) {
        (self.opslimit, self.memlimit)
    }

    /// Serialise the envelope.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut out =
            Vec::with_capacity(1 + 1 + 4 + 8 + 8 + SALT_BYTES + 24 + self.ciphertext.len() + 16);
        out.push(FORMAT_VERSION);
        out.push(0); // reserved
        out.extend_from_slice(&SALT_LEN_U16.to_le_bytes());
        out.extend_from_slice(&self.opslimit.to_le_bytes());
        out.extend_from_slice(&(self.memlimit as u64).to_le_bytes());
        out.extend_from_slice(&self.salt);
        out.extend_from_slice(&self.nonce);
        out.extend_from_slice(&self.ciphertext);
        out.extend_from_slice(&self.mac);
        out
    }

    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// Parse an envelope.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        let header_len = 2 + 2 + 8 + 8;
        if bytes.len() < header_len + SALT_BYTES + 24 + 32 + 16 {
            return Err(Error::Format("the recovery envelope is truncated".into()));
        }
        if bytes[0] != FORMAT_VERSION {
            return Err(Error::UnsupportedVersion(bytes[0]));
        }
        if bytes[1] != 0 {
            return Err(Error::Format(
                "the recovery envelope has unknown flags".into(),
            ));
        }
        let salt_len = u16::from_le_bytes([bytes[2], bytes[3]]);
        if salt_len as usize != SALT_BYTES {
            return Err(Error::Format(
                "the recovery envelope has an unexpected salt".into(),
            ));
        }
        let opslimit = u64::from_le_bytes([
            bytes[4], bytes[5], bytes[6], bytes[7], bytes[8], bytes[9], bytes[10], bytes[11],
        ]);
        let memlimit = u64::from_le_bytes([
            bytes[12], bytes[13], bytes[14], bytes[15], bytes[16], bytes[17], bytes[18], bytes[19],
        ]);
        if opslimit == 0 || memlimit == 0 {
            return Err(Error::Format(
                "the recovery envelope has invalid cost parameters".into(),
            ));
        }
        let memlimit = usize::try_from(memlimit)
            .map_err(|_| Error::Format("the recovery envelope memory limit is invalid".into()))?;

        let mut cursor = header_len;
        let mut salt = [0u8; SALT_BYTES];
        salt.copy_from_slice(&bytes[cursor..cursor + SALT_BYTES]);
        cursor += SALT_BYTES;
        let mut nonce = [0u8; 24];
        nonce.copy_from_slice(&bytes[cursor..cursor + 24]);
        cursor += 24;
        let ciphertext_end = bytes.len() - 16;
        if ciphertext_end < cursor {
            return Err(Error::Format("the recovery envelope is truncated".into()));
        }
        let ciphertext = bytes[cursor..ciphertext_end].to_vec();
        let mut mac = [0u8; 16];
        mac.copy_from_slice(&bytes[ciphertext_end..]);
        if ciphertext.len() != 32 {
            return Err(Error::Format(
                "the recovery envelope does not contain a master key".into(),
            ));
        }

        Ok(Self {
            salt,
            opslimit,
            memlimit,
            nonce,
            ciphertext,
            mac,
        })
    }
}

/// The witness: proof that a key is the vault master key of this vault.
///
/// It is a constant sealed under the master key. It contains no secret that
/// the master key does not already protect, and it allows the recovery path
/// to reject a key that decrypts the envelope but is not the right one.
#[derive(Clone)]
pub struct RecoveryWitness {
    nonce: [u8; 24],
    ciphertext: Vec<u8>,
    mac: [u8; 16],
}

impl RecoveryWitness {
    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// Create a witness for `vmk`.
    pub fn create(vmk: &VaultMasterKey) -> Result<Self> {
        let mut nonce = [0u8; 24];
        sodium::random_bytes(&mut nonce)?;
        let (ciphertext, mac) =
            sodium::aead_seal(vmk.as_bytes(), &nonce, WITNESS_PLAINTEXT, WITNESS_PLAINTEXT)?;
        let mut mac_array = [0u8; 16];
        mac_array.copy_from_slice(&mac);
        Ok(Self {
            nonce,
            ciphertext,
            mac: mac_array,
        })
    }

    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// Check that `vmk` is the key this witness was created for.
    pub fn verify(&self, vmk: &VaultMasterKey) -> Result<()> {
        let plaintext = sodium::aead_open(
            vmk.as_bytes(),
            &self.nonce,
            WITNESS_PLAINTEXT,
            &self.ciphertext,
            &self.mac,
        )?;
        if plaintext != WITNESS_PLAINTEXT {
            return Err(Error::Authentication(
                "the recovered key is not the vault master key".into(),
            ));
        }
        Ok(())
    }

    /// Serialise the witness.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(24 + self.ciphertext.len() + 16);
        out.extend_from_slice(&self.nonce);
        out.extend_from_slice(&self.ciphertext);
        out.extend_from_slice(&self.mac);
        out
    }

    /// # Errors
    /// #
    /// # Returns [`crate::Error`] when the operation cannot be completed; see
    /// # the error taxonomy in [`crate::error`] for the individual failure modes.
    /// Parse a witness.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        if bytes.len() < 24 + WITNESS_PLAINTEXT.len() + 16 {
            return Err(Error::Format("the recovery witness is truncated".into()));
        }
        let mut nonce = [0u8; 24];
        nonce.copy_from_slice(&bytes[..24]);
        let ciphertext_end = bytes.len() - 16;
        let ciphertext = bytes[24..ciphertext_end].to_vec();
        let mut mac = [0u8; 16];
        mac.copy_from_slice(&bytes[ciphertext_end..]);
        Ok(Self {
            nonce,
            ciphertext,
            mac,
        })
    }
}

/// The associated data of the key envelope: it binds the cost parameters and
/// the salt, so that they cannot be changed without breaking the
/// authentication.
fn envelope_aad(salt: &[u8; SALT_BYTES], opslimit: u64, memlimit: usize) -> Vec<u8> {
    let mut ad = Vec::with_capacity(32);
    ad.extend_from_slice(b"zarc.recovery.v1");
    ad.extend_from_slice(&opslimit.to_le_bytes());
    ad.extend_from_slice(&(memlimit as u64).to_le_bytes());
    ad.extend_from_slice(salt);
    ad
}

/// A passphrase that is zeroized when it is dropped.
#[derive(Zeroize, ZeroizeOnDrop)]
pub struct SecretPassphrase(Vec<u8>);

impl SecretPassphrase {
    /// Take ownership of the passphrase bytes.
    #[must_use]
    pub fn new(bytes: Vec<u8>) -> Self {
        Self(bytes)
    }

    /// The passphrase bytes.
    #[must_use]
    pub fn as_bytes(&self) -> &[u8] {
        &self.0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn envelope_round_trips() {
        let vmk = VaultMasterKey::generate().unwrap();
        let envelope = RecoveryEnvelope::create(&vmk, b"correct horse battery").unwrap();
        let recovered = envelope.open(b"correct horse battery").unwrap();
        assert_eq!(recovered.as_bytes(), vmk.as_bytes());
    }

    #[test]
    fn a_wrong_passphrase_is_rejected() {
        let vmk = VaultMasterKey::generate().unwrap();
        let envelope = RecoveryEnvelope::create(&vmk, b"correct horse battery").unwrap();
        let Err(error) = envelope.open(b"wrong horse battery") else {
            return;
        };
        assert!(matches!(error, Error::Authentication(_)));
    }

    #[test]
    fn a_short_passphrase_is_rejected() {
        let vmk = VaultMasterKey::generate().unwrap();
        assert!(RecoveryEnvelope::create(&vmk, b"short").is_err());
    }

    #[test]
    fn the_envelope_survives_a_round_trip_through_bytes() {
        let vmk = VaultMasterKey::generate().unwrap();
        let envelope = RecoveryEnvelope::create(&vmk, b"correct horse battery").unwrap();
        let decoded = RecoveryEnvelope::decode(&envelope.encode()).unwrap();
        assert_eq!(decoded.parameters(), envelope.parameters());
        let recovered = decoded.open(b"correct horse battery").unwrap();
        assert_eq!(recovered.as_bytes(), vmk.as_bytes());
    }

    #[test]
    fn a_modified_envelope_is_rejected() {
        let vmk = VaultMasterKey::generate().unwrap();
        let envelope = RecoveryEnvelope::create(&vmk, b"correct horse battery").unwrap();
        let mut bytes = envelope.encode();
        let last = bytes.len() - 1;
        bytes[last] ^= 0x01;
        let decoded = RecoveryEnvelope::decode(&bytes).unwrap();
        assert!(decoded.open(b"correct horse battery").is_err());
    }

    #[test]
    fn the_witness_accepts_only_the_right_key() {
        let vmk = VaultMasterKey::generate().unwrap();
        let other = VaultMasterKey::generate().unwrap();
        let witness = RecoveryWitness::create(&vmk).unwrap();
        assert!(witness.verify(&vmk).is_ok());
        assert!(witness.verify(&other).is_err());
        let decoded = RecoveryWitness::decode(&witness.encode()).unwrap();
        assert!(decoded.verify(&vmk).is_ok());
    }
}
