package com.zarc.vault.core

/**
 * The bridge to the Rust vault core.
 *
 * Only file paths, object identifiers and the vault master key cross this
 * boundary - media bytes never travel through the JVM.
 */
object ZarcNative {

    init {
        System.loadLibrary("zarc_jni")
    }

    /** Runs the complete crypto self test in [workDir] and returns a report. */
    external fun nativeSelfTest(workDir: String): String

    /** Encrypts [sourcePath] into [objectsDir] and returns the object id. */
    external fun nativeEncryptFile(
        vaultMasterKey: ByteArray,
        sourcePath: String,
        objectsDir: String,
    ): String

    /** Decrypts an object into `destinationDir/destinationName`. */
    external fun nativeDecryptFile(
        vaultMasterKey: ByteArray,
        objectId: String,
        objectsDir: String,
        destinationDir: String,
        destinationName: String,
    ): String

    /** Verifies an object end to end without producing plaintext. */
    external fun nativeVerifyObject(
        vaultMasterKey: ByteArray,
        objectId: String,
        objectsDir: String,
    ): String

    /** Lists the object ids stored in [objectsDir], one per line. */
    external fun nativeListObjects(objectsDir: String): String

    /** Returns information about the linked crypto library. */
    external fun nativeCryptoInfo(): String

    /** Creates a recovery envelope that protects the vault master key. */
    external fun nativeCreateRecoveryEnvelope(
        vaultMasterKey: ByteArray,
        passphrase: ByteArray,
    ): ByteArray

    /** Opens a recovery envelope and returns the vault master key. */
    external fun nativeOpenRecoveryEnvelope(
        envelope: ByteArray,
        passphrase: ByteArray,
    ): ByteArray

    /** Creates the witness that proves a key is the vault master key. */
    external fun nativeCreateRecoveryWitness(vaultMasterKey: ByteArray): ByteArray

    /** Checks that a key is the vault master key of this vault. */
    external fun nativeVerifyRecoveryWitness(
        witness: ByteArray,
        vaultMasterKey: ByteArray,
    ): Boolean

    /** Re-wraps every data key under a new vault master key. */
    external fun nativeRotateMasterKey(
        oldVaultMasterKey: ByteArray,
        newVaultMasterKey: ByteArray,
        objectsDir: String,
    ): Int

    /** Seals an arbitrary blob under the vault master key. */
    external fun nativeSealBlob(
        vaultMasterKey: ByteArray,
        plaintext: ByteArray,
    ): ByteArray

    /** Opens a blob that was sealed with the vault master key. */
    external fun nativeOpenBlob(
        vaultMasterKey: ByteArray,
        blob: ByteArray,
    ): ByteArray

    /** Derives a purpose bound subkey from the vault master key. */
    external fun nativeDeriveSubkey(
        vaultMasterKey: ByteArray,
        info: String,
    ): ByteArray

    /** Re-encrypts an object with a fresh data key. */
    external fun nativeReencryptObject(
        vaultMasterKey: ByteArray,
        objectId: String,
        objectsDir: String,
    ): String
}
