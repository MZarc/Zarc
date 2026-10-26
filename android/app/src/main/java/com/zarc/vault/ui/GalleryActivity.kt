package com.zarc.vault.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zarc.vault.gallery.MediaItem
import com.zarc.vault.gallery.PrivateGate
import com.zarc.vault.ui.theme.ZarcTheme
import java.io.File

/**
 * The gallery activity.
 *
 * It hosts the Compose user interface and owns the two platform interactions
 * the gallery needs: the document/media picker and the biometric authentication prompt
 * matching system lock.
 */
class GalleryActivity : FragmentActivity() {

    private val viewModel: GalleryViewModel by viewModels()

    private var importingToPrivate = false
    private var isAppUnlockedSession = false
    private var appLockedState by mutableStateOf(false)

    // System Photo Picker with multi-select support (Samsung Gallery / Android Photo Picker)
    private val pickVisualMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.importMultiple(uris, isPrivate = importingToPrivate)
        }
    }

    // Document picker fallback with multi-select support
    private val openMultipleDocsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.importMultiple(uris, isPrivate = importingToPrivate)
        }
    }

    private fun launchPicker(isPrivate: Boolean) {
        importingToPrivate = isPrivate
        try {
            pickVisualMediaLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
            )
        } catch (e: Exception) {
            openMultipleDocsLauncher.launch(arrayOf("image/*", "video/*"))
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

                LaunchedEffect(state.appLockEnabled, state.stealthLockEnabled) {
                    val lockOn = state.appLockEnabled || state.stealthLockEnabled
                    if (lockOn && !isAppUnlockedSession) {
                        appLockedState = true
                        if (state.appLockEnabled) {
                            authenticateAppLock()
                        } else {
                            viewModel.showAppStealthDialog()
                        }
                    } else if (!lockOn) {
                        appLockedState = false
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    GalleryApp(
                        viewModel = viewModel,
                        onImport = { launchPicker(isPrivate = false) },
                        onImportPrivate = { launchPicker(isPrivate = true) },
                        onOpenWith = ::openWith,
                        onUnlockPrivate = ::unlockPrivateArea,
                    )

                    // App-level biometric lock overlay
                    AnimatedVisibility(
                        visible = appLockedState,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                                .clickable(enabled = false) {},
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                modifier = Modifier.padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(80.dp)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = ZarcIcons.Shield,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(44.dp),
                                    )
                                }
                                Spacer(Modifier.height(20.dp))
                                Text(
                                    text = "Zarc Vault Locked",
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = if (state.appLockEnabled && state.stealthLockEnabled) {
                                        "Authenticate with fingerprint & dynamic PIN to open"
                                    } else if (state.stealthLockEnabled) {
                                        "Enter dynamic time PIN to unlock"
                                    } else {
                                        "Touch fingerprint sensor or use screen lock credentials"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                                Spacer(Modifier.height(28.dp))
                                Button(onClick = {
                                    if (state.appLockEnabled) authenticateAppLock()
                                    else viewModel.showAppStealthDialog()
                                }) {
                                    Icon(ZarcIcons.Lock, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Unlock Zarc")
                                }
                            }
                        }
                    }

                    if (state.appStealthDialogOpen) {
                        StealthLockDialog(
                            onDismiss = viewModel::dismissAppStealthDialog,
                            onVerified = {
                                viewModel.dismissAppStealthDialog()
                                isAppUnlockedSession = true
                                appLockedState = false
                            },
                        )
                    }

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

    override fun onResume() {
        super.onResume()
        val lockOn = viewModel.state.value.appLockEnabled || viewModel.state.value.stealthLockEnabled
        if (lockOn && !isAppUnlockedSession) {
            appLockedState = true
            if (viewModel.state.value.appLockEnabled) {
                authenticateAppLock()
            } else {
                viewModel.showAppStealthDialog()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (viewModel.state.value.appLockEnabled || viewModel.state.value.stealthLockEnabled) {
            isAppUnlockedSession = false
        }
    }

    private fun authenticateAppLock() {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        try {
            val prompt = BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (viewModel.state.value.stealthLockEnabled) {
                            viewModel.showAppStealthDialog()
                        } else {
                            isAppUnlockedSession = true
                            appLockedState = false
                        }
                    }

                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        if (code != BiometricPrompt.ERROR_USER_CANCELED && code != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                            viewModel.message(message.toString())
                        }
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Zarc")
                .setSubtitle("Use your fingerprint or lock screen credentials")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build()
            prompt.authenticate(info)
        } catch (e: Exception) {
            android.util.Log.e("Zarc", "authenticateAppLock error: ${e.message}")
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
     * matching the system lock, then proceed to the Stealth PIN lock for double protection.
     */
    private fun unlockPrivateArea() {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        if (viewModel.state.value.privateUnlocked) {
            viewModel.selectTab(GalleryTab.PRIVATE)
            return
        }
        try {
            val gate = PrivateGate(this)
            val privateGateFile = File(viewModel.repository.objectsDir.parentFile, "private/gate.bin")
            if (!privateGateFile.exists()) {
                val enabled = viewModel.repository.enablePrivateArea()
                if (!enabled) {
                    viewModel.message("The private area could not be initialized")
                    return
                }
                viewModel.refresh()
            }

            val prompt = BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        try {
                            val file = File(viewModel.repository.objectsDir.parentFile, "private/gate.bin")
                            val key = gate.unwrap(file.readBytes())
                            viewModel.onPrivateUnlocked(key)
                        } catch (error: Exception) {
                            viewModel.message("The private key could not be released: ${error.message}")
                        }
                    }

                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        if (code != BiometricPrompt.ERROR_USER_CANCELED && code != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                            viewModel.message(message.toString())
                        }
                    }
                },
            )

            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Private Vault")
                .setSubtitle("Touch the fingerprint sensor or enter device lock PIN")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build()

            prompt.authenticate(info)
        } catch (t: Throwable) {
            android.util.Log.e("Zarc", "unlockPrivateArea error", t)
            viewModel.message("Error opening private area: ${t.message}")
        }
    }
}
