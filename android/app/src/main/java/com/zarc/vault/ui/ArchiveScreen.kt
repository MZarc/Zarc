package com.zarc.vault.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zarc.vault.archive.ArchiveManifestData
import com.zarc.vault.archive.ArchiveObject
import com.zarc.vault.archive.ArchivePhotoSource
import com.zarc.vault.archive.ArchiveRepository
import com.zarc.vault.gallery.MediaItem
import com.zarc.vault.ui.theme.ZarcPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen modes for Zarc Disguised Archive.
 */
enum class ArchiveScreenMode {
    LAUNCHER,
    CREATE,
    EXTRACT,
}

/**
 * Hallmark-crafted Disguised Archive Screen.
 *
 * Implements:
 * 1. Dedicated launcher landing with two prominent action cards:
 *    - "Create Zarc Archive"
 *    - "Extract Zarc Archive"
 * 2. Create flow:
 *    - Tips / security info
 *    - Multi-photo selection from Phone Gallery OR Zarc Vault
 *    - Disguise as JPEG option (ON by default)
 *    - Output filename popup with uneditable extension (.jpg / .zarc) and encryption passphrase
 *    - Build & save via SAF CreateDocument
 * 3. Extract flow:
 *    - File selector for .jpg or .zarc
 *    - Instant outer cover preview for disguised JPEGs (0 password required)
 *    - Passphrase prompt with comprehensive cryptographic error handling
 *    - Decrypted Zarc Vault gallery UI with thumbnail grid, mini-roll, and info dialog
 *    - Single photo extraction and bulk "Extract all" extraction via SAF OpenDocumentTree
 */
