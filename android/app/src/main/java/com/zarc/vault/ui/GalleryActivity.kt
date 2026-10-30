package com.zarc.vault.ui

import android.content.ComponentCallbacks2
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.zarc.vault.gallery.MediaItem
import com.zarc.vault.gallery.PrivateGate
import com.zarc.vault.ui.theme.ZarcTheme
import java.io.File
import javax.crypto.Cipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var suppressPrivateRelock = false
    private var isAppUnlockedSession = false
    private var appLockedState by mutableStateOf(false)
    private var isAppInRecents by mutableStateOf(false)

    // System Photo Picker with multi-select support (Samsung Gallery / Android Photo Picker)
    private val pickVisualMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris: List<Uri> ->
        suppressPrivateRelock = false
        if (uris.isNotEmpty()) {
            viewModel.importMultiple(uris, isPrivate = importingToPrivate)
        }
    }

    // Document picker fallback with multi-select support
    private val openMultipleDocsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        suppressPrivateRelock = false
        if (uris.isNotEmpty()) {
            viewModel.importMultiple(uris, isPrivate = importingToPrivate)
        }
    }

    private fun launchPicker(isPrivate: Boolean) {
        importingToPrivate = isPrivate
        // The picker moves the activity to the background; do not relock the
        // private area while the user is choosing the files to import.
        suppressPrivateRelock = isPrivate
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
        // Prevent screenshots, screen recording and unredacted recents
        // snapshots for every screen, including the normal gallery.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
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
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (isAppInRecents) Modifier.blur(25.dp) else Modifier),
                    ) {
                        GalleryApp(
                            viewModel = viewModel,
                            onImport = { launchPicker(isPrivate = false) },
                            onImportPrivate = { launchPicker(isPrivate = true) },
                            onOpenWith = ::openWith,
                            onUnlockPrivate = ::unlockPrivateArea,
                        )
                    }

                    // Frosted privacy scrim when in recent apps
                    if (isAppInRecents) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0x9907080A))
                                .clickable(enabled = false) {},
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(76.dp)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = ZarcIcons.Shield,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(40.dp),
                                    )
                                }
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    text = "Zarc Vault",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                                    color = Color.White,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Hardware-Encrypted Privacy Protected",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }

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

    override fun onPause() {
        super.onPause()
        isAppInRecents = true
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            window.decorView.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    35f, 35f, android.graphics.Shader.TileMode.MIRROR,
                ),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        isAppInRecents = false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            window.decorView.setRenderEffect(null)
        }
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
        if (!suppressPrivateRelock) {
            // The private area is relocked whenever the app leaves the
            // foreground, even if the app-level lock is disabled.
            viewModel.lockPrivateArea()
        }
        if (viewModel.state.value.appLockEnabled || viewModel.state.value.stealthLockEnabled) {
            isAppUnlockedSession = false
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            ThumbnailCache.trim(level)
            ViewerImageCache.trim(level)
            ArchiveVaultSessionCache.trim(level)
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
        // Decryption can take seconds for a large video; never block the main
        // thread on it.
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { viewModel.repository.exportForSharing(item, isPrivate) }
            }
            val file = result.getOrElse {
                viewModel.message("the item could not be decrypted: ${it.message}")
                return@launch
            }
            val uri: Uri = FileProvider.getUriForFile(
                this@GalleryActivity,
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
    }

    /**
     * Ask the platform for a biometric authentication, then release the
     * private subkey with the authorised Keystore operation.
     */
    private fun unlockPrivateArea() {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        if (viewModel.state.value.privateUnlocked) {
            viewModel.selectTab(GalleryTab.PRIVATE)
            return
        }
        try {
            val gate = PrivateGate(this)
            if (!gate.ensureKey()) {
                viewModel.message(
                    "The private area requires an enrolled fingerprint or face; " +
                        "the authentication-bound key could not be created",
                )
                return
            }
            val repository = viewModel.repository
            val gateFile = File(repository.objectsDir.parentFile, "private/gate.bin")

            if (!gateFile.exists()) {
                if (repository.hasPrivateData() || repository.hasLegacyPrivateGate()) {
                    migrateLegacyPrivateArea(gate)
                } else {
                    setUpPrivateArea(gate, gateFile)
                }
                return
            }

            val wrapped = gateFile.readBytes()
            if (gate.isCurrentFormat(wrapped)) {
                val cipher = gate.beginUnwrap(wrapped)
                promptPrivateKey(cipher, "Unlock Private Vault") { authorized ->
                    val key = gate.finishUnwrap(authorized, wrapped)
                    viewModel.onPrivateUnlocked(key)
                }
            } else {
                migrateLegacyPrivateArea(gate)
            }
        } catch (t: Throwable) {
            android.util.Log.e("Zarc", "unlockPrivateArea error", t)
            viewModel.message("Error opening private area: ${t.message}")
        }
    }

    /** Create the first private area with a fresh random subkey. */
    private fun setUpPrivateArea(gate: PrivateGate, gateFile: File) {
        val repository = viewModel.repository
        val subkey = repository.newPrivateSubkey()
        val cipher = gate.beginWrap()
        promptPrivateKey(
            cipher,
            "Set up Private Vault",
            onFailure = { subkey.fill(0) },
        ) { authorized ->
            try {
                val wrapped = gate.finishWrap(authorized, subkey)
                repository.savePrivateGate(wrapped)
                viewModel.onPrivateUnlocked(subkey)
            } catch (error: Exception) {
                subkey.fill(0)
                viewModel.message("The private area could not be set up: ${error.message}")
            }
        }
    }

    /**
     * Upgrade a pre-v2 private area from the deterministic vault-derived
     * subkey to an independent random subkey wrapped by the auth-bound key.
     */
    private fun migrateLegacyPrivateArea(gate: PrivateGate) {
        val repository = viewModel.repository
        val gateFile = File(repository.objectsDir.parentFile, "private/gate.bin")
        val legacyFromGate = runCatching {
            if (gateFile.exists()) {
                val wrapped = gateFile.readBytes()
                if (gate.isCurrentFormat(wrapped)) null else gate.unwrapLegacy(wrapped)
            } else {
                null
            }
        }.getOrNull()
        val legacyKey = legacyFromGate ?: repository.legacyPrivateSubkey()
        val newKey = repository.newPrivateSubkey()
        val cipher = gate.beginWrap()
        promptPrivateKey(
            cipher,
            "Upgrade Private Vault",
            onFailure = {
                legacyKey.fill(0)
                newKey.fill(0)
            },
        ) { authorized ->
            try {
                val wrapped = gate.finishWrap(authorized, newKey)
                repository.markPrivateMigration()
                repository.savePrivateGate(wrapped)
                // The view model finishes the object migration before it
                // publishes the unlocked private index.
                viewModel.onPrivateUnlocked(newKey)
            } catch (error: Exception) {
                newKey.fill(0)
                viewModel.message("The private area could not be upgraded: ${error.message}")
            } finally {
                legacyKey.fill(0)
            }
        }
    }

    /**
     * Show a strong-biometric prompt bound to [cipher].
     *
     * Device-credential authentication cannot authorise a `CryptoObject`, so
     * the private gate deliberately requires biometrics.
     */
    private fun promptPrivateKey(
        cipher: Cipher,
        title: String,
        onFailure: () -> Unit = {},
        onSuccess: (Cipher) -> Unit,
    ) {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            onFailure()
            return
        }
        try {
            val prompt = BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val authorized = result.cryptoObject?.cipher
                        if (authorized == null) {
                            viewModel.message("The private key could not be released")
                            onFailure()
                        } else {
                            onSuccess(authorized)
                        }
                    }

                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        if (code != BiometricPrompt.ERROR_USER_CANCELED && code != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                            viewModel.message(message.toString())
                        }
                        onFailure()
                    }
                },
            )

            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("Touch the fingerprint sensor to release the private key")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Cancel")
                .build()

            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        } catch (error: Exception) {
            onFailure()
            viewModel.message("The biometric prompt could not be started: ${error.message}")
        }
    }
}
