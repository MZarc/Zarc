package com.zarc.vault.core

import android.content.Context
import com.zarc.vault.keystore.KeystoreFailure
import com.zarc.vault.keystore.KeystoreKeyManager
import com.zarc.vault.keystore.KeystoreReport
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The application level facade of the vault.
 *
 * It owns the directories, the vault master key lifecycle and the calls into
 * the Rust core. Media bytes never leave the Rust core: the repository only
 * hands over file paths and the (unwrapped) vault master key.
 *
 * Everything lives in `noBackupFilesDir`, so neither the vault objects nor the
 * wrapped vault master key can ever end up in an automatic backup, on top of
 * the backup rules declared in the manifest.
 */
class VaultRepository(context: Context) {

    private val appContext = context.applicationContext
    private val keystore = KeystoreKeyManager(appContext)

    /** The directory that holds the encrypted vault objects. */
    val objectsDir: File = File(appContext.noBackupFilesDir, "vault/objects")

    /** The directory that receives decrypted files. */
    val exportDir: File = File(appContext.noBackupFilesDir, "vault/export")

    /** The scratch directory used for the self test and for staging. */
    val workDir: File = File(appContext.cacheDir, "vault/work")

    /** The passphrase protected copy of the vault master key. */
    val recoveryEnvelopeFile: File = File(appContext.noBackupFilesDir, "keys/vmk.recovery")

    /** The witness that proves a recovered key is the vault master key. */
    val recoveryWitnessFile: File = File(appContext.noBackupFilesDir, "keys/vmk.witness")

    /** The staged vault master key of a rotation that is in progress. */
    val stagedKeyFile: File = File(appContext.noBackupFilesDir, "keys/vmk.next")

    /** Create the vault directories with owner-only permissions. */
    fun ensureDirectories() {
        for (directory in listOf(objectsDir, exportDir, workDir)) {
            if (!directory.exists()) {
                directory.mkdirs()
            }
            restrictToOwner(directory, isDirectory = true)
        }
    }

    /** What the platform reports about the key that protects the vault. */
    fun keystoreReport(): KeystoreReport = keystore.ensureKey().report

    /** Whether the device advertises a StrongBox Keymaster implementation. */
    fun hasStrongBox(): Boolean = keystore.hasStrongBox()

    /** The location of the wrapped vault master key. */
    fun wrappedKeyLocation(): String = keystore.wrappedKeyFile.absolutePath

    /** A description of the linked crypto library. */
    fun cryptoInfo(): String = ZarcNative.nativeCryptoInfo()

    /** Run the full crypto self test inside the application. */
    fun selfTest(): String {
        ensureDirectories()
        return ZarcNative.nativeSelfTest(workDir.absolutePath)
    }

    /**
     * Return the vault master key, creating and wrapping it on first use.
     *
     * The caller owns the returned array and must zeroize it after use.
     */
    fun vaultMasterKey(): ByteArray {
        ensureDirectories()
        val state = keystore.ensureKey()
        val wrapped = keystore.wrappedKeyFile
        if (state.created && !wrapped.exists() && hasStoredObjects()) {
            // The Keystore key was lost while the vault still holds objects.
            // Starting over would silently orphan them, so the vault reports
            // that it needs the recovery passphrase instead.
            throw KeystoreFailure(
                "the Android Keystore key was lost; the vault can only be opened " +
                    "with the recovery passphrase",
            )
        }
        if (state.created && wrapped.exists()) {
            // The Keystore key was lost (for example because the user cleared
            // the keystore or the device was reset) and has just been
            // recreated. The wrapped vault master key can never be unwrapped
            // again: the vault is lost, and Zarc says so instead of failing
            // later with a confusing authentication error.
            throw KeystoreFailure(
                "the Android Keystore key was lost and has been recreated; " +
                    "the vault master key can no longer be unwrapped and the " +
                    "vault is unrecoverable",
            )
        }
        if (!wrapped.exists()) {
            val fresh = ByteArray(KeystoreKeyManager.VAULT_MASTER_KEY_BYTES)
            SecureRandom().nextBytes(fresh)
            val wrappedMaterial = keystore.wrapVaultMasterKey(fresh)
            try {
                wrapped.parentFile?.mkdirs()
                wrapped.writeBytes(wrappedMaterial)
                restrictToOwner(wrapped, isDirectory = false)
            } finally {
                fresh.fill(0)
            }
        }
        val material = wrapped.readBytes()
        return keystore.unwrapVaultMasterKey(material)
    }

