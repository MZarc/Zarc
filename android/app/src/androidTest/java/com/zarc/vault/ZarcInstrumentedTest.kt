package com.zarc.vault

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zarc.vault.core.VaultRepository
import com.zarc.vault.core.ZarcException
import com.zarc.vault.keystore.KeystoreFailure
import com.zarc.vault.keystore.KeystoreKeyManager
import com.zarc.vault.keystore.KeystoreReport
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device tests for the Android specific half of the vault.
 *
 * These run against the real Android Keystore on a real (or emulated)
 * device. A desktop unit test cannot prove any of this.
 */
@RunWith(AndroidJUnit4::class)
class ZarcInstrumentedTest {

    private lateinit var context: Context
    private lateinit var repository: VaultRepository
    private lateinit var keystore: KeystoreKeyManager

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        repository = VaultRepository(context)
        keystore = KeystoreKeyManager(context)
        repository.ensureDirectories()
    }

    /**
     * Every test starts from a clean vault: the Keystore key, the wrapped
     * master key and the stored objects are removed.
     */
    @After
    fun tearDown() {
        keystore.deleteKey()
        repository.objectsDir.listFiles()?.forEach { it.delete() }
        repository.exportDir.listFiles()?.forEach { it.delete() }
        repository.workDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun theKeystoreKeyIsCreatedAndThePlatformReportsItsProtection() {
        val report: KeystoreReport = keystore.ensureKey().report

        assertEquals(KeystoreKeyManager.KEY_ALIAS, report.alias)
        assertEquals("AndroidKeyStore", report.provider)
        // The platform must report *something*; Zarc never guesses.
        assertTrue(
            "unexpected security level: ${report.securityLevel}",
            report.securityLevel in setOf(
                "STRONGBOX",
                "TRUSTED_ENVIRONMENT",
                "SOFTWARE",
                "HARDWARE_BACKED_PRE_31",
                "UNKNOWN",
                "UNAVAILABLE",
            ),
        )
        // When the platform exposes KeyInfo, the StrongBox flag must agree
        // with the reported level. When it does not, no hardware backing may
        // be claimed.
        if (report.keyInfoAvailable) {
            assertEquals(
                report.strongBoxBacked,
                report.securityLevel == "STRONGBOX",
            )
        } else {
            assertFalse(
                "without KeyInfo no hardware backing may be claimed",
                report.hardwareBacked,
            )
            assertEquals("UNAVAILABLE", report.securityLevel)
        }
    }

    @Test
    fun theVaultMasterKeySurvivesAProcessRestart() {
        val first = repository.vaultMasterKey()
        assertEquals(KeystoreKeyManager.VAULT_MASTER_KEY_BYTES, first.size)

        // Simulate a restart: a brand new repository and key manager instance
        // must unwrap exactly the same master key.
        val restarted = VaultRepository(context)
        val second = restarted.vaultMasterKey()
        assertTrue(first.contentEquals(second))

        first.fill(0)
        second.fill(0)
    }

    @Test
    fun aCorruptedWrappedVaultMasterKeyIsRejected() {
        repository.vaultMasterKey()
        val wrapped = keystore.wrappedKeyFile
        assertTrue("the wrapped key must exist", wrapped.exists())

        val original = wrapped.readBytes()
        val corrupted = original.copyOf()
        corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1] + 1).toByte()
        wrapped.writeBytes(corrupted)

        try {
            val failure = runCatching { repository.vaultMasterKey() }.exceptionOrNull()
            assertTrue(
                "a corrupted wrapped key must be rejected, got $failure",
                failure is KeystoreFailure,
            )
        } finally {
            wrapped.writeBytes(original)
        }
    }

    @Test
    fun aMissingKeystoreKeyFailsClosed() {
        repository.vaultMasterKey()

        // Remove only the Keystore key; the wrapped vault master key stays.
        // The vault must fail closed instead of silently starting over.
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.deleteEntry(KeystoreKeyManager.KEY_ALIAS)
        assertEquals("MISSING", keystore.report().securityLevel)

        val failure = runCatching { repository.vaultMasterKey() }.exceptionOrNull()
        assertTrue(
            "a missing key must fail closed, got $failure",
            failure is KeystoreFailure,
        )
    }

    @Test
    fun losingTheKeystoreKeyMakesTheVaultUnreachable() {
        val fixture = File(repository.workDir, "key-loss.bin")
        fixture.writeBytes(ByteArray(1024) { 5 })
        val objectId = repository.encrypt(fixture)

        // Losing the Keystore key destroys the device bound copy of the
        // master key. The vault must not silently start over with a new key:
        // it reports that only the recovery passphrase can open it.
        keystore.deleteKey()
        val failure = runCatching { repository.vaultMasterKey() }.exceptionOrNull()
        assertTrue(
            "a lost Keystore key must fail closed, got $failure",
            failure is KeystoreFailure,
        )
        assertTrue(
            "the error must mention the recovery passphrase: $failure",
            failure?.message?.contains("recovery passphrase") == true,
        )

        // The objects of the old vault are not readable without the key.
        val decryptFailure = runCatching {
            repository.decrypt(objectId, "unreachable.bin")
        }.exceptionOrNull()
        assertTrue(
            "objects of a lost vault must not decrypt, got $decryptFailure",
            decryptFailure is Exception,
        )
    }

    @Test
    fun encryptAndDecryptRoundTripOnTheDevice() {
        val fixture = File(repository.workDir, "instrumented-fixture.bin")
        val payload = ByteArray(200_000) { index -> (index * 7 % 253).toByte() }
        fixture.writeBytes(payload)

        val before = repository.sha256(fixture)
        val objectId = repository.encrypt(fixture)
        assertEquals(32, objectId.length)

        val verification = repository.verify(objectId)
        assertTrue(verification.contains("plaintext=${payload.size}"))

        val restored = repository.decrypt(objectId, "restored.bin")
        assertEquals(before, repository.sha256(restored))
        assertTrue(payload.contentEquals(restored.readBytes()))
    }

    @Test
    fun theVaultObjectsUseOpaqueNamesAndLiveInAppPrivateStorage() {
        val fixture = File(repository.workDir, "naming.bin")
        fixture.writeBytes(ByteArray(1024) { 7 })

        val objectId = repository.encrypt(fixture)
        val stored = File(repository.objectsDir, objectId)
        assertTrue("the object must exist", stored.exists())
        assertEquals(objectId, stored.name)
        assertFalse("the object must not carry an extension", stored.name.contains('.'))
        assertFalse(stored.name.endsWith(".jpg"))

        // The directory must be inside the app private data directory.
        val privateRoot = context.filesDir.parentFile ?: context.filesDir
        assertTrue(
            "the vault must live inside the app private directory",
            stored.canonicalPath.startsWith(privateRoot.canonicalPath),
        )
    }

    @Test
    fun theStoredObjectDoesNotContainThePlaintext() {
        val fixture = File(repository.workDir, "leak-check.bin")
        val payload = ByteArray(4096) { index -> (index % 251).toByte() }
        fixture.writeBytes(payload)

        repository.encrypt(fixture)

        for (candidate in repository.objectsDir.listFiles().orEmpty()) {
            val haystack = candidate.readBytes()
            assertFalse(
                "the plaintext was found in ${candidate.name}",
                haystack.containsBytes(payload),
            )
        }
    }

    @Test
    fun theUnwrappedVaultMasterKeyIsNeverWrittenToDisk() {
        val key = repository.vaultMasterKey()
        try {
            for (root in listOf(context.filesDir, context.noBackupFilesDir, context.cacheDir)) {
                for (candidate in root.walkTopDown().filter { it.isFile }) {
                    val haystack = candidate.readBytes()
                    assertFalse(
                        "the vault master key was found in ${candidate.absolutePath}",
                        haystack.containsBytes(key),
                    )
                }
            }
        } finally {
            key.fill(0)
        }
    }

    @Test
    fun theVaultIsExcludedFromBackups() {
        // The manifest disables backups outright (`android:allowBackup="false"`
        // plus the data extraction rules); that is verified by inspecting the
        // merged manifest during the build, because the platform does not
        // expose the flag through `PackageManager` any more.
        //
        // What the platform *does* guarantee at runtime - and what this test
        // checks - is that the vault objects and the wrapped vault master key
        // live in `noBackupFilesDir`, which the platform excludes from every
        // backup and device transfer mechanism.
        assertTrue(
            repository.objectsDir.canonicalPath.startsWith(
                context.noBackupFilesDir.canonicalPath,
            ),
        )
        assertTrue(
            keystore.wrappedKeyFile.canonicalPath.startsWith(
                context.noBackupFilesDir.canonicalPath,
            ),
        )
        assertTrue(
            repository.exportDir.canonicalPath.startsWith(
                context.noBackupFilesDir.canonicalPath,
            ),
        )
    }

    @Test
    fun twoEncryptionsOfTheSameInputProduceDifferentObjects() {
        val fixture = File(repository.workDir, "same.bin")
        fixture.writeBytes(ByteArray(4096) { 3 })

        val first = repository.encrypt(fixture)
        val second = repository.encrypt(fixture)
        assertNotEquals(first, second)

        val firstBytes = File(repository.objectsDir, first).readBytes()
        val secondBytes = File(repository.objectsDir, second).readBytes()
        assertFalse("fresh keys must produce different ciphertext", firstBytes.contentEquals(secondBytes))
    }

    @Test
    fun aWrongKeyCannotOpenAnObject() {
        val fixture = File(repository.workDir, "wrong-key.bin")
        fixture.writeBytes(ByteArray(2048) { 9 })
        val objectId = repository.encrypt(fixture)

        val wrongKey = ByteArray(KeystoreKeyManager.VAULT_MASTER_KEY_BYTES) { 0x5a }
        val failure = runCatching {
            com.zarc.vault.core.ZarcNative.nativeDecryptFile(
                wrongKey,
                objectId,
                repository.objectsDir.absolutePath,
                repository.exportDir.absolutePath,
                "stolen.bin",
            )
        }.exceptionOrNull()
        assertTrue("a wrong key must be rejected, got $failure", failure is ZarcException)
        assertFalse(File(repository.exportDir, "stolen.bin").exists())
    }

    @Test
    fun theCryptoSelfTestPassesOnTheDevice() {
        val report = repository.selfTest()
        assertTrue("the self test must pass:\n$report", report.contains("RESULT: PASS"))
        assertTrue(report.contains("libsodium 1.0.22"))
    }
}

/** A byte wise substring search. */
private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    outer@ for (start in 0..(size - needle.size)) {
        for (offset in needle.indices) {
            if (this[start + offset] != needle[offset]) continue@outer
        }
        return true
    }
    return false
}