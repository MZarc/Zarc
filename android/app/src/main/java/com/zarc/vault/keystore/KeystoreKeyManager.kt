package com.zarc.vault.keystore

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Android platform adapter for the key hierarchy.
 *
 * It owns a single, non-exportable AES-256-GCM key inside the Android
 * Keystore. That key wraps (and unwraps) the 256-bit vault master key. The
 * unwrapped master key is only ever held in memory, is handed to the Rust
 * core for the duration of a single operation and is zeroized afterwards.
 *
 * The class never writes an unwrapped key to storage and never logs key
 * material.
 */
class KeystoreKeyManager(context: Context) {

    private val appContext = context.applicationContext

    /** The alias of the key that protects the vault master key. */
    val alias: String = KEY_ALIAS

    /**
     * The file that holds the wrapped vault master key.
     *
     * It lives in `noBackupFilesDir`, which the platform excludes from every
     * backup and device transfer mechanism, on top of the backup rules in the
     * manifest.
     */
    val wrappedKeyFile: File = File(appContext.noBackupFilesDir, WRAPPED_KEY_PATH)

    /** Records how the Keystore key was generated (see `strongBoxGenerationMarker`). */
    private val markerFile: File = File(appContext.noBackupFilesDir, GENERATION_MARKER_PATH)

    /**
     * Make sure the Keystore key exists and return what the platform reports
     * about it.
     *
     * StrongBox is requested when the device advertises support for it. If the
     * platform refuses (for example because the StrongBox is out of service),
     * the key is generated without it and the report says so - Zarc never
     * claims a protection level that the platform did not report.
     */
    @Synchronized
    fun ensureKey(): KeystoreState {
        val store = SecurityLevels.androidKeyStore()
        val created = !store.containsAlias(alias)
        if (created) {
            generateKey()
        }
        return KeystoreState(report(), created)
    }

    /** Report what the platform says about the key, without creating it. */
    fun report(): KeystoreReport {
        val store = SecurityLevels.androidKeyStore()
        val key = store.getKey(alias, null) as? SecretKey
            ?: return KeystoreReport(
                alias = alias,
                provider = SecurityLevels.ANDROID_KEY_STORE,
                securityLevel = "MISSING",
                hardwareBacked = false,
                strongBoxBacked = false,
                keySizeBits = 0,
                blockModes = "-",
                paddings = "-",
                keyInfoAvailable = false,
                notes = "the key does not exist in the Android Keystore",
            )

        // `KeyInfo` is the only platform API that exposes the protection level
        // of a key. Which factory returns it depends on the platform:
        //
        //  * the KeyStore2 provider (Android 12 and newer) registers a
        //    `KeyFactory` for RSA and EC only, and returns the `KeyInfo` of an
        //    AES key through its `SecretKeyFactory`,
        //  * the legacy provider registers a `KeyFactory` for AES.
        //
        // Both paths are tried, so the protection level is reported whenever
        // the platform exposes it at all. If neither works, the level is
        // reported as unavailable instead of being guessed.
        val keyInfo = keyInfoOf(key)

        val strongBoxRequested = strongBoxGenerationMarker()

        return if (keyInfo != null) {
            KeystoreReport(
                alias = alias,
                provider = SecurityLevels.ANDROID_KEY_STORE,
                securityLevel = SecurityLevels.of(keyInfo),
                hardwareBacked = SecurityLevels.isHardwareBacked(keyInfo),
                strongBoxBacked = SecurityLevels.isStrongBox(keyInfo),
                keySizeBits = keyInfo.keySize,
                blockModes = KeyProperties.BLOCK_MODE_GCM,
                paddings = KeyProperties.ENCRYPTION_PADDING_NONE,
                keyInfoAvailable = true,
                notes = "",
            )
        } else {
            KeystoreReport(
                alias = alias,
                provider = SecurityLevels.ANDROID_KEY_STORE,
                securityLevel = SecurityLevels.UNAVAILABLE,
                // Without `KeyInfo` there is no platform evidence for hardware
                // backing, so Zarc does not claim it.
                hardwareBacked = false,
                strongBoxBacked = strongBoxRequested,
                keySizeBits = 256,
                blockModes = KeyProperties.BLOCK_MODE_GCM,
                paddings = KeyProperties.ENCRYPTION_PADDING_NONE,
                keyInfoAvailable = false,
                notes = "the platform does not expose KeyInfo for this key; " +
                    "strongBox reflects how the key was generated, " +
                    "not a level reported by the platform",
            )
        }
    }

    /** Delete the Keystore key. The wrapped master key becomes useless. */
    @Synchronized
    fun deleteKey() {
        val store = SecurityLevels.androidKeyStore()
        if (store.containsAlias(alias)) {
            store.deleteEntry(alias)
        }
        if (wrappedKeyFile.exists()) {
            wrappedKeyFile.delete()
        }
        if (markerFile.exists()) {
            markerFile.delete()
        }
    }

    /**
     * Wrap the vault master key with the Keystore key.
     *
     * The result is `iv || ciphertext`, ready to be stored in app-private
     * storage. The GCM IV is generated by the platform cipher.
     */
    @Synchronized
    fun wrapVaultMasterKey(vaultMasterKey: ByteArray): ByteArray {
        require(vaultMasterKey.size == VAULT_MASTER_KEY_BYTES) {
            "the vault master key must be $VAULT_MASTER_KEY_BYTES bytes"
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadKey())
        val ciphertext = cipher.doFinal(vaultMasterKey)
        return cipher.iv + ciphertext
    }

