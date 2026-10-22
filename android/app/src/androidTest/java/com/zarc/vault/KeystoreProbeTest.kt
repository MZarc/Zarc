package com.zarc.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyFactory
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A probe that records exactly which Android Keystore APIs this platform
 * supports. It documents the behaviour of the emulator instead of assuming it.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreProbeTest {

    @Test
    fun probe() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val results = mutableListOf<String>()
        val alias = "zarc.probe.${System.currentTimeMillis()}"
        var key: SecretKey? = null

        try {
            val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
            results += "KeyGenerator(AES, AndroidKeyStore): OK"
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            key = generator.generateKey()
            results += "generateKey: OK algorithm=${key.algorithm}"
        } catch (error: Exception) {
            results += "KeyGenerator/generateKey: FAIL $error"
        }

        if (key != null) {
            val secretKey = key
            try {
                val factory = KeyFactory.getInstance(secretKey.algorithm, "AndroidKeyStore")
                val info = factory.getKeySpec(secretKey, KeyInfo::class.java)
                results += "KeyFactory(AES, AndroidKeyStore): OK level=${info.securityLevel} hw=${info.isInsideSecureHardware}"
            } catch (error: Exception) {
                results += "KeyFactory(AES, AndroidKeyStore): FAIL $error"
            }
        }

        try {
            SecretKeyFactory.getInstance("AES", "AndroidKeyStore")
            results += "SecretKeyFactory(AES, AndroidKeyStore): OK"
        } catch (error: Exception) {
            results += "SecretKeyFactory(AES, AndroidKeyStore): FAIL $error"
        }

        try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val entry = store.getEntry(alias, null)
            results += "KeyStore.getEntry: OK ${entry.javaClass.name}"
        } catch (error: Exception) {
            results += "KeyStore.getEntry: FAIL $error"
        }

        try {
            val factory = KeyFactory.getInstance("RSA", "AndroidKeyStore")
            results += "KeyFactory(RSA, AndroidKeyStore): OK ${factory.javaClass.name}"
        } catch (error: Exception) {
            results += "KeyFactory(RSA, AndroidKeyStore): FAIL $error"
        }

        // The provider that matters for AES keys on Android 12 and newer: the
        // KeyStore2 provider registers a `SecretKeyFactory` for AES, and that
        // factory can return the `KeyInfo` that carries the security level.
        try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val secretKey = store.getKey(alias, null) as SecretKey
            val skf = SecretKeyFactory.getInstance("AES", "AndroidKeyStore")
            val info = skf.getKeySpec(secretKey, KeyInfo::class.java) as KeyInfo
            results += "SecretKeyFactory.getKeySpec(KeyInfo): OK level=${info.securityLevel} " +
                "hw=${info.isInsideSecureHardware} size=${info.keySize}"
        } catch (error: Exception) {
            results += "SecretKeyFactory.getKeySpec(KeyInfo): FAIL $error"
        }

        // Report what the platform says about the key that Zarc uses. This
        // is the evidence for the "hardware backed?" question.
        val keystore = com.zarc.vault.keystore.KeystoreKeyManager(context)
        val report = keystore.ensureKey().report
        results += "KeystoreReport: ${report.summary()} blockModes=${report.blockModes} paddings=${report.paddings}"
        results += "strongBoxAvailable=${keystore.hasStrongBox()}"

        Log.i(TAG, results.joinToString(" | "))
        println("PROBE:\n" + results.joinToString("\n"))
    }

    companion object {
        private const val TAG = "ZarcProbe"
    }
}
