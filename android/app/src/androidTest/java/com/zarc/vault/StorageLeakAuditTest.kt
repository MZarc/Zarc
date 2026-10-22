package com.zarc.vault

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zarc.vault.core.VaultRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Creates a vault object that carries a unique, searchable marker.
 *
 * The test is driven by `tools/device-audit.ps1`, which passes the marker and
 * the original file name as instrumentation arguments, and then searches the
 * whole device (with elevated privileges) for the marker.
 *
 * The marker is synthetic: it is not personal data.
 */
@RunWith(AndroidJUnit4::class)
class StorageLeakAuditTest {

    @Test
    fun createAnObjectWithAUniqueMarker() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val marker = arguments.getString("marker") ?: "ZARCAUDITMARKER"
        val fileName = arguments.getString("filename") ?: "zarc-audit-fixture.jpg"

        val repository = VaultRepository(context)
        repository.ensureDirectories()

        // A synthetic "media" file: a JPEG header followed by the marker.
        val header = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00,
            0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
        )
        val payload = ByteArray(120 * 1024) { index -> (index % 251).toByte() }
        val markerBytes = marker.toByteArray()
        System.arraycopy(markerBytes, 0, payload, 1024, markerBytes.size)

        val fixture = File(repository.workDir, fileName)
        fixture.writeBytes(header + payload)

        val before = repository.sha256(fixture)
        val objectId = repository.encrypt(fixture)

        // The round trip has to work, otherwise the audit is meaningless.
        val restored = repository.decrypt(objectId, "zarc-audit-restored.bin")
        assertEquals(before, repository.sha256(restored))
        assertTrue(
            "the restored file must contain the marker",
            restored.readBytes().toString(Charsets.ISO_8859_1).contains(marker),
        )

        // The stored object must not contain the marker.
        val stored = File(repository.objectsDir, objectId).readBytes()
        assertFalse(
            "the marker must not appear in the stored object",
            stored.toString(Charsets.ISO_8859_1).contains(marker),
        )

        // Leave a note for the audit script with the identifiers it needs.
        val note = File(context.cacheDir, "zarc-audit-note.txt")
        note.writeText(
            listOf(
                "marker=$marker",
                "filename=$fileName",
                "objectId=$objectId",
                "objectsDir=${repository.objectsDir.absolutePath}",
                "package=${context.packageName}",
            ).joinToString("\n"),
        )
    }
}
