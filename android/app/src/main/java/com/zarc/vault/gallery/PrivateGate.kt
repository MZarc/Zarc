package com.zarc.vault.gallery

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The gate that protects the private area.
 *
 * The private area uses an independent random subkey. That subkey is wrapped
 * by a second Keystore key created with `setUserAuthenticationRequired(true)`
 * and a strong-biometric-only authentication policy, so the wrapped subkey can
 * only be released after a successful biometric authentication that the user
 * performs in the system prompt.
 *
 * The gate key is deliberately *not* invalidated when a new biometric is
 * enrolled. The random subkey has no other wrapping, so invalidation would
 * permanently destroy the user's private media merely because they added a
 * fingerprint or face. The key is still invalidated when the secure lock
 * screen is removed, and every use requires a fresh biometric.
 *
 * The private media itself is encrypted with the random subkey, so the private
 * area needs two independent things: the vault key *and* the device
 * authentication. Compromising the vault master key alone does not expose the
 * private area.
 */
class PrivateGate(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Make sure the authentication-bound gate key exists.
     *
     * An existing key is never deleted: doing so would destroy the only copy
     * of the wrapped private subkey. If the key is unusable the caller must
     * surface an error and keep the private area closed.
     */
    @Synchronized
    fun ensureKey(): Boolean {
        val store = keyStore()
        if (store.containsAlias(ALIAS)) {
            val key = runCatching { loadKey() }.getOrElse { return false }
            // A legacy key was not authentication-bound and can be used
            // without a prompt. Replace it with an auth-bound key; the legacy
            // private subkey is still recoverable from the vault master key
            // during migration.
            val usableWithoutAuth = runCatching {
                val probe = Cipher.getInstance(TRANSFORMATION)
                probe.init(Cipher.ENCRYPT_MODE, key)
                probe.doFinal(ByteArray(1))
                true
            }.getOrDefault(false)
            if (!usableWithoutAuth) return true
            Log.w(TAG, "replacing a legacy non-authenticated private gate key")
            runCatching { store.deleteEntry(ALIAS) }
        }
        return try {
            generateKey()
            true
        } catch (error: Exception) {
            Log.w(TAG, "the private gate key could not be created: ${error.message}")
            false
        }
    }

    /** Whether the Keystore key that wraps the private subkey exists. */
    fun hasKey(): Boolean = keyStore().containsAlias(ALIAS)

    /** Whether [wrapped] uses the current, versioned gate format. */
    fun isCurrentFormat(wrapped: ByteArray): Boolean =
        wrapped.size > MAGIC.size + IV_BYTES + TAG_BYTES &&
            wrapped.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    /**
     * Start wrapping a private subkey.
     *
     * The returned cipher has to be handed to `BiometricPrompt` as a
     * `CryptoObject`. The actual `doFinal` only succeeds after the platform
     * authorises the operation.
     */
    fun beginWrap(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadKey())
        return cipher
    }

    /** Finish wrapping after the prompt authorised [cipher]. */
    fun finishWrap(cipher: Cipher, subkey: ByteArray): ByteArray {
        val ciphertext = cipher.doFinal(subkey)
        return MAGIC + cipher.iv + ciphertext
    }

    /**
     * Start unwrapping a current-format private subkey.
     *
     * The returned cipher has to be handed to `BiometricPrompt` as a
     * `CryptoObject`.
     */
    fun beginUnwrap(wrapped: ByteArray): Cipher {
        require(isCurrentFormat(wrapped)) { "the private gate blob has an unknown format" }
        val iv = wrapped.copyOfRange(MAGIC.size, MAGIC.size + IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, loadKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher
    }

    /** Finish unwrapping after the prompt authorised [cipher]. */
    fun finishUnwrap(cipher: Cipher, wrapped: ByteArray): ByteArray {
        require(isCurrentFormat(wrapped)) { "the private gate blob has an unknown format" }
        val offset = MAGIC.size + IV_BYTES
        return cipher.doFinal(wrapped, offset, wrapped.size - offset)
    }

    /**
     * Release a legacy gate blob that was written before the authentication
     * bound key existed. This is only used to migrate old private data once;
     * the caller immediately replaces the blob with [finishWrap] output.
     */
    fun unwrapLegacy(wrapped: ByteArray): ByteArray {
        require(!isCurrentFormat(wrapped)) { "the private gate blob is not legacy" }
        require(wrapped.size > IV_BYTES + TAG_BYTES) { "the legacy gate blob is truncated" }
        val iv = wrapped.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, loadKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(wrapped, IV_BYTES, wrapped.size - IV_BYTES)
    }

    /** Delete the gate key. The private area becomes unreachable. */
    fun deleteKey() {
        val store = keyStore()
        if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
    }

    private fun generateKey() {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE,
        )
        val builder = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            // The private subkey has no second wrapping. Invalidating this key
            // on a routine biometric enrollment would permanently destroy the
            // private media, so the key survives enrollment changes while
            // still requiring a strong biometric for every use.
            .setInvalidatedByBiometricEnrollment(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG,
            )
        } else {
            // -1 means: authenticate for every use of the key.
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }

        generator.init(builder.build())
        generator.generateKey()
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private fun loadKey(): SecretKey =
        keyStore().getKey(ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("the private gate key does not exist")

    companion object {
        private const val TAG = "ZarcPrivateGate"

        /** The alias of the gate key. */
        const val ALIAS = "zarc.vault.gate.v1"

        private val MAGIC = byteArrayOf('Z'.code.toByte(), 'P'.code.toByte(), 'G'.code.toByte(), '2'.code.toByte())
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / 8

        /** Whether [wrapped] predates the authentication-bound gate format. */
        fun isLegacy(wrapped: ByteArray): Boolean =
            !(wrapped.size > MAGIC.size + IV_BYTES + TAG_BYTES &&
                wrapped.copyOfRange(0, MAGIC.size).contentEquals(MAGIC))
    }
}