    /** Encrypt [source] into the vault and return the object id. */
    fun encrypt(source: File): String {
        val key = vaultMasterKey()
        return try {
            ZarcNative.nativeEncryptFile(key, source.absolutePath, objectsDir.absolutePath)
        } finally {
            key.fill(0)
        }
    }

    /** Decrypt an object into the export directory and return the file. */
    fun decrypt(objectId: String, destinationName: String): File {
        ensureDirectories()
        val key = vaultMasterKey()
        return try {
            val path = ZarcNative.nativeDecryptFile(
                key,
                objectId,
                objectsDir.absolutePath,
                exportDir.absolutePath,
                destinationName,
            )
            File(path)
        } finally {
            key.fill(0)
        }
    }

    /** Verify an object without producing plaintext. */
    fun verify(objectId: String): String {
        val key = vaultMasterKey()
        return try {
            ZarcNative.nativeVerifyObject(key, objectId, objectsDir.absolutePath)
        } finally {
            key.fill(0)
        }
    }

    /** The ids of the objects in the vault. */
    fun listObjects(): List<String> {
        ensureDirectories()
        val listing = ZarcNative.nativeListObjects(objectsDir.absolutePath)
        return listing.split('\n').filter { it.isNotBlank() }
    }

    /** The SHA-256 digest of a file, as lowercase hexadecimal. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Create a passphrase protected copy of the vault master key.
     *
     * The passphrase is converted to bytes, handed to the Rust core and
     * zeroized immediately afterwards. The passphrase itself is never stored.
     *
     * @return true when a recovery envelope was created.
     */
    fun enableRecovery(passphrase: CharArray): Boolean {
        ensureDirectories()
        val key = vaultMasterKey()
        val passphraseBytes = passphrase.toUtf8Bytes()
        return try {
            val envelope = ZarcNative.nativeCreateRecoveryEnvelope(key, passphraseBytes)
            val witness = ZarcNative.nativeCreateRecoveryWitness(key)
            recoveryEnvelopeFile.parentFile?.mkdirs()
            recoveryEnvelopeFile.writeBytes(envelope)
            restrictToOwner(recoveryEnvelopeFile, isDirectory = false)
            recoveryWitnessFile.writeBytes(witness)
            restrictToOwner(recoveryWitnessFile, isDirectory = false)
            true
        } finally {
            passphraseBytes.fill(0)
            key.fill(0)
        }
    }

    /** Remove the recovery envelope and the witness. */
    fun disableRecovery() {
        recoveryEnvelopeFile.delete()
        recoveryWitnessFile.delete()
    }

    /** Whether a recovery envelope exists. */
    fun hasRecovery(): Boolean = recoveryEnvelopeFile.exists() && recoveryWitnessFile.exists()

    /**
     * Recover the vault master key with the passphrase.
     *
     * The recovered key is checked against the witness, so a key that is not
     * the vault master key is rejected even if the envelope decrypted.
     */
    fun recoverVaultMasterKey(passphrase: CharArray): ByteArray {
        require(hasRecovery()) { "no recovery envelope has been created" }
        val passphraseBytes = passphrase.toUtf8Bytes()
        return try {
            val recovered = ZarcNative.nativeOpenRecoveryEnvelope(
                recoveryEnvelopeFile.readBytes(),
                passphraseBytes,
            )
            val witness = recoveryWitnessFile.readBytes()
            if (!ZarcNative.nativeVerifyRecoveryWitness(witness, recovered)) {
                recovered.fill(0)
                throw com.zarc.vault.keystore.KeystoreFailure(
                    "the recovered key is not the vault master key of this vault",
                )
            }
            recovered
        } finally {
            passphraseBytes.fill(0)
        }
    }

