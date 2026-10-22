# ADR-0003: The versioned opaque container format

* Status: Accepted
* Date: 2026-10-21

## Context

Vault objects must be stored under opaque identifiers, must not look like
ordinary images or videos, must not leak the original file name or media
metadata, and must fail closed when they are modified, truncated or
substituted.

## Decision

Zarc defines a **versioned, fixed size header followed by an authenticated
secretstream**, specified in `docs/FILE_FORMAT.md`:

* a 152 byte header that contains only what is structurally required to
  decrypt the object (magic, version, object id, algorithm ids, the
  secretstream header, the wrapping nonce, the plaintext and ciphertext
  lengths, the chunk size and the wrapped data key),
* the encrypted content as a sequence of secretstream messages, the last of
  which carries the final tag,
* the whole header is used as associated data for every chunk and (except for
  the wrapped key itself) for the key envelope, so no header field can be
  changed without breaking an authentication.

## Rationale

* **Versioning.** The format starts at version 1. Unknown versions are
  rejected with an explicit error instead of being guessed.
* **Strict validation.** Reserved bytes must be zero, the algorithm ids must be
  known, the chunk size must be plausible and the declared lengths must match
  the chunk plan exactly. A hostile header therefore fails in the parser
  rather than deep inside the crypto code.
* **No descriptive metadata.** The header carries no file name, no MIME type,
  no dimensions, no timestamps. The object id is random and is also the file
  name, so the file name leaks nothing about the content.
* **Deterministic chunk plan.** The number of chunks and their boundaries are
  a pure function of the plaintext length and the chunk size. The writer and
  the reader therefore agree without having to store a chunk table, and the
  reader can detect trailing data.
* **A random object id that is authenticated.** The id is part of the
  envelope's associated data and must match the file name, so an envelope
  cannot be moved to a different object.

## Consequences

* The format leaks the *size* of the plaintext (within the chunk size) and
  the number of objects. It cannot hide those without a padding or padding
  oracle design, which is out of scope for this phase and is documented as a
  residual risk.
* The format is not designed for streaming writes: the plaintext length has to
  be known before the header is written. Zarc therefore encrypts from a
  regular file, where the length is known. A future "import from a stream"
  feature would need either a length prefix in the header or a padding scheme.
* Future versions may add fields. The header length is stored in the header,
  and the parser rejects a length it does not know, so a future version can
  extend the header without breaking the version check.