    /**
     * Unwrap the vault master key.
     *
     * @throws KeystoreFailure if the key is missing, invalidated or the
     *   wrapped material is corrupt. The caller must treat every failure as
     *   fatal for the vault: there is no fallback to an unprotected key.
     */
    @Synchronized
    fun unwrapVaultMasterKey(wrapped: ByteArray): ByteArray {
        if (wrapped.size <= GCM_IV_BYTES) {
            throw KeystoreFailure("the wrapped vault master key is truncated")
        }
        val iv = wrapped.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = wrapped.copyOfRange(GCM_IV_BYTES, wrapped.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.DECRYPT_MODE, loadKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        } catch (error: KeyPermanentlyInvalidatedException) {
            throw KeystoreFailure(
                "the Keystore key was permanently invalidated; the vault cannot be opened",
                error,
            )
        } catch (error: UnrecoverableKeyException) {
            throw KeystoreFailure("the Keystore key cannot be recovered", error)
        } catch (error: java.security.InvalidKeyException) {
            throw KeystoreFailure("the Keystore key is unusable: ${error.message}", error)
        }
        return try {
            cipher.doFinal(ciphertext)
        } catch (error: AEADBadTagException) {
            throw KeystoreFailure(
                "the wrapped vault master key failed authentication; it has been modified",
                error,
            )
        }
    }

    /**
     * Obtain the `KeyInfo` of a key, whichever factory the platform provides.
     *
     * Returns `null` when the platform does not expose the information.
     */
    private fun keyInfoOf(key: SecretKey): KeyInfo? {
        // KeyStore2 (Android 12+): the SecretKeyFactory of the AndroidKeyStore
        // returns the KeyInfo of an AES key.
        runCatching {
            val factory = javax.crypto.SecretKeyFactory.getInstance(
                key.algorithm,
                SecurityLevels.ANDROID_KEY_STORE,
            )
            return factory.getKeySpec(key, KeyInfo::class.java) as? KeyInfo
        }
        // Legacy provider: a KeyFactory for the key algorithm.
        runCatching {
            val factory = KeyFactory.getInstance(key.algorithm, SecurityLevels.ANDROID_KEY_STORE)
            return factory.getKeySpec(key, KeyInfo::class.java)
        }
        return null
    }

    /**
     * Replace the Keystore key with a new one.
     *
     * The old key is deleted and a new key is generated under the same alias.
     * The caller has to re-wrap the vault master key afterwards; if it does
     * not, the recovery envelope (if one exists) is the only way back.
     */
    @Synchronized
    fun regenerateKey() {
        val store = SecurityLevels.androidKeyStore()
        if (store.containsAlias(alias)) {
            store.deleteEntry(alias)
        }
        generateKey()
    }

    /** Load the secret key from the Keystore. */
    private fun loadKey(): SecretKey {
        val store = SecurityLevels.androidKeyStore()
        val key = store.getKey(alias, null) as? SecretKey
            ?: throw KeystoreFailure("the Keystore key '$alias' does not exist")
        return key
    }

    /** Generate the AES-256-GCM key, preferring StrongBox when it is available. */
    private fun generateKey() {
        val strongBoxAvailable = hasStrongBox()
        if (strongBoxAvailable) {
            try {
                generateKey(strongBox = true)
                setStrongBoxGenerationMarker(true)
                return
            } catch (error: StrongBoxUnavailableException) {
                Log.w(TAG, "StrongBox is advertised but unavailable, falling back: ${error.message}")
            }
        }
        generateKey(strongBox = false)
        setStrongBoxGenerationMarker(false)
    }

    /**
     * Whether the key was generated with StrongBox backing.
     *
     * The platform only accepts `setIsStrongBoxBacked(true)` when a StrongBox
     * is really present, so a successful generation is evidence that the key
     * lives in the StrongBox. The marker only records *how* the key was
     * created; it never claims a level the platform did not report.
     */
    private fun strongBoxGenerationMarker(): Boolean =
        markerFile.readText().trim() == "strongbox"

    private fun setStrongBoxGenerationMarker(strongBox: Boolean) {
        markerFile.parentFile?.mkdirs()
        markerFile.writeText(if (strongBox) "strongbox" else "default")
        markerFile.setReadable(false, false)
        markerFile.setReadable(true, true)
        markerFile.setWritable(false, false)
        markerFile.setWritable(true, true)
    }

    private fun generateKey(strongBox: Boolean) {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, SecurityLevels.ANDROID_KEY_STORE)
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // The platform must generate the GCM IV for every operation.
            .setRandomizedEncryptionRequired(true)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        generator.init(builder.build())
        generator.generateKey()
    }

    /** Whether the device advertises a StrongBox Keymaster implementation. */
    fun hasStrongBox(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    companion object {
        private const val TAG = "ZarcKeystore"

        /** The alias of the key that protects the vault master key. */
        const val KEY_ALIAS = "zarc.vault.kek.v1"

        /** Where the wrapped vault master key is stored, relative to filesDir. */
        const val WRAPPED_KEY_PATH = "keys/vmk.wrapped"

        /** Where the key generation marker is stored, relative to filesDir. */
        const val GENERATION_MARKER_PATH = "keys/kek.generation"

        /** The size of the vault master key in bytes. */
        const val VAULT_MASTER_KEY_BYTES = 32

        /** The size of the GCM IV in bytes. */
        const val GCM_IV_BYTES = 12

        /** The size of the GCM authentication tag in bits. */
        const val GCM_TAG_BITS = 128

        /** The cipher used to wrap the vault master key. */
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/**
 * A failure of the platform key store.
 *
 * Every failure is fatal for the vault: Zarc never falls back to storing the
 * vault master key unprotected.
 */
class KeystoreFailure(message: String, cause: Throwable? = null) : Exception(message, cause)
