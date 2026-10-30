package com.zarc.vault.core

import android.content.Context
import com.zarc.vault.keystore.KeystoreFailure
import com.zarc.vault.keystore.KeystoreKeyManager
import com.zarc.vault.keystore.KeystoreReport
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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

    /** The directory that receives transient decrypted files. */
    val exportDir: File = File(appContext.cacheDir, "vault_export")

    /** The scratch directory used for the self test and for staging. */
    val workDir: File = File(appContext.cacheDir, "vault/work")

    /** The application private vault directory. */
    private val vaultDir: File get() = File(appContext.noBackupFilesDir, "vault")

    /** The passphrase protected copy of the vault master key. */
    val recoveryEnvelopeFile: File = File(appContext.noBackupFilesDir, "keys/vmk.recovery")

    /** The witness that proves a recovered key is the vault master key. */
    val recoveryWitnessFile: File = File(appContext.noBackupFilesDir, "keys/vmk.witness")

    /** The staged vault master key of a rotation that is in progress. */
    val stagedKeyFile: File = File(appContext.noBackupFilesDir, "keys/vmk.next")

    /** Guards against re-entering rotation recovery from [vaultMasterKey]. */
    private var handlingRotation = false

    /**
     * Number of already-damaged objects that the last rotation or recovery
     * left untouched. A non-zero value means the rest of the vault was rotated
     * successfully but those object files were unreadable before it started.
     */
    @Volatile
    var lastRotationSkipped: Int = 0
        private set

    /** Create the vault directories with owner-only permissions. */
    fun ensureDirectories() {
        for (directory in listOf(objectsDir, exportDir, workDir)) {
            if (!directory.exists()) {
                directory.mkdirs()
            }
            restrictToOwner(directory, isDirectory = true)
        }
    }

    /**
     * Remove plaintext leftovers from earlier runs.
     *
     * This runs at startup, before any viewer or archive session is active, so
     * it can safely remove every transient export and session directory.
     */
    fun purgeStaleTransientFiles() {
        ensureDirectories()
        exportDir.listFiles()?.forEach { it.deleteRecursively() }
        File(appContext.cacheDir, "exports").listFiles()?.forEach { it.deleteRecursively() }
        workDir.listFiles()?.forEach { it.deleteRecursively() }
        appContext.cacheDir.listFiles()?.forEach { entry ->
            val name = entry.name
            if (
                name.startsWith("inspect_") ||
                name.startsWith("built_") ||
                name.startsWith("decrypted_session_") ||
                name.startsWith("cover_")
            ) {
                entry.deleteRecursively()
            }
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
     * A rotation that was interrupted is finished before the active key is
     * returned, so no caller can ever see a vault whose objects and metadata
     * are split between two keys.
     *
     * The caller owns the returned array and must zeroize it after use.
     */
    @Synchronized
    fun vaultMasterKey(): ByteArray {
        ensureDirectories()
        recoverPendingRotation()
        return unwrapActiveVaultMasterKey()
    }

    /** Unwrap the active vault master key without running rotation recovery. */
    private fun unwrapActiveVaultMasterKey(): ByteArray {
        val state = keystore.ensureKey()
        val wrapped = keystore.wrappedKeyFile
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
        if (!wrapped.exists() && hasStoredObjects()) {
            // The wrapped key disappeared while the vault still holds
            // objects. Generating a fresh master key here would silently
            // orphan every object, so fail closed instead.
            throw KeystoreFailure(
                "the wrapped vault master key is missing while the vault still " +
                    "holds objects; the vault can only be opened with the " +
                    "recovery passphrase",
            )
        }
        if (!wrapped.exists()) {
            val fresh = ByteArray(KeystoreKeyManager.VAULT_MASTER_KEY_BYTES)
            SecureRandom().nextBytes(fresh)
            val wrappedMaterial = keystore.wrapVaultMasterKey(fresh)
            try {
                writeFileAtomically(wrapped, wrappedMaterial)
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
            writeFileAtomically(recoveryEnvelopeFile, envelope)
            restrictToOwner(recoveryEnvelopeFile, isDirectory = false)
            writeFileAtomically(recoveryWitnessFile, witness)
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
     * Every normal and binned object keeps its content; only the wrapping of
     * its data key is redone. The sealed index and the sealed thumbnails are
     * re-sealed under the new key as well. The new key is staged before the
     * rotation and committed after it, so an interrupted rotation can be
     * finished on the next start.
     *
     * Recovery is deliberately refused while a passphrase envelope exists:
     * the envelope is bound to the current master key and can only be
     * recreated with the user's passphrase. Disable recovery before rotating
     * and enable it again afterwards.
     *
     * @return the number of objects that were re-wrapped.
     */
    @Synchronized
    fun rotateVaultMasterKey(): Int {
        ensureDirectories()
        if (hasRecovery()) {
            throw KeystoreFailure(
                "recovery is enabled; disable recovery before rotating the " +
                    "master key and enable it again afterwards",
            )
        }
        if (hasPendingPrivateMigration()) {
            throw KeystoreFailure(
                "the private area still uses the legacy key hierarchy; unlock " +
                    "the private area once to finish its migration before " +
                    "rotating the master key",
            )
        }

        // Finish any earlier rotation before starting a new one. Otherwise the
        // staged key would be overwritten and the partial rotation would have
        // no way back.
        recoverPendingRotation()

        val oldKey = unwrapActiveVaultMasterKey()
        val newKey = ByteArray(KeystoreKeyManager.VAULT_MASTER_KEY_BYTES)
        SecureRandom().nextBytes(newKey)
        lastRotationSkipped = 0
        return try {
            // Stage the new key first: it is wrapped by the Keystore key and
            // written next to the active one.
            val staged = keystore.wrapVaultMasterKey(newKey)
            writeFileAtomically(stagedKeyFile, staged)
            restrictToOwner(stagedKeyFile, isDirectory = false)

            val outcome = rotateVaultArtifacts(oldKey, newKey)
            lastRotationSkipped = outcome.skipped

            // Commit: the staged key becomes the active one. The rename is
            // atomic on the app's private storage and replaces the old file.
            commitStagedKey()
            outcome.rotated
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
    @Synchronized
    fun recoverPendingRotation(): Boolean {
        if (!stagedKeyFile.exists() || handlingRotation) return false
        handlingRotation = true
        lastRotationSkipped = 0
        return try {
            val stagedKey = keystore.unwrapVaultMasterKey(stagedKeyFile.readBytes())
            val activeKey = unwrapActiveVaultMasterKey()
            try {
                val outcome = rotateVaultArtifacts(activeKey, stagedKey)
                lastRotationSkipped = outcome.skipped
                commitStagedKey()
                true
            } finally {
                stagedKey.fill(0)
                activeKey.fill(0)
            }
        } finally {
            handlingRotation = false
        }
    }

    /**
     * Re-wrap every object under [newKey] and re-seal every metadata blob.
     *
     * The operation is idempotent: objects that already unwrap with the new
     * key are skipped, and a blob that is already sealed with the new key is
     * left untouched. That makes an interrupted rotation safe to repeat.
     * Objects that were already unreadable are left untouched and counted.
     */
    private fun rotateVaultArtifacts(oldKey: ByteArray, newKey: ByteArray): RotationOutcome {
        var rotated = 0
        var skipped = 0

        val binDir = File(vaultDir, "bin")
        for (directory in listOf(objectsDir, binDir)) {
            val report = ZarcNative.nativeRotateMasterKey(
                oldKey,
                newKey,
                directory.absolutePath,
            )
            rotated += report[0]
            skipped += report[1]
        }

        resealBlobFile(File(vaultDir, "index.bin"), oldKey, newKey)
        resealBlobTree(File(vaultDir, "thumbs"), oldKey, newKey)
        // The recycle bin mixes objects and their sealed thumbnails.
        resealBlobTree(binDir, oldKey, newKey)
        return RotationOutcome(rotated = rotated, skipped = skipped)
    }

    /** Result of re-wrapping every artifact under a new master key. */
    private data class RotationOutcome(val rotated: Int, val skipped: Int)

    /** Re-seal one blob file under the new key, if it is still under the old one. */
    private fun resealBlobFile(file: File, oldKey: ByteArray, newKey: ByteArray): Boolean {
        if (!file.isFile) return false
        val sealed = runCatching { file.readBytes() }.getOrElse { return false }

        val alreadyNew = runCatching { ZarcNative.nativeOpenBlob(newKey, sealed) }
        if (alreadyNew.isSuccess) {
            alreadyNew.getOrNull()?.fill(0)
            return false
        }

        val plain = runCatching { ZarcNative.nativeOpenBlob(oldKey, sealed) }.getOrElse {
            // The blob was already unreadable before the rotation. Leave it
            // untouched; the index loader quarantines it and the media itself
            // is still intact.
            android.util.Log.e("VaultRepo", "a sealed blob could not be re-sealed: ${file.name}", it)
            return false
        }
        try {
            val resealed = ZarcNative.nativeSealBlob(newKey, plain)
            writeFileAtomically(file, resealed)
            return true
        } finally {
            plain.fill(0)
        }
    }

    /** Re-seal every `.blob` file in a directory tree. */
    private fun resealBlobTree(directory: File, oldKey: ByteArray, newKey: ByteArray) {
        val files = directory.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                resealBlobTree(file, oldKey, newKey)
            } else if (file.isFile && file.name.endsWith(".blob")) {
                resealBlobFile(file, oldKey, newKey)
            }
        }
    }

    /** Commit the staged key as the active key, checking the rename result. */
    private fun commitStagedKey() {
        val active = keystore.wrappedKeyFile
        if (!stagedKeyFile.renameTo(active)) {
            throw KeystoreFailure("the rotated vault master key could not be committed")
        }
        restrictToOwner(active, isDirectory = false)
    }

    /** Whether an interrupted private-area migration still has to be finished. */
    private fun hasPendingPrivateMigration(): Boolean =
        File(vaultDir, "private/migrating").exists() ||
            isLegacyPrivateGate()

    /** Whether the private gate still uses the deterministic legacy subkey. */
    private fun isLegacyPrivateGate(): Boolean {
        val gate = File(vaultDir, "private/gate.bin")
        if (!gate.isFile) return false
        val magic = ByteArray(4)
        val read = runCatching { gate.inputStream().use { it.read(magic) } }.getOrDefault(0)
        if (read < 4) return true
        return !magic.contentEquals(byteArrayOf('Z'.code.toByte(), 'P'.code.toByte(), 'G'.code.toByte(), '2'.code.toByte()))
    }

    /** Whether the vault directory already holds objects. */
    fun hasStoredObjects(): Boolean {
        return listOf(
            objectsDir,
            File(vaultDir, "bin"),
            File(vaultDir, "private/objects"),
            File(vaultDir, "private_bin"),
        ).any { directory ->
            directory.listFiles()?.any { it.isFile } == true
        }
    }

    /**
     * Remove decrypted files that were staged for an in-app consumer.
     *
     * Sharing exports (`cacheDir/exports`) are intentionally left alone here:
     * another application may still be reading one when Zarc is backgrounded.
     */
    fun purgeTransientExports() {
        exportDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /**
     * Replace [file] only after all of its bytes are on disk. A crash can then
     * leave the previous file or the complete new file behind, never a
     * truncated one.
     */
    private fun writeFileAtomically(file: File, bytes: ByteArray) {
        val parent = file.parentFile
            ?: throw IOException("the file has no parent directory")
        parent.mkdirs()
        val temporary = File.createTempFile("${file.name}.", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (!temporary.renameTo(file)) {
                throw IOException("the file could not be committed")
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

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
        writeFileAtomically(active, wrapped)
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