    /**
     * Rotate the vault master key.
     *
     * Every object keeps its content; only the wrapping of its data key is
     * redone. The new key is staged before the rotation and committed after
     * it, so an interrupted rotation can be finished on the next start.
     *
     * @return the number of objects that were re-wrapped.
     */
    fun rotateVaultMasterKey(): Int {
        ensureDirectories()
        val oldKey = vaultMasterKey()
        val newKey = ByteArray(KeystoreKeyManager.VAULT_MASTER_KEY_BYTES)
        SecureRandom().nextBytes(newKey)
        return try {
            // Stage the new key first: it is wrapped by the Keystore key and
            // written next to the active one.
            val staged = keystore.wrapVaultMasterKey(newKey)
            stagedKeyFile.parentFile?.mkdirs()
            stagedKeyFile.writeBytes(staged)
            restrictToOwner(stagedKeyFile, isDirectory = false)

            // Re-wrap the data keys of every object.
            val rotated = ZarcNative.nativeRotateMasterKey(
                oldKey,
                newKey,
                objectsDir.absolutePath,
            )

            // Commit: the staged key becomes the active one.
            val active = File(appContext.noBackupFilesDir, "keys/vmk.wrapped")
            if (!stagedKeyFile.renameTo(active)) {
                throw com.zarc.vault.keystore.KeystoreFailure(
                    "the rotated vault master key could not be committed",
                )
            }
            rotated
        } finally {
            newKey.fill(0)
            oldKey.fill(0)
        }
    }

    /**
     * Finish a rotation that was interrupted.
     *
     * @return true when a staged key was found and the rotation was finished.
     */
    fun recoverPendingRotation(): Boolean {
        if (!stagedKeyFile.exists()) return false
        val stagedKey = keystore.unwrapVaultMasterKey(stagedKeyFile.readBytes())
        val activeKey = try {
            vaultMasterKey()
        } catch (error: Exception) {
            stagedKey.fill(0)
            throw error
        }
        return try {
            ZarcNative.nativeRotateMasterKey(
                activeKey,
                stagedKey,
                objectsDir.absolutePath,
            )
            val active = File(appContext.noBackupFilesDir, "keys/vmk.wrapped")
            stagedKeyFile.renameTo(active)
            true
        } finally {
            stagedKey.fill(0)
            activeKey.fill(0)
        }
    }

    /** Whether the vault directory already holds objects. */
    fun hasStoredObjects(): Boolean =
        objectsDir.listFiles()?.any { it.isFile } == true

    /**
     * Decrypt an object with an explicitly provided vault master key.
     *
     * This is the path used after a recovery: the key comes from the recovery
     * envelope instead of the Android Keystore.
     */
    fun decryptWithKey(
        vaultMasterKey: ByteArray,
        objectId: String,
        destinationName: String,
    ): File {
        ensureDirectories()
        return ZarcNative.nativeDecryptFile(
            vaultMasterKey,
            objectId,
            objectsDir.absolutePath,
            exportDir.absolutePath,
            destinationName,
        ).let(::File)
    }

    /**
     * Re-establish the Keystore binding after a recovery.
     *
     * A fresh Keystore key is generated and the recovered master key is
     * wrapped under it, so the vault works normally again.
     */
    fun restoreKeystoreBinding(vaultMasterKey: ByteArray) {
        keystore.regenerateKey()
        val wrapped = keystore.wrapVaultMasterKey(vaultMasterKey)
        val active = File(appContext.noBackupFilesDir, "keys/vmk.wrapped")
        active.parentFile?.mkdirs()
        active.writeBytes(wrapped)
        restrictToOwner(active, isDirectory = false)
    }

    /** Re-encrypt an object with a fresh data key. */
    fun reencryptObject(objectId: String): String {
        val key = vaultMasterKey()
        return try {
            ZarcNative.nativeReencryptObject(key, objectId, objectsDir.absolutePath)
        } finally {
            key.fill(0)
        }
    }

    /** The names of the files in the vault directory, for the audit view. */
    fun vaultFileNames(): List<String> =
        objectsDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()

    /** Convert a passphrase into UTF-8 bytes without keeping the string. */
    private fun CharArray.toUtf8Bytes(): ByteArray {
        val chars = this
        val buffer = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars))
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    private fun restrictToOwner(file: File, isDirectory: Boolean) {
        // Remove every permission first, then grant the owner exactly what it
        // needs. Directories keep the execute bit, otherwise the owner could
        // not traverse them.
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
        if (isDirectory) {
            file.setExecutable(true, true)
        }
    }
}
