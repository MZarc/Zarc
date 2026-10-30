package com.zarc.vault.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.zarc.vault.core.VaultRepository
import com.zarc.vault.core.ZarcException
import java.io.File

/**
 * The developer oriented Security Lab screen.
 *
 * It exists so that the *real* Android implementation can be exercised on a
 * device: the Android Keystore, the Rust core, the container format and the
 * storage layout. It is not a gallery.
 */
class SecurityLabActivity : Activity() {

    private lateinit var repository: VaultRepository
    private lateinit var output: TextView
    private lateinit var passphrase: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = VaultRepository(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        val title = TextView(this).apply {
            text = "Zarc - Security Lab"
            textSize = 22f
            setPadding(0, 0, 0, 24)
        }
        root.addView(title)

        root.addView(button("1. Platform key report") { showPlatformReport() })
        root.addView(button("2. Crypto self test") { showSelfTest() })
        root.addView(button("3. Encrypt a synthetic fixture") { showEncryptionRoundTrip() })
        root.addView(button("4. Import a file (system picker)") { pickFile() })
        root.addView(button("5. List vault objects") { showListing() })

        // The passphrase field is used by the recovery actions below. It is a
        // password field: the text is never shown and never logged.
        passphrase = EditText(this).apply {
            hint = "recovery passphrase"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(0, 16, 0, 0)
        }
        root.addView(passphrase)
        root.addView(button("6. Enable recovery (passphrase)") { enableRecovery() })
        root.addView(button("7. Recover the vault with the passphrase") { recoverVault() })
        root.addView(button("8. Rotate the vault master key") { rotateMasterKey() })
        root.addView(button("9. Re-encrypt the first object") { reencryptFirstObject() })

        output = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 12f
            setPadding(0, 24, 0, 0)
        }
        root.addView(ScrollView(this).apply { addView(output) })

        setContentView(root)
    }

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { runCatching(action).onFailure { append("ERROR: ${it.message}") } }
        }

    private fun append(text: String) {
        output.append(text)
        output.append("\n")
    }

    private fun showPlatformReport() {
        append("=== Platform key report ===")
        val report = repository.keystoreReport()
        append(report.summary())
        append("blockModes=${report.blockModes} paddings=${report.paddings}")
        append("strongBoxAvailable=${repository.hasStrongBox()}")
        append("vaultObjects=${repository.objectsDir.absolutePath}")
        append("wrappedKey=${repository.wrappedKeyLocation()}")
        append(repository.cryptoInfo())
    }

    private fun showSelfTest() {
        append("=== Crypto self test ===")
        append(repository.selfTest())
    }

    private fun showEncryptionRoundTrip() {
        append("=== Synthetic fixture round trip ===")
        repository.ensureDirectories()
        val fixture = File(repository.workDir, "synthetic-fixture.jpg")
        val payload = ByteArray(96 * 1024) { index -> (index * 31 % 251).toByte() }
        // A synthetic JPEG header so that the fixture is recognisable as a
        // media file before it is encrypted.
        val header = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00,
            0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
        )
        fixture.writeBytes(header + payload)
        val before = repository.sha256(fixture)
        val objectId = repository.encrypt(fixture)
        append("fixture=${fixture.absolutePath}")
        append("sha256(plaintext)=$before")
        append("objectId=$objectId")
        append(repository.verify(objectId))

        val restored = repository.decrypt(objectId, "restored-fixture.bin")
        val after = repository.sha256(restored)
        append("sha256(restored)=$after")
        append("identical=${before == after}")
        append("storedFileName=${repository.vaultFileNames()}")
        append("plaintextLeakInVault=${repository.plaintextAppearsInVault(fixture)}")
    }

    private fun showListing() {
        append("=== Vault objects ===")
        for (id in repository.listObjects()) {
            append("$id  ${repository.verify(id)}")
        }
    }

    private fun enableRecovery() {
        val value = passphrase.text.toString()
        if (value.length < 8) {
            append("the passphrase must be at least 8 characters long")
            return
        }
        val chars = value.toCharArray()
        passphrase.setText("")
        try {
            val created = repository.enableRecovery(chars)
            append("=== Recovery envelope ===")
            append("created=$created")
            append("hasRecovery=${repository.hasRecovery()}")
            append("the passphrase is never stored; the envelope is protected with Argon2id")
        } finally {
            chars.fill('\u0000')
        }
    }

    private fun recoverVault() {
        val value = passphrase.text.toString()
        if (value.isEmpty()) {
            append("enter the recovery passphrase first")
            return
        }
        val chars = value.toCharArray()
        passphrase.setText("")
        try {
            val key = repository.recoverVaultMasterKey(chars)
            try {
                append("=== Recovery ===")
                append("recovered a ${key.size} byte vault master key")
                append("objects=${repository.listObjects().size}")
            } finally {
                key.fill(0)
            }
        } catch (error: Exception) {
            append("recovery failed: ${error.message}")
        } finally {
            chars.fill('\u0000')
        }
    }

    private fun rotateMasterKey() {
        try {
            val rotated = repository.rotateVaultMasterKey()
            append("=== Vault master key rotation ===")
            append("re-wrapped $rotated object(s)")
            val skipped = repository.lastRotationSkipped
            if (skipped > 0) {
                append("$skipped already-damaged object(s) were left untouched")
            }
            append("the content of the objects was not re-encrypted")
        } catch (error: Exception) {
            append("rotation failed: ${error.message}")
        }
    }

    private fun reencryptFirstObject() {
        val ids = repository.listObjects()
        if (ids.isEmpty()) {
            append("the vault is empty")
            return
        }
        try {
            val info = repository.reencryptObject(ids.first())
            append("=== Re-encryption ===")
            append(info)
        } catch (error: Exception) {
            append("re-encryption failed: ${error.message}")
        }
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(intent, REQUEST_OPEN_DOCUMENT)
        } catch (error: android.content.ActivityNotFoundException) {
            append("no document provider available: ${error.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OPEN_DOCUMENT) return
        if (resultCode != RESULT_OK) {
            append("import cancelled")
            return
        }
        val uri: Uri = data?.data ?: return
        append("=== Imported document ===")
        append("uri=$uri")
        try {
            repository.ensureDirectories()
            val staged = File(repository.workDir, "imported-${System.currentTimeMillis()}")
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "the document could not be opened" }
                staged.outputStream().use { output -> input.copyTo(output) }
            }
            append("imported=${staged.absolutePath} size=${staged.length()}")
            val before = repository.sha256(staged)
            val objectId = repository.encrypt(staged)
            append("sha256(plaintext)=$before")
            append("objectId=$objectId")
            append(repository.verify(objectId))
            val restored = repository.decrypt(objectId, "imported-restored.bin")
            append("sha256(restored)=${repository.sha256(restored)}")
            append("identical=${before == repository.sha256(restored)}")
        } catch (error: ZarcException) {
            append("vault error: ${error.message}")
        } catch (error: Exception) {
            append("error: ${error.message}")
        }
    }

    companion object {
        private const val REQUEST_OPEN_DOCUMENT = 1001
    }
}


/**
 * Search the vault directory for the raw bytes of a plaintext file.
 *
 * This is the on-device counterpart of the storage leakage audit.
 */
private fun VaultRepository.plaintextAppearsInVault(plaintext: File): Boolean {
    val all = plaintext.readBytes()
    val needle = all.copyOf(minOf(all.size, 4096))
    if (needle.isEmpty()) return false
    return objectsDir.listFiles().orEmpty().any { candidate ->
        candidate.readBytes().containsBytes(needle)
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
