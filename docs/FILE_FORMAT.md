# The Zarc encrypted object format (version 1)

An object is a single file in the vault directory. The file name is the
object identifier in lowercase hexadecimal; the file has **no extension**.

```text
+---------------------------+  offset 0
|  header (152 bytes)       |
+---------------------------+  offset 152
|  ciphertext region        |  exactly `ciphertext_length` bytes
+---------------------------+  end of file
```

The ciphertext region is a sequence of secretstream messages. Each message is
one encrypted chunk of at most `chunk_size` plaintext bytes, plus the 17
bytes of secretstream overhead (1 tag byte + 16 authentication bytes). The
**last** message of an object carries the `TAG_FINAL` tag.

## Header

| Offset | Size | Field | Value |
|--------|------|-------|-------|
| 0 | 4 | `magic` | `"ZARC"` (0x5A 0x41 0x52 0x43) |
| 4 | 1 | `format_version` | 1 |
| 5 | 2 | `header_length` | 152 (u16, little endian) |
| 7 | 1 | `flags` | 0 |
| 8 | 4 | reserved | 0 |
| 12 | 16 | `object_id` | random; also the file name in hex |
| 28 | 1 | `envelope_algo` | 1 = XChaCha20-Poly1305-IETF |
| 29 | 1 | `stream_algo` | 1 = XChaCha20-Poly1305 secretstream |
| 30 | 2 | reserved | 0 |
| 32 | 24 | `secretstream_header` | libsodium secretstream header |
| 56 | 24 | `envelope_nonce` | XChaCha20-Poly1305 nonce of the key envelope (mutable: it changes during a key rotation) |
| 80 | 8 | `plaintext_length` | length of the original data (u64, little endian) |
| 88 | 8 | `ciphertext_length` | length of the ciphertext region (u64, little endian) |
| 96 | 4 | `chunk_size` | plaintext bytes per secretstream message (u32, little endian) |
| 100 | 4 | reserved | 0 |
| 104 | 32 | `wrapped_dek` | the data key, encrypted under the vault master key (mutable) |
| 136 | 16 | `wrapped_dek_mac` | Poly1305 tag of the key envelope (mutable) |
| 152 | - | end of header | |

All integers are little endian.

## Validation rules

A parser must reject a container when any of the following does not hold. Zarc
implements exactly these rules; there is no "best effort" parsing path.

1. the magic is exactly `"ZARC"`,
2. `format_version` is a version this build understands (currently 1),
3. `header_length` is exactly 152,
4. `flags` is 0 and every reserved field is 0,
5. `envelope_algo` and `stream_algo` are known,
6. `chunk_size` is between 1024 and 1 MiB,
7. `plaintext_length` is at most 2^48 (a sanity bound; the value is never used
   to allocate memory),
8. `ciphertext_length` equals `plaintext_length + 17 * chunk_count`, where
   `chunk_count = max(1, ceil(plaintext_length / chunk_size))`,
   and the arithmetic is checked for overflow,
9. the file contains exactly `152 + ciphertext_length` bytes; trailing data is
   rejected,
10. the file name equals the hex encoding of `object_id`.

The chunk plan is a pure function of the plaintext length and the chunk size,
so the writer and the reader always agree on the chunk boundaries:

```text
chunk_count        = plaintext_length == 0 ? 1 : ceil(plaintext_length / chunk_size)
plain_len(chunk i) = min(chunk_size, plaintext_length - i * chunk_size)
cipher_len(chunk i)= plain_len(chunk i) + 17
```

An empty object therefore still has exactly one (empty) secretstream message,
so that "the stream was completed" is always an authenticated property.

## Authentication

Two independent authentications cover every object.

**Stream authentication.** Every secretstream message is pushed and pulled
with the same associated data:

```text
stream_aad = "zarc.stream.v1" || header[0..56] || header[80..104]
```

`header[0..56]` covers the magic, the version, the object id, the algorithm
ids and the secretstream header; `header[80..104]` covers the plaintext
length, the ciphertext length and the chunk size. Changing any of them
invalidates the authentication of every chunk.

