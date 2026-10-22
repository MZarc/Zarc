package com.zarc.vault.keystore

import android.os.Build
import java.security.KeyStore

/**
 * What the platform actually reports about the key that protects the vault
 * master key.
 *
 * The values come from the platform (`KeyInfo.getSecurityLevel()` on API 31 and
 * newer, `KeyInfo.isInsideSecureHardware()` before that). When the platform
 * does not expose that information - some AndroidKeyStore implementations do
 * not offer a `KeyFactory` for AES keys - the report says `UNAVAILABLE`
 * instead of guessing. Zarc never claims a protection level that the platform
 * did not report.
 */
data class KeystoreReport(
    /** The alias of the key in the Android Keystore. */
    val alias: String,
    /** The provider that holds the key. */
    val provider: String,
    /** The security level as reported by the platform. */
    val securityLevel: String,
    /** Whether the platform reports the key as living inside secure hardware. */
    val hardwareBacked: Boolean,
    /** Whether the key was generated with (and accepted by) StrongBox. */
    val strongBoxBacked: Boolean,
    /** The key size in bits. */
    val keySizeBits: Int,
    /** The block modes the key is used with. */
    val blockModes: String,
    /** The encryption paddings the key is used with. */
    val paddings: String,
    /** Whether the platform exposed `KeyInfo` for this key. */
    val keyInfoAvailable: Boolean,
    /** Free form notes, for example why StrongBox was not used. */
    val notes: String,
) {
    /** A single line summary for the user interface. */
    fun summary(): String = buildString {
        append("alias=$alias")
        append(" provider=$provider")
        append(" level=$securityLevel")
        append(" hardwareBacked=$hardwareBacked")
        append(" strongBox=$strongBoxBacked")
        append(" keySize=$keySizeBits")
        append(" keyInfoAvailable=$keyInfoAvailable")
    }
}

/**
 * The result of ensuring that the Keystore key exists.
 *
 * [created] tells the caller whether the key had to be created. A key that
 * was just created cannot unwrap a vault master key that was wrapped with an
 * older key, which is how a lost Keystore key is detected.
 */
data class KeystoreState(
    /** What the platform reports about the key. */
    val report: KeystoreReport,
    /** Whether the key was created by this call. */
    val created: Boolean,
)

/** Helpers around `KeyInfo` that differ between Android versions. */
internal object SecurityLevels {

    /** The reported level when the platform does not expose `KeyInfo`. */
    const val UNAVAILABLE = "UNAVAILABLE"

    /** The reported level when the platform returns an unknown value. */
    const val UNKNOWN = "UNKNOWN"

    /** The platform reported security level, or a documented fallback. */
    fun of(keyInfo: android.security.keystore.KeyInfo): String = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            when (keyInfo.securityLevel) {
                android.security.keystore.KeyProperties.SECURITY_LEVEL_STRONGBOX -> "STRONGBOX"
                android.security.keystore.KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ->
                    "TRUSTED_ENVIRONMENT"
                android.security.keystore.KeyProperties.SECURITY_LEVEL_SOFTWARE -> "SOFTWARE"
                else -> UNKNOWN
            }
        // Before API 31 the platform does not expose a level, only a boolean.
        else -> if (isHardwareBacked(keyInfo)) {
            "HARDWARE_BACKED_PRE_31"
        } else {
            "SOFTWARE"
        }
    }

    /** Whether the platform reports secure hardware backing. */
    fun isHardwareBacked(keyInfo: android.security.keystore.KeyInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            keyInfo.securityLevel != android.security.keystore.KeyProperties.SECURITY_LEVEL_SOFTWARE
        } else {
            @Suppress("DEPRECATION")
            keyInfo.isInsideSecureHardware
        }

    /** Whether the key lives in a StrongBox. */
    fun isStrongBox(keyInfo: android.security.keystore.KeyInfo): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            keyInfo.securityLevel == android.security.keystore.KeyProperties.SECURITY_LEVEL_STRONGBOX

    /** Load the Android Keystore, or fail loudly. */
    fun androidKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    const val ANDROID_KEY_STORE = "AndroidKeyStore"
}
