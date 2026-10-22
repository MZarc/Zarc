# ADR-0005: A passphrase recovery envelope and key rotation

* Status: Accepted
* Date: 2026-10-21
* Supersedes: nothing. It extends [ADR-0004](ADR-0004-device-bound-key-hierarchy.md).

## Context

ADR-0004 documented the consequence of a purely device bound key hierarchy: the
vault is unrecoverable when the Android Keystore key is lost. That is a
deliberate trade-off, but it is a *bad* one for a vault that holds a user's
private photos: a factory reset, a device replacement or a keystore
invalidation would destroy years of media with no way back.

The brief asks for a documented recovery extension point, a proper password
based KDF, and key rotation.

## Decision

Zarc adds two capabilities.

**1. A passphrase recovery envelope.**

```text
passphrase --Argon2id(salt, ops=3, mem=64 MiB)--> wrapping key
          --XChaCha20-Poly1305--> vault master key
```

* Argon2id is the password based KDF of the *same* library that provides the
  content encryption (libsodium's `crypto_pwhash_argon2id`). No new dependency
  and no home grown KDF.
* The envelope stores only: the format version, a random 16 byte salt, the
  Argon2id cost parameters, a random 24 byte nonce, the wrapped master key and
  its 128 bit authentication tag. The passphrase is never stored.
* The cost parameters are part of the envelope's associated data, so they
  cannot be lowered without breaking the authentication. A future version can
  raise them and re-derive the key.
* A **witness** - a constant sealed under the master key - lets the code
  verify that a recovered key really is the vault master key of this vault and
  not some other key that happened to decrypt.
* The derived key, the passphrase bytes and the recovered key are zeroized as
  soon as they have been used.

**2. Key rotation.**

* *Master key rotation* re-wraps the data key of every object under a new
  master key. **The content is not re-encrypted**: the data key does not
  change, so the encrypted stream stays byte for byte identical. The
  operation is idempotent, so an interrupted rotation can simply be repeated.
* *Object re-encryption* decrypts and re-encrypts one object with a fresh data
  key, a fresh secretstream header and a fresh envelope nonce, keeping the
  identity of the object. The object is replaced atomically.
* *Keystore key rotation* deletes the Keystore key, generates a new one under
  the same alias and re-wraps the master key.

## Consequences

* The vault master key is no longer strictly device bound: whoever holds the
  passphrase and the envelope can open the vault. The device bound envelope
  is still the primary path and the passphrase is an additional factor, not a
  replacement.
* The container format changed: the stream associated data now covers only the
  *immutable* header fields, so that the mutable ones (the envelope nonce, the
  wrapped data key and its tag) can change during a rotation without
  invalidating the content. The envelope associated data still authenticates
  them, together with the object id. The format specification in
  `docs/FILE_FORMAT.md` was updated accordingly; no deployed object exists.
* A passphrase is a weak secret compared to a 256 bit key. The Argon2id cost
  parameters slow down guessing, but a user who chooses "123456" has a weak
  vault. The user interface states the minimum length; the real guidance is a
  generated passphrase.
* The rotation is a maintenance operation that rewrites every object file. It
  is not fast for a multi-gigabyte vault and should not run while the battery
  is low.
* The recovery envelope raises the stakes of a device compromise: the envelope
  is on the device, so an attacker who gets the device also gets the
  *encrypted* copy of the master key. Without the passphrase that is still
  protected by Argon2id, but it is no longer free.