The three *mutable* fields - the envelope nonce (`header[56..80]`), the
wrapped data key (`header[104..136]`) and its tag (`header[136..152]`) - are
deliberately **not** covered by the stream associated data: they change when
the vault master key is rotated or when an object is re-encrypted, and the
content stream has to survive that. They are covered by the envelope
associated data instead, which authenticates them together with the object
id, so they still cannot be tampered with.

**Envelope authentication.** The data key is wrapped with
XChaCha20-Poly1305-IETF under the vault master key with the associated data:

```text
envelope_aad = "zarc.dek.v1" || header[0..104]
```

`header[0..104]` is every header byte up to (but excluding) the wrapped key
itself. It therefore binds the key envelope to the object id, the format
version, the algorithm ids, the secretstream header, the nonce and the
declared lengths. Moving a valid envelope to another object does not make it
valid in the wrong context, because the associated data changes.

The order of operations during encryption is:

1. generate the object id, the data key and the envelope nonce,
2. compute the lengths from the plaintext length and the chunk size,
3. build the header (the wrapped key fields are still empty),
4. derive `envelope_aad` from the header and wrap the data key,
5. write the wrapped key into the header,
6. derive `stream_aad` from the header and encrypt the content.

Step 3 and 4 are safe because `envelope_aad` does not cover the wrapped key
bytes; step 5 and 6 are safe because the stream associated data only covers
the immutable fields, which step 5 does not change.

## What the format does *not* contain

The header deliberately contains **no descriptive metadata**:

* no original file name,
* no MIME type or media type,
* no image dimensions, no EXIF, no GPS data,
* no capture timestamp,
* no album name or user chosen label,
* no thumbnail, no colour information, no file size of the original beyond
  the length that is structurally required.

The only information an attacker can read from a vault file is:

* that it is a Zarc object (`"ZARC"` magic),
* the format version,
* the object id (which is the file name),
* the plaintext length (rounded up to the chunk size),
* the number of objects.

This is metadata leakage, and it is **not** zero. See the leakage section
below.

## Metadata leakage analysis

**What leaks.**

* *Existence and count.* The number of objects is visible to anyone who can
  list the vault directory.
* *Size.* `plaintext_length` is stored in the clear. The exact plaintext
  length of every object is therefore readable by anyone with the file.
* *Structure.* `chunk_size` and the chunk count are visible, so the number of
  secretstream messages is known.
* *The format marker.* `"ZARC"` identifies the file as a Zarc object. It does
  not reveal the original media format.
* *Timing and access patterns.* When a file is opened, read or decrypted is
  observable to the system and to anyone with access to the device.
* *Storage usage.* The total space used by the vault is visible.

**What does not leak.**

* The file name is a random 128-bit identifier, not a hint about the content.
* The first bytes of the file are the header, not the magic bytes of a JPEG,
  PNG, WebP or MP4 stream, so a media scanner or a "file type" probe does not
  recognise the object as media.
* The content is encrypted with a stream cipher and an AEAD, so no
  statistical structure of the plaintext is recoverable from the ciphertext
  beyond its length.
* The presence of the plaintext inside the vault was checked by the tests:
  the plaintext marker never appears in a stored object.

**Explicitly not claimed.**

* Zarc does **not** claim that the existence of encrypted data is
  undetectable. The `"ZARC"` marker, the object count, the sizes and the
  timing are all observable.
* Zarc does **not** hide the plaintext length. A padding scheme that hides
  the length would leak its padding oracle instead; that is a design
  decision for a future version and is documented as an open item in
  `docs/THREAT_MODEL.md`.

## Forward compatibility

* `format_version` is checked before anything else is interpreted.
* `header_length` is stored in the header, so a future version may extend
  the header. A parser that does not know the length rejects the object
  instead of misinterpreting it.
* Unknown algorithm ids are rejected explicitly.
* The object id, the nonce and the secretstream header have fixed sizes that
  are dictated by the primitives and cannot change without a new version.

## Worked example

An object of 150 000 bytes with a chunk size of 65 536:

```text
plaintext_length  = 150000
chunk_count       = ceil(150000 / 65536) = 3
chunk plaintexts  = 65536, 65536, 18928
ciphertext_length = 150000 + 3 * 17 = 150051
file size         = 152 + 150051 = 150203
```