@Composable
fun ArchiveScreen(
    onBack: () -> Unit,
    viewModel: GalleryViewModel,
) {
    val context = LocalContext.current
    val repository = remember { ArchiveRepository(context) }
    var currentMode by remember { mutableStateOf(ArchiveScreenMode.LAUNCHER) }

    // Intercept hardware/gesture back to navigate cleanly through sub-screens
    BackHandler {
        if (currentMode != ArchiveScreenMode.LAUNCHER) {
            currentMode = ArchiveScreenMode.LAUNCHER
        } else {
            onBack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (currentMode) {
            ArchiveScreenMode.LAUNCHER -> {
                ArchiveLauncherScreen(
                    onBack = onBack,
                    onCreateClick = { currentMode = ArchiveScreenMode.CREATE },
                    onExtractClick = { currentMode = ArchiveScreenMode.EXTRACT },
                )
            }
            ArchiveScreenMode.CREATE -> {
                CreateArchiveFlow(
                    repository = repository,
                    viewModel = viewModel,
                    onBack = { currentMode = ArchiveScreenMode.LAUNCHER },
                )
            }
            ArchiveScreenMode.EXTRACT -> {
                ExtractArchiveFlow(
                    repository = repository,
                    onBack = { currentMode = ArchiveScreenMode.LAUNCHER },
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 1. ARCHIVE LAUNCHER LANDING SCREEN
// -----------------------------------------------------------------------------

@Composable
private fun ArchiveLauncherScreen(
    onBack: () -> Unit,
    onCreateClick: () -> Unit,
    onExtractClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = ZarcIcons.Back,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Disguised photo archive",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Encrypted containers hidden in plain sight",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Hero Explanatory Card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = ZarcIcons.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = "JPEG Polyglot Steganography",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    text = "Package private photos into an Album.jpg file. Phone galleries and Google Photos display the cover image normally, while the encrypted Zarc container remains completely hidden behind Argon2id and XChaCha20-Poly1305.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )

                Spacer(Modifier.height(14.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TechnicalChip("100% Offline")
                    TechnicalChip("XChaCha20-Poly1305")
                    TechnicalChip("Argon2id KDF")
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Action Section Header
        Text(
            text = "Select an action",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
        )

        Spacer(Modifier.height(8.dp))

        // Card 1: Create Zarc Archive
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(22.dp))
                .clickable(onClick = onCreateClick),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = ZarcIcons.Import,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Create Zarc Archive",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                        ) {
                            Text(
                                text = "PACK",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Select photos from phone or vault, sanitize metadata, disguise as JPEG, and set master passphrase.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                }

                Spacer(Modifier.width(8.dp))

                Icon(
                    imageVector = ZarcIcons.Chevron,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Card 2: Extract Zarc Archive
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(22.dp))
                .clickable(onClick = onExtractClick),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = ZarcIcons.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Extract Zarc Archive",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Open an Album.jpg or .zarc container, authenticate with passphrase, browse decrypted gallery, and extract media.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                }

                Spacer(Modifier.width(8.dp))

                Icon(
                    imageVector = ZarcIcons.Chevron,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 2. CREATE ARCHIVE FLOW
// -----------------------------------------------------------------------------

@Composable
private fun CreateArchiveFlow(
    repository: ArchiveRepository,
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vaultState by viewModel.state.collectAsStateWithLifecycle()

    var selectedSources by remember { mutableStateOf<List<ArchivePhotoSource>>(emptyList()) }
    var coverIndex by remember { mutableIntStateOf(0) }
    var disguiseAsJpeg by remember { mutableStateOf(true) }

    var showVaultPicker by remember { mutableStateOf(false) }
    var showBuildDialog by remember { mutableStateOf(false) }

    var isBuilding by remember { mutableStateOf(false) }
    var buildProgressText by remember { mutableStateOf("") }
    var builtTempFile by remember { mutableStateOf<File?>(null) }
    var builtOutputName by remember { mutableStateOf("") }

    var successMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Launcher for picking photos from phone gallery
    val phonePhotoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                val newSources = mutableListOf<ArchivePhotoSource>()
                for (uri in uris) {
                    val (name, size) = queryUriNameAndSize(context, uri)
                    val bmp = runCatching {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeStream(stream, null, bounds)
                            var sample = 1
                            while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) {
                                sample *= 2
                            }
                            context.contentResolver.openInputStream(uri)?.use { s2 ->
                                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                                BitmapFactory.decodeStream(s2, null, opts)
                            }
                        }
                    }.getOrNull()
                    newSources.add(
                        ArchivePhotoSource(
                            displayName = name,
                            sizeBytes = size,
                            uri = uri,
                            previewBitmap = bmp,
                        )
                    )
                }
                withContext(Dispatchers.Main) {
                    selectedSources = selectedSources + newSources
                }
            }
        }
    }

    // Launcher for saving the completed file to destination via SAF
    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(
            if (disguiseAsJpeg) "image/jpeg" else "application/octet-stream"
        )
    ) { destinationUri ->
        if (destinationUri != null && builtTempFile != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                        builtTempFile!!.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                    val sizeMb = "%.2f".format(builtTempFile!!.length() / (1024.0 * 1024.0))
                    val count = selectedSources.size
                    withContext(Dispatchers.Main) {
                        successMessage = "Archive saved successfully!\n$count photos encrypted in $sizeMb MB."
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        errorMessage = "Failed to save file: ${e.message}"
                    }
                } finally {
                    builtTempFile?.delete()
                    builtTempFile = null
                }
            }
        } else {
            builtTempFile?.delete()
            builtTempFile = null
        }
    }

    // Vault Selection Dialog
    if (showVaultPicker) {
        VaultPhotoPickerDialog(
            items = vaultState.roll,
            onDismiss = { showVaultPicker = false },
            onConfirmSelection = { pickedItems ->
                showVaultPicker = false
                scope.launch(Dispatchers.IO) {
                    val vaultSources = mutableListOf<ArchivePhotoSource>()
                    for (item in pickedItems) {
                        val decryptedFile = runCatching {
                            viewModel.repository.decrypt(item, isPrivate = false)
                        }.getOrNull()
                        if (decryptedFile != null && decryptedFile.exists()) {
                            val bmp = runCatching {
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeFile(decryptedFile.absolutePath, bounds)
                                var sample = 1
                                while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
                                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                                BitmapFactory.decodeFile(decryptedFile.absolutePath, opts)
                            }.getOrNull()
                            vaultSources.add(
                                ArchivePhotoSource(
                                    displayName = item.name,
                                    sizeBytes = decryptedFile.length(),
                                    localFile = decryptedFile,
                                    previewBitmap = bmp,
                                )
                            )
                        }
                    }
                    withContext(Dispatchers.Main) {
                        selectedSources = selectedSources + vaultSources
                    }
                }
            }
        )
    }

    // Build Archive Dialog (Passphrase & Name Popup)
    if (showBuildDialog) {
        ArchiveBuildDialog(
            disguiseAsJpeg = disguiseAsJpeg,
            photoCount = selectedSources.size,
            onDismiss = { showBuildDialog = false },
            onBuildConfirmed = { outputBaseName, passphrase ->
                showBuildDialog = false
                isBuilding = true
                buildProgressText = "Sanitizing metadata & encrypting..."
                builtOutputName = outputBaseName

                scope.launch {
                    val ext = if (disguiseAsJpeg) "jpg" else "zarc"
                    val tempOutput = File(context.cacheDir, "built_${System.currentTimeMillis()}.$ext")
                    val result = repository.createArchiveFromSources(
                        outputFile = tempOutput,
                        sources = selectedSources,
                        coverIndex = coverIndex,
                        passphrase = passphrase,
                        createAsPolyglotJpeg = disguiseAsJpeg,
                    )
                    isBuilding = false
                    result.fold(
                        onSuccess = { file ->
                            builtTempFile = file
                            // Trigger SAF CreateDocument to save
                            saveFileLauncher.launch("$outputBaseName.$ext")
                        },
                        onFailure = { error ->
                            errorMessage = "Creation failed: ${error.message}"
                        }
                    )
                }
            }
        )
    }

    // Success Alert
    successMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = {
                successMessage = null
                onBack()
            },
            icon = {
                Icon(
                    imageVector = ZarcIcons.Check,
                    contentDescription = null,
                    tint = ZarcPalette.verified,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = { Text("Archive Created") },
            text = { Text(msg) },
            confirmButton = {
                Button(
                    onClick = {
                        successMessage = null
                        onBack()
                    }
                ) {
                    Text("Done")
                }
            }
        )
    }

    // Error Alert
    errorMessage?.let { err ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            icon = {
                Icon(
                    imageVector = ZarcIcons.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = { Text("Error") },
            text = { Text(err) },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) {
                    Text("OK")
                }
            }
        )
    }

    // Main Create UI
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = ZarcIcons.Back,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Create disguised archive",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Select photos, disguise, and build container",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Info / Tips Card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = ZarcIcons.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Stealth Disguise & Sanitization",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "• Native Rust strips GPS coordinates, camera serials, and EXIF tags before packaging.\n" +
                            "• All internal photos, filenames, and metadata are encrypted with Argon2id + XChaCha20-Poly1305.\n" +
                            "• The outer cover remains a genuine JPEG photo that opens normally in any gallery or Google Photos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Photos Selection Header & Source Buttons
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Archive photos",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${selectedSources.size} photos selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Two Source Buttons: From Phone vs From Vault
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            phonePhotoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(ZarcIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("From Phone")
                    }

                    OutlinedButton(
                        onClick = { showVaultPicker = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Icon(ZarcIcons.Lock, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text("From Vault", color = MaterialTheme.colorScheme.onSurface)
                    }
                }

                // Selected Photos Carousel / List
                if (selectedSources.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Tap a photo to set as outer cover",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(selectedSources) { idx, src ->
                            val isCover = idx == coverIndex
                            Surface(
                                modifier = Modifier
                                    .width(100.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { coverIndex = idx },
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(
                                    if (isCover) 2.dp else 1.dp,
                                    if (isCover) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    ) {
                                        if (src.previewBitmap != null) {
                                            Image(
                                                bitmap = src.previewBitmap.asImageBitmap(),
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Icon(
                                                imageVector = ZarcIcons.Roll,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .align(Alignment.Center)
                                            )
                                        }

                                        // Remove Button
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(4.dp)
                                                .size(22.dp)
                                                .clip(CircleShape)
                                                .background(Color.Black.copy(alpha = 0.65f))
                                                .clickable {
                                                    selectedSources = selectedSources.toMutableList().also { it.removeAt(idx) }
                                                    if (coverIndex >= selectedSources.size) {
                                                        coverIndex = (selectedSources.size - 1).coerceAtLeast(0)
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = ZarcIcons.Close,
                                                contentDescription = "Remove",
                                                tint = Color.White,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }

                                        if (isCover) {
                                            Surface(
                                                modifier = Modifier
                                                    .align(Alignment.BottomCenter)
                                                    .padding(bottom = 4.dp),
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.primary
                                            ) {
                                                Text(
                                                    text = "COVER",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 8.sp
                                                    ),
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(6.dp))

                                    Text(
                                        text = src.displayName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Disguise Configuration Card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Disguise as JPEG photo (.jpg)",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (disguiseAsJpeg) {
                                "Outer cover visible to any phone gallery or Google Photos. Private payload stays 100% encrypted."
                            } else {
                                "Save as standalone .zarc container without a disguise cover."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = disguiseAsJpeg,
                        onCheckedChange = { disguiseAsJpeg = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Continue / Build Button
        Button(
            onClick = { showBuildDialog = true },
            enabled = selectedSources.isNotEmpty() && !isBuilding,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            if (isBuilding) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.5.dp
                )
                Spacer(Modifier.width(10.dp))
                Text(buildProgressText)
            } else {
                Text(
                    text = if (selectedSources.isEmpty()) "Add photos to continue" else "Configure & build archive",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 3. EXTRACT ARCHIVE FLOW
// -----------------------------------------------------------------------------

@Composable
private fun ExtractArchiveFlow(
    repository: ArchiveRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedArchiveUri by remember { mutableStateOf<Uri?>(null) }
    var selectedArchiveName by remember { mutableStateOf("") }
    var selectedArchiveSize by remember { mutableStateOf(0L) }
    var localCachedArchiveFile by remember { mutableStateOf<File?>(null) }
    var outerCoverBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var passphrase by remember { mutableStateOf("") }
    var showPassphraseDialog by remember { mutableStateOf(false) }

    var isUnlocking by remember { mutableStateOf(false) }
    var unlockedManifest by remember { mutableStateOf<ArchiveManifestData?>(null) }

    var activeViewerObject by remember { mutableStateOf<ArchiveObject?>(null) }
    var viewerBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showInfoDialog by remember { mutableStateOf(false) }

    var isExtractingAll by remember { mutableStateOf(false) }
    var extractProgressText by remember { mutableStateOf("") }

    var alertTitle by remember { mutableStateOf<String?>(null) }
    var alertMessage by remember { mutableStateOf<String?>(null) }

    // Memory cache for decoded entry bitmaps
    val entryBitmapCache = remember { mutableMapOf<String, Bitmap>() }

    // Launcher for picking .jpg or .zarc container
    val pickArchiveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedArchiveUri = uri
            val (name, size) = queryUriNameAndSize(context, uri)
            selectedArchiveName = name
            selectedArchiveSize = size
            unlockedManifest = null
            outerCoverBitmap = null
            activeViewerObject = null

            // Cache to disk and check for outer cover
            scope.launch(Dispatchers.IO) {
                val cacheArchive = File(context.cacheDir, "open_${System.currentTimeMillis()}_$name")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(cacheArchive).use { out -> input.copyTo(out) }
                }
                localCachedArchiveFile = cacheArchive

                // Try reading outer cover without passphrase
                val outerBytes = repository.readOuterCover(cacheArchive).getOrNull()
                if (outerBytes != null) {
                    val bmp = BitmapFactory.decodeByteArray(outerBytes, 0, outerBytes.size)
                    withContext(Dispatchers.Main) {
                        outerCoverBitmap = bmp
                        showPassphraseDialog = true
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showPassphraseDialog = true
                    }
                }
            }
        }
    }

    // Launcher for extracting all entries via SAF OpenDocumentTree
    val extractAllLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null && localCachedArchiveFile != null && unlockedManifest != null) {
            scope.launch {
                isExtractingAll = true
                extractProgressText = "Extracting all photos..."
                val destTempDir = File(context.cacheDir, "extract_all_${System.currentTimeMillis()}")
                val result = repository.extractAllEntries(
                    archiveFile = localCachedArchiveFile!!,
                    passphrase = passphrase,
                    destinationDir = destTempDir
                )

                result.fold(
                    onSuccess = { files ->
                        withContext(Dispatchers.IO) {
                            for (file in files) {
                                val docUri = DocumentFile.fromTreeUri(context, treeUri)
                                    ?.createFile("image/jpeg", file.name)?.uri
                                if (docUri != null) {
                                    context.contentResolver.openOutputStream(docUri)?.use { out ->
                                        file.inputStream().use { input -> input.copyTo(out) }
                                    }
                                }
                            }
                        }
                        isExtractingAll = false
                        alertTitle = "Extraction Complete"
                        alertMessage = "Successfully extracted ${files.size} photos to device storage."
                    },
                    onFailure = { err ->
                        isExtractingAll = false
                        alertTitle = "Extraction Failed"
                        alertMessage = err.message ?: "Failed to extract entries."
                    }
                )
            }
        }
    }

    // Launcher for extracting a single photo
    var singleExtractObject by remember { mutableStateOf<ArchiveObject?>(null) }
    val extractSingleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/jpeg")
    ) { targetUri ->
        if (targetUri != null && localCachedArchiveFile != null && singleExtractObject != null) {
            scope.launch(Dispatchers.IO) {
                val tempSingle = File(context.cacheDir, "single_${singleExtractObject!!.id}.tmp")
                val res = repository.extractSingleEntry(
                    archiveFile = localCachedArchiveFile!!,
                    passphrase = passphrase,
                    objectId = singleExtractObject!!.id,
                    destinationFile = tempSingle
                )
                res.fold(
                    onSuccess = { file ->
                        context.contentResolver.openOutputStream(targetUri)?.use { out ->
                            file.inputStream().use { input -> input.copyTo(out) }
                        }
                        file.delete()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Saved ${singleExtractObject!!.originalName}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onFailure = { err ->
                        withContext(Dispatchers.Main) {
                            alertTitle = "Extract Error"
                            alertMessage = err.message ?: "Could not extract photo."
                        }
                    }
                )
            }
        }
    }

    // Passphrase Dialog
    if (showPassphraseDialog) {
        ArchivePassphraseDialog(
            fileName = selectedArchiveName,
            isUnlocking = isUnlocking,
            onDismiss = {
                showPassphraseDialog = false
            },
            onUnlock = { pass ->
                passphrase = pass
                isUnlocking = true
                scope.launch {
                    val result = repository.readManifest(localCachedArchiveFile!!, pass)
                    isUnlocking = false
                    result.fold(
                        onSuccess = { manifest ->
                            showPassphraseDialog = false
                            unlockedManifest = manifest
                            entryBitmapCache.clear()
                        },
                        onFailure = { err ->
                            val msg = err.message ?: "Unknown error"
                            alertTitle = "Decryption Failed"
                            alertMessage = if (msg.contains("authentication", ignoreCase = true) || msg.contains("mac", ignoreCase = true) || msg.contains("passphrase", ignoreCase = true)) {
                                "Incorrect passphrase. Authentication tag verification failed."
                            } else if (msg.contains("format", ignoreCase = true) || msg.contains("magic", ignoreCase = true)) {
                                "The selected file is not a valid Zarc container or has been tampered with."
                            } else {
                                "Authentication failed: $msg"
                            }
                        }
                    )
                }
            }
        )
    }

    // Alert Dialog
    if (alertTitle != null && alertMessage != null) {
        AlertDialog(
            onDismissRequest = {
                alertTitle = null
                alertMessage = null
            },
            icon = {
                Icon(
                    imageVector = ZarcIcons.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = { Text(alertTitle!!) },
            text = { Text(alertMessage!!) },
            confirmButton = {
                Button(onClick = {
                    alertTitle = null
                    alertMessage = null
                }) {
                    Text("OK")
                }
            }
        )
    }

    // Intercept Back button inside Full Media Viewer
    BackHandler(enabled = activeViewerObject != null) {
        activeViewerObject = null
    }

    // Main Content
    if (unlockedManifest == null) {
        // Unopened / Pre-Unlock State
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            // Top App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = ZarcIcons.Back,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Extract Zarc archive",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Open, inspect, and extract .jpg or .zarc containers",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Select File Card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = ZarcIcons.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = "Select archive (.jpg or .zarc)",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = "Choose an Album.jpg disguised photo or a standalone .zarc file from storage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(18.dp))

                    Button(
                        onClick = {
                            pickArchiveLauncher.launch(
                                arrayOf("image/jpeg", "application/octet-stream", "*/*")
                            )
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(ZarcIcons.Import, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (selectedArchiveUri == null) "Select .jpg / .zarc file" else "Choose another file")
                    }
                }
            }

            // Outer Cover Card (If Selected File has an Outer Cover)
            if (selectedArchiveUri != null) {
                Spacer(Modifier.height(16.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = selectedArchiveName,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${"%.2f".format(selectedArchiveSize / (1024.0 * 1024.0))} MB on disk",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = { showPassphraseDialog = true },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Unlock")
                            }
                        }

                        if (outerCoverBitmap != null) {
                            Spacer(Modifier.height(14.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = ZarcPalette.verified.copy(alpha = 0.16f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = ZarcIcons.Check,
                                        contentDescription = null,
                                        tint = ZarcPalette.verified,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Genuine JPEG Cover Verified (0 Password Required)",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = ZarcPalette.verified
                                    )
                                }
                            }

                            Spacer(Modifier.height(10.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            ) {
                                Image(
                                    bitmap = outerCoverBitmap!!.asImageBitmap(),
                                    contentDescription = "Cover preview",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        // UNLOCKED STATE: Zarc Vault Gallery Type UI
        val manifest = unlockedManifest!!

        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // Decrypted Gallery Top Bar
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            unlockedManifest = null
                            activeViewerObject = null
                            passphrase = ""
                        }) {
                            Icon(
                                imageVector = ZarcIcons.Lock,
                                contentDescription = "Lock",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedArchiveName,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${manifest.objects.size} photos • Decrypted",
                                style = MaterialTheme.typography.bodySmall,
                                color = ZarcPalette.verified
                            )
                        }

                        // Extract All Button
                        Button(
                            onClick = { extractAllLauncher.launch(null) },
                            shape = RoundedCornerShape(12.dp),
                            enabled = !isExtractingAll,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            if (isExtractingAll) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(ZarcIcons.OpenWith, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Extract all")
                            }
                        }
                    }
                }

                // Decrypted Thumbnail Grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(manifest.objects) { _, obj ->
                        var thumbBmp by remember(obj.id) { mutableStateOf(entryBitmapCache[obj.id]) }

                        LaunchedEffect(obj.id) {
                            if (thumbBmp == null && localCachedArchiveFile != null) {
                                val res = repository.extractEntryToBitmap(
                                    archiveFile = localCachedArchiveFile!!,
                                    passphrase = passphrase,
                                    objectId = obj.id,
                                    maxDimension = 320
                                )
                                res.getOrNull()?.let { decoded ->
                                    entryBitmapCache[obj.id] = decoded
                                    thumbBmp = decoded
                                }
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    activeViewerObject = obj
                                    viewerBitmap = entryBitmapCache[obj.id]
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                if (thumbBmp != null) {
                                    Image(
                                        bitmap = thumbBmp!!.asImageBitmap(),
                                        contentDescription = obj.originalName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }

                                // Format badge
                                Surface(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp),
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color.Black.copy(alpha = 0.65f)
                                ) {
                                    Text(
                                        text = obj.originalName.substringAfterLast('.', "JPG").uppercase(Locale.ROOT),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // FULL MEDIA VIEWER OVERLAY
            activeViewerObject?.let { activeObj ->
                val activeIndex = manifest.objects.indexOfFirst { it.id == activeObj.id }.coerceAtLeast(0)

                // High-res load for viewer
                LaunchedEffect(activeObj.id) {
                    if (localCachedArchiveFile != null) {
                        val res = repository.extractEntryToBitmap(
                            archiveFile = localCachedArchiveFile!!,
                            passphrase = passphrase,
                            objectId = activeObj.id,
                            maxDimension = 2048
                        )
                        res.getOrNull()?.let { highRes ->
                            viewerBitmap = highRes
                            entryBitmapCache[activeObj.id] = highRes
                        }
                    }
                }

                DecryptedArchiveViewer(
                    manifest = manifest,
                    currentObject = activeObj,
                    bitmap = viewerBitmap,
                    onSelectObject = { newObj ->
                        activeViewerObject = newObj
                        viewerBitmap = entryBitmapCache[newObj.id]
                    },
                    onClose = { activeViewerObject = null },
                    onShowInfo = { showInfoDialog = true },
                    onExtractSingle = {
                        singleExtractObject = activeObj
                        extractSingleLauncher.launch(activeObj.originalName)
                    }
                )

                // Media Info Modal for Archive Object
                if (showInfoDialog) {
                    ArchiveObjectInfoDialog(
                        obj = activeObj,
                        onDismiss = { showInfoDialog = false }
                    )
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 4. DECRYPTED ARCHIVE VIEWER & MINI-ROLL
// -----------------------------------------------------------------------------

@Composable
private fun DecryptedArchiveViewer(
    manifest: ArchiveManifestData,
    currentObject: ArchiveObject,
    bitmap: Bitmap?,
    onSelectObject: (ArchiveObject) -> Unit,
    onClose: () -> Unit,
    onShowInfo: () -> Unit,
    onExtractSingle: () -> Unit,
) {
    var scale by remember(currentObject.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(currentObject.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(currentObject.id) { mutableFloatStateOf(0f) }
    var controlsVisible by remember { mutableStateOf(true) }

    val currentIndex = manifest.objects.indexOfFirst { it.id == currentObject.id }.coerceAtLeast(0)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Zoomable Image Canvas
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(currentObject.id) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
                .clickable { controlsVisible = !controlsVisible },
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = currentObject.originalName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                )
            } else {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        // Top Controls Overlay
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.Black.copy(alpha = 0.70f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = ZarcIcons.Back,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentObject.originalName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${currentIndex + 1} of ${manifest.objects.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }

                    // Single Photo Extract Button
                    IconButton(onClick = onExtractSingle) {
                        Icon(
                            imageVector = ZarcIcons.OpenWith,
                            contentDescription = "Extract photo",
                            tint = Color.White
                        )
                    }

                    // Info Button
                    IconButton(onClick = onShowInfo) {
                        Icon(
                            imageVector = ZarcIcons.Info,
                            contentDescription = "Photo Details",
                            tint = Color.White
                        )
                    }
                }
            }
        }

        // Bottom Mini-Roll Filmstrip Overlay
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp, start = 12.dp, end = 12.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.88f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp)
                ) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(manifest.objects) { _, obj ->
                            val isSelected = obj.id == currentObject.id
                            Surface(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onSelectObject(obj) },
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                border = BorderStroke(
                                    if (isSelected) 2.5.dp else 1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = ZarcIcons.Roll,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 5. HELPER DIALOGS & COMPONENTS
// -----------------------------------------------------------------------------

@Composable
private fun TechnicalChip(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ArchiveBuildDialog(
    disguiseAsJpeg: Boolean,
    photoCount: Int,
    onDismiss: () -> Unit,
    onBuildConfirmed: (name: String, pass: String) -> Unit,
) {
    var outputName by remember { mutableStateOf("Album") }
    var passphrase by remember { mutableStateOf("") }
    var confirmPassphrase by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                Text(
                    text = "Archive configuration",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Encapsulating $photoCount photos with Argon2id + XChaCha20-Poly1305",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))

                // Output File Name with Uneditable Extension Badge
                Text(
                    text = "Output file name",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = outputName,
                    onValueChange = { outputName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(
                                text = if (disguiseAsJpeg) ".jpg" else ".zarc",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    )
                )

                Spacer(Modifier.height(14.dp))

                // Encryption Passphrase
                Text(
                    text = "Encryption passphrase",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = {
                        passphrase = it
                        validationError = null
                    },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    )
                )

                Spacer(Modifier.height(14.dp))

                // Confirm Passphrase
                Text(
                    text = "Confirm passphrase",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = confirmPassphrase,
                    onValueChange = {
                        confirmPassphrase = it
                        validationError = null
                    },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    )
                )

                validationError?.let { err ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(20.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val cleanName = outputName.trim().ifEmpty { "Album" }
                            if (passphrase.isEmpty()) {
                                validationError = "Passphrase cannot be empty"
                            } else if (passphrase != confirmPassphrase) {
                                validationError = "Passphrases do not match"
                            } else {
                                onBuildConfirmed(cleanName, passphrase)
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text("Build & save archive")
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchivePassphraseDialog(
    fileName: String,
    isUnlocking: Boolean,
    onDismiss: () -> Unit,
    onUnlock: (String) -> Unit,
) {
    var pass by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                Text(
                    text = "Unlock archive",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Enter master passphrase for $fileName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    )
                )

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss, enabled = !isUnlocking) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onUnlock(pass) },
                        enabled = pass.isNotBlank() && !isUnlocking,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isUnlocking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Deriving key...")
                        } else {
                            Text("Unlock")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultPhotoPickerDialog(
    items: List<MediaItem>,
    onDismiss: () -> Unit,
    onConfirmSelection: (List<MediaItem>) -> Unit,
) {
    val selectedIds = remember { mutableStateOf(setOf<String>()) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .height(480.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Select from Vault",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${selectedIds.value.size} photos selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(ZarcIcons.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (items.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No photos in Vault yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(items) { _, item ->
                            val isSelected = selectedIds.value.contains(item.id)
                            Surface(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        selectedIds.value = if (isSelected) {
                                            selectedIds.value - item.id
                                        } else {
                                            selectedIds.value + item.id
                                        }
                                    },
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                border = BorderStroke(
                                    if (isSelected) 2.5.dp else 1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = ZarcIcons.Roll,
                                            contentDescription = null,
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = item.name,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(4.dp)
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = ZarcIcons.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val picked = items.filter { selectedIds.value.contains(it.id) }
                            onConfirmSelection(picked)
                        },
                        enabled = selectedIds.value.isNotEmpty(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Add selected (${selectedIds.value.size})")
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchiveObjectInfoDialog(
    obj: ArchiveObject,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = ZarcIcons.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        title = {
            Text(
                text = "Photo details",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // File Name Surface
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Original file name",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = obj.originalName,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Stats Row (Original Size, Sanitized Size, Format)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "Size",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "%.1f KB".format(obj.originalSize / 1024.0),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "Sanitized",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "%.1f KB".format(obj.sanitizedSize / 1024.0),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "Format",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = obj.originalName.substringAfterLast('.', "JPG").uppercase(Locale.ROOT),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // MIME Encoding
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "MIME encoding",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = obj.mimeType,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Encryption Details
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Container security",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Encrypted with XChaCha20-Poly1305 in 64 KiB authenticated streaming chunks. Outer metadata sanitized.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("Done")
            }
        }
    )
}

private fun queryUriNameAndSize(context: Context, uri: Uri): Pair<String, Long> {
    var name = "photo.jpg"
    var size = 0L
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx != -1) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx != -1) size = cursor.getLong(sizeIdx)
            }
        }
    } catch (_: Exception) {}
    return Pair(name, size)
}

private fun formatDate(timestampMs: Long): String {
    if (timestampMs <= 0L) return "Recent"
    val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
    return sdf.format(Date(timestampMs))
}
