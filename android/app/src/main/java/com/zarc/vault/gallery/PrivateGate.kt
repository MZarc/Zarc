package com.zarc.vault.gallery

import android.content.Context
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
 * The private area uses a subkey that is derived from the vault master key
 * and then *wrapped* by a second Keystore key. That second key is created with
 * `setUserAuthenticationRequired(true)`, so the wrapped subkey can only be
 * unwrapped after a successful biometric (or device credential) authentication
 * that the user performs in the system prompt.
 *
 * The private media itself is encrypted with the subkey, so the private area
 * needs two independent things: the vault key *and* the device authentication.
 */
class PrivateGate(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Make sure the gate key exists.
     *
     * @return false when the platform cannot provide a key that requires user
     *   authentication.
     */
    fun ensureKey(): Boolean {
        val store = keyStore()
        if (store.containsAlias(ALIAS)) return true
        return try {
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
                // The key may only be used after a user authentication.
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(false)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(-1)
            }
            generator.init(builder.build())
            generator.generateKey()
            true
        } catch (error: Exception) {
            Log.w(TAG, "the private gate key could not be created: ${error.message}")
            false
        }
    }

    /** Wrap the private subkey with the gate key. */
    fun wrap(subkey: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadKey())
        val ciphertext = cipher.doFinal(subkey)
        return cipher.iv + ciphertext
    }

    /**
     * The cipher that unlocks the gate.
     *
     * The cipher has to be handed to the biometric prompt as a
     * `CryptoObject`; the platform then authorises it, and
     * [decryptWith] finishes the operation.
     */
    fun cipher(wrapped: ByteArray): Cipher {
        val iv = wrapped.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, loadKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher
    }

    /** Finish the unwrapping with the authorised cipher. */
    fun decryptWith(cipher: Cipher, wrapped: ByteArray): ByteArray {
        val ciphertext = wrapped.copyOfRange(IV_BYTES, wrapped.size)
        return cipher.doFinal(ciphertext)
    }

    /** Delete the gate key. The private area becomes unreachable. */
    fun deleteKey() {
        val store = keyStore()
        if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
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

        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
