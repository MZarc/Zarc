package com.zarc.vault

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zarc.vault.core.VaultRepository
import com.zarc.vault.keystore.KeystoreFailure
import com.zarc.vault.keystore.KeystoreKeyManager
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device tests for the recovery envelope and the key rotation.
 *
 * These exercise the real Android Keystore: the recovery envelope is the only
 * way back after the Keystore key is destroyed.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryInstrumentedTest {

    private lateinit var context: Context
    private lateinit var repository: VaultRepository
    private lateinit var keystore: KeystoreKeyManager

    private val passphrase = "correct horse battery staple".toCharArray()
    private val wrongPassphrase = "wrong horse battery staple".toCharArray()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        repository = VaultRepository(context)
        keystore = KeystoreKeyManager(context)
        repository.ensureDirectories()
    }

    @After
    fun tearDown() {
        keystore.deleteKey()
        repository.objectsDir.listFiles()?.forEach { it.delete() }
        repository.exportDir.listFiles()?.forEach { it.delete() }
        repository.workDir.listFiles()?.forEach { it.delete() }
        repository.recoveryEnvelopeFile.delete()
        repository.recoveryWitnessFile.delete()
        repository.stagedKeyFile.delete()
    }

    private fun storeFixture(name: String, size: Int = 4096): File {
        val fixture = File(repository.workDir, name)
        fixture.writeBytes(ByteArray(size) { index -> (index % 251).toByte() })
        return fixture
    }

    @Test
    fun theRecoveryEnvelopeCanUnlockTheVaultAfterTheKeystoreKeyIsLost() {
        val fixture = storeFixture("recovery.bin")
        val objectId = repository.encrypt(fixture)
        val digest = repository.sha256(fixture)

        assertTrue(repository.enableRecovery(passphrase))
        assertTrue(repository.hasRecovery())

        // The device is lost: the Keystore key and the wrapped master key are
        // gone, only the recovery envelope remains.
        keystore.deleteKey()
        // The Keystore bound copy of the master key is gone: the recovery
        // envelope is the only way back into the vault.
        assertFalse(keystore.wrappedKeyFile.exists())

        // The passphrase recovers the master key ...
        val recovered = repository.recoverVaultMasterKey(passphrase)
        try {
            assertEquals(KeystoreKeyManager.VAULT_MASTER_KEY_BYTES, recovered.size)
            // ... and the objects open with it.
            val restored = repository.decryptWithKey(recovered, objectId, "recovered.bin")
            assertEquals(digest, repository.sha256(restored))

            // The Keystore binding can be restored, so the vault works again.
            repository.restoreKeystoreBinding(recovered)
            val afterRestore = repository.decrypt(objectId, "restored.bin")
            assertEquals(digest, repository.sha256(afterRestore))
        } finally {
            recovered.fill(0)
        }
    }

    @Test
    fun aWrongPassphraseIsRejected() {
        repository.encrypt(storeFixture("wrong.bin"))
        assertTrue(repository.enableRecovery(passphrase))

        val failure = runCatching { repository.recoverVaultMasterKey(wrongPassphrase) }
            .exceptionOrNull()
        assertTrue("a wrong passphrase must be rejected, got $failure", failure is Exception)
    }

    @Test
    fun theRecoveryEnvelopeCanBeRemoved() {
        repository.encrypt(storeFixture("disable.bin"))
        assertTrue(repository.enableRecovery(passphrase))
        repository.disableRecovery()
        assertFalse(repository.hasRecovery())
    }

    @Test
    fun rotatingTheMasterKeyKeepsTheObjectsReadable() {
        val fixture = storeFixture("rotate.bin")
        val objectId = repository.encrypt(fixture)
        val digest = repository.sha256(fixture)

        val rotated = repository.rotateVaultMasterKey()
        assertTrue("at least one object must be re-wrapped", rotated >= 1)

        // The objects are readable with the (new) active key.
        val restored = repository.decrypt(objectId, "rotated.bin")
        assertEquals(digest, repository.sha256(restored))
    }

    @Test
    fun reencryptingAnObjectKeepsThePlaintext() {
        val fixture = storeFixture("reencrypt.bin")
        val objectId = repository.encrypt(fixture)
        val digest = repository.sha256(fixture)

        repository.reencryptObject(objectId)
        val restored = repository.decrypt(objectId, "reencrypted.bin")
        assertEquals(digest, repository.sha256(restored))
    }

    @Test
    fun theRecoveryEnvelopeNeverContainsThePlaintext() {
        val fixture = storeFixture("leak.bin", 8192)
        repository.encrypt(fixture)
        repository.enableRecovery(passphrase)

        val envelope = repository.recoveryEnvelopeFile.readBytes()
        val witness = repository.recoveryWitnessFile.readBytes()
        val key = repository.vaultMasterKey()
        try {
            assertFalse(
                "the envelope must not contain the master key",
                envelope.toString(Charsets.ISO_8859_1).contains(key.toString(Charsets.ISO_8859_1)),
            )
            assertFalse(
                "the witness must not contain the master key",
                witness.toString(Charsets.ISO_8859_1).contains(key.toString(Charsets.ISO_8859_1)),
            )
            // The passphrase must not be recoverable from the envelope.
            assertFalse(
                "the envelope must not contain the passphrase",
                envelope.toString(Charsets.ISO_8859_1)
                    .contains("correct horse battery staple"),
            )
        } finally {
            key.fill(0)
        }
    }
}
