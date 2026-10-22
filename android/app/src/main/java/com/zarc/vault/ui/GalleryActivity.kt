package com.zarc.vault.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zarc.vault.gallery.MediaItem
import com.zarc.vault.gallery.PrivateGate
import com.zarc.vault.ui.theme.ZarcTheme
import java.io.File

/**
 * The gallery activity.
 *
 * It hosts the Compose user interface and owns the two platform interactions
 * the gallery needs: the document picker (Storage Access Framework) and the
 * biometric prompt that releases the private area.
 */
class GalleryActivity : FragmentActivity() {

    private val viewModel: GalleryViewModel by viewModels()

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.import(uri, isPrivate = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ZarcTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val snackbar = remember { SnackbarHostState() }

                LaunchedEffect(state.message) {
                    state.message?.let {
                        snackbar.showSnackbar(it)
                        viewModel.clearMessage()
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    GalleryApp(
                        viewModel = viewModel,
                        onImport = { importLauncher.launch(arrayOf("image/*", "video/*")) },
                        onOpenWith = ::openWith,
                        onUnlockPrivate = ::unlockPrivateArea,
                    )
                    SnackbarHost(
                        hostState = snackbar,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 96.dp),
                    )
                }
            }
        }
    }

    /**
     * Hand a decrypted copy of an item to another application.
     *
     * The copy is written to the cache directory and shared through a
     * `FileProvider` with a temporary read permission. The vault object itself
     * is never shared.
     */
    private fun openWith(item: MediaItem) {
        val isPrivate = item.isPrivate
        val file: File = viewModel.repository.exportForSharing(item, isPrivate)
        val uri: Uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file,
        )
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val share = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
            type = item.mime
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(view, "Open with").apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(share))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(chooser) }
            .onFailure { viewModel.message("no application can open this file") }
    }

    /**
     * Ask the platform for a biometric (or device credential) authentication
     * and release the private key when it succeeds.
     */
    private fun unlockPrivateArea() {
        val gate = PrivateGate(this)
        val wrapped = File(viewModel.repository.objectsDir.parentFile, "private/gate.bin")
        if (!wrapped.exists()) {
            viewModel.message("the private area has not been set up yet")
            return
        }
        val cipher = try {
            gate.cipher(wrapped.readBytes())
        } catch (error: Exception) {
            viewModel.message("the private gate key is not available: ${error.message}")
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authorised = result.cryptoObject?.cipher ?: return
                    try {
                        val key = gate.decryptWith(authorised, wrapped.readBytes())
                        viewModel.onPrivateUnlocked(key)
                    } catch (error: Exception) {
                        viewModel.message("the private key could not be released")
                    }
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    viewModel.message(message.toString())
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock the private album")
            .setSubtitle("The private key is only released after an authentication")
            .setNegativeButtonText("Cancel")
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}
