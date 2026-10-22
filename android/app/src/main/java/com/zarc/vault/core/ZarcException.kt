package com.zarc.vault.core

/**
 * Exception thrown by the Rust vault core through the JNI bridge.
 *
 * The message carries a stable, machine readable description of the failure
 * (for example `authentication failure: ...`); it never contains key material
 * or plaintext.
 */
class ZarcException(message: String) : Exception(message)
