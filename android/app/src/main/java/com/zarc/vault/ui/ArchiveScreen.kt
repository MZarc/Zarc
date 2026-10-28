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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
 * Masterpiece Disguised Photo Archive Screen.
 *
 * Implements:
 * 1. Clean, atmospheric landing hub with two balanced action cards:
 *    - "Create Archive"
 *    - "Extract Archive"
 * 2. Create flow:
 *    - Concise metadata sanitization note
 *    - Multi-photo tray supporting Phone Gallery AND Zarc Vault
 *    - Cover photo selection
 *    - Disguise as JPEG toggle (ON by default)
 *    - Configuration modal with uneditable extension badge (.jpg/.zarc)
 *    - Build & save via SAF CreateDocument
 * 3. Extract flow:
 *    - File picker for .jpg or .zarc
 *    - Instant zero-knowledge unencrypted outer cover preview
 *    - Passphrase prompt with robust error handling
 *    - Vault Gallery UI with 3-column grid, interactive full-screen viewer with mini-roll filmstrip
 *    - Single photo extraction and "Extract all" directory export
 */
@Composable
fun ArchiveScreen(
    onBack: () -> Unit,
    viewModel: GalleryViewModel,
) {
    val context = LocalContext.current
    val repository = remember { ArchiveRepository(context) }
    var currentMode by remember { mutableStateOf(ArchiveScreenMode.LAUNCHER) }

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
// 1. ARCHIVE LAUNCHER SCREEN (CLEAN, ATMOSPHERIC, PURPOSEFUL)
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
                    text = "Disguised Archive",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Covert photo containers hidden in JPEG photos",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Quiet Security Status Tag
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = ZarcIcons.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Argon2id · XChaCha20-Poly1305 · 100% Offline",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Two Balanced Action Cards
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Card 1: Create Archive
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .clickable(onClick = onCreateClick),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.40f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
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
                                text = "Create Archive",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                            ) {
                                Text(
                                    text = "PACK",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 9.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Package photos into a genuine .jpg or .zarc container with master passphrase.",
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
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Card 2: Extract Archive
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
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
                            .size(54.dp)
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Extract Archive",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            ) {
                                Text(
                                    text = "EXTRACT",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 9.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Open Album.jpg or .zarc container, authenticate, browse gallery, and extract media.",
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
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        // Discreet Footer Information
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = ZarcIcons.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(16.dp)
                    .padding(top = 2.dp)
            )
            Text(
                text = "Disguised JPEG photos display their cover normally in any phone gallery or cloud backup without exposing encrypted photos.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                lineHeight = 17.sp
            )
        }
    }
}

// -----------------------------------------------------------------------------
// 2. CREATE ARCHIVE FLOW (HIGH-CRAFT WORKBENCH)
// -----------------------------------------------------------------------------

@Composable
private fun CreateArchiveFlow(
    repository: ArchiveRepository,
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val selectedSources = remember { mutableStateListOf<ArchivePhotoSource>() }
    var coverIndex by remember { mutableIntStateOf(0) }
    var disguiseAsJpeg by remember { mutableStateOf(true) }

    var showVaultPicker by remember { mutableStateOf(false) }
    var showConfigDialog by remember { mutableStateOf(false) }

    var isBuilding by remember { mutableStateOf(false) }
    var buildProgress by remember { mutableStateOf<String?>(null) }
    var builtTempFile by remember { mutableStateOf<File?>(null) }
    var pendingExportName by remember { mutableStateOf("Album.jpg") }

    val phonePhotoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 100)
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                val name = queryFileName(context, uri) ?: "photo_${System.currentTimeMillis()}.jpg"
                val size = queryFileSize(context, uri)
                selectedSources.add(
                    ArchivePhotoSource(
                        displayName = name,
                        sizeBytes = size,
                        uri = uri,
                    )
                )
            }
        }
    }

    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { destUri ->
        if (destUri != null && builtTempFile != null && builtTempFile!!.exists()) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(destUri)?.use { outStream ->
                            builtTempFile!!.inputStream().use { inStream ->
                                inStream.copyTo(outStream)
                            }
                        }
                    }
                    Toast.makeText(context, "Archive saved successfully!", Toast.LENGTH_LONG).show()
                    onBack()
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to save: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    builtTempFile?.delete()
                    builtTempFile = null
                    isBuilding = false
                    buildProgress = null
                }
            }
        } else {
            isBuilding = false
            buildProgress = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 36.dp)
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
                    text = "Create Archive",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Select photos and configure disguise",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Concise Security Notice
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = ZarcIcons.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "EXIF metadata, GPS, and serials are stripped automatically before packaging.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Photos Container Area
        if (selectedSources.isEmpty()) {
            // Empty State Dropzone Card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = ZarcIcons.Import,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = "No photos selected",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = "Add photos from your device gallery or encrypted vault",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                }
            }
        } else {
            // Selected Photos Active Workbench
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Header with count & action chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            val totalBytes = selectedSources.sumOf { it.sizeBytes }
                            Text(
                                text = "${selectedSources.size} photos selected",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = formatBytes(totalBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier.clickable {
                                    phonePhotoPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(ZarcIcons.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text("Phone", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier.clickable { showVaultPicker = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(ZarcIcons.Lock, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text("Vault", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                modifier = Modifier.clickable { selectedSources.clear() }
                            ) {
                                Text(
                                    text = "Clear",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Tap a photo to set as cover image",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))

                    // Horizontal Thumbnails Strip
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(selectedSources) { index, source ->
                            val isCover = (index == coverIndex)
                            val bitmap = remember(source) {
                                loadSourceThumbnail(context, source)
                            }

                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(
                                        width = if (isCover) 2.5.dp else 1.dp,
                                        color = if (isCover) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                    .clickable { coverIndex = index }
                            ) {
                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = source.displayName,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(ZarcIcons.Import, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }

                                if (isCover) {
                                    Surface(
                                        shape = RoundedCornerShape(bottomStart = 8.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.align(Alignment.TopEnd)
                                    ) {
                                        Text(
                                            text = "COVER",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            fontSize = 8.sp,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                // Remove button
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp)
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.65f))
                                        .clickable {
                                            selectedSources.removeAt(index)
                                            if (coverIndex >= selectedSources.size) {
                                                coverIndex = (selectedSources.size - 1).coerceAtLeast(0)
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("✕", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Disguise as JPEG Option (Only shown after photos are added)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (disguiseAsJpeg) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                else MaterialTheme.colorScheme.surfaceContainerHighest
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = ZarcIcons.Import,
                            contentDescription = null,
                            tint = if (disguiseAsJpeg) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Disguise as JPEG photo (.jpg)",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (disguiseAsJpeg) {
                                "Compatible with any gallery or Google Photos. Private payload stays 100% encrypted."
                            } else {
                                "Standard raw .zarc container format."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp
                        )
                    }

                    Spacer(Modifier.width(8.dp))

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

            Spacer(Modifier.height(24.dp))

            // Prominent Continue Button
            Button(
                onClick = { showConfigDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                enabled = !isBuilding
            ) {
                if (isBuilding) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(buildProgress ?: "Building archive...")
                } else {
                    Text(
                        text = "Continue to Passphrase",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }

    // Modal: Vault Photo Picker Dialog
    if (showVaultPicker) {
        VaultPhotoPickerDialog(
            viewModel = viewModel,
            onDismiss = { showVaultPicker = false },
            onAddSelected = { addedVaultSources ->
                selectedSources.addAll(addedVaultSources)
                showVaultPicker = false
            }
        )
    }

    // Modal: Output Filename & Encryption Passphrase Dialog
    if (showConfigDialog) {
        ArchiveConfigDialog(
            photoCount = selectedSources.size,
            disguiseAsJpeg = disguiseAsJpeg,
            onDismiss = { showConfigDialog = false },
            onConfirm = { filename, passphrase ->
                showConfigDialog = false
                isBuilding = true
                buildProgress = "Packaging ${selectedSources.size} photos..."

                scope.launch {
                    val safeFilename = if (disguiseAsJpeg) {
                        if (filename.endsWith(".jpg", ignoreCase = true)) filename else "$filename.jpg"
                    } else {
                        if (filename.endsWith(".zarc", ignoreCase = true)) filename else "$filename.zarc"
                    }
                    pendingExportName = safeFilename

                    val tempOutFile = File(context.cacheDir, "built_archive_${System.currentTimeMillis()}.dat")
                    val result = repository.createArchiveFromSources(
                        outputFile = tempOutFile,
                        sources = selectedSources.toList(),
                        coverIndex = coverIndex,
                        passphrase = passphrase,
                        createAsPolyglotJpeg = disguiseAsJpeg,
                    )

                    result.fold(
                        onSuccess = { tempArchiveFile ->
                            builtTempFile = tempArchiveFile
                            buildProgress = "Select save destination..."
                            saveDocumentLauncher.launch(safeFilename)
                        },
                        onFailure = { error ->
                            isBuilding = false
                            buildProgress = null
                            Toast.makeText(context, "Build failed: ${error.message}", Toast.LENGTH_LONG).show()
                        }
                    )
                }
            }
        )
    }
}

// -----------------------------------------------------------------------------
// 3. VAULT PHOTO PICKER DIALOG (CLEAN, FLUID MULTI-SELECTION)
// -----------------------------------------------------------------------------

@Composable
private fun VaultPhotoPickerDialog(
    viewModel: GalleryViewModel,
    onDismiss: () -> Unit,
    onAddSelected: (List<ArchivePhotoSource>) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val vaultItems = remember(state.roll) {
        state.roll.filter { !it.isDeleted && !it.isVideo }
    }
    val selectedItemIds = remember { mutableStateListOf<String>() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 360.dp, max = 560.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Select from Vault",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${selectedItemIds.size} photos selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    TextButton(
                        onClick = {
                            if (selectedItemIds.size == vaultItems.size) {
                                selectedItemIds.clear()
                            } else {
                                selectedItemIds.clear()
                                selectedItemIds.addAll(vaultItems.map { it.id })
                            }
                        }
                    ) {
                        Text(
                            text = if (selectedItemIds.size == vaultItems.size) "Deselect all" else "Select all",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Photos Grid
                if (vaultItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Your Zarc Vault has no photos.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(vaultItems) { _, item ->
                            val isSelected = selectedItemIds.contains(item.id)

                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(
                                        width = if (isSelected) 2.5.dp else 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .clickable {
                                        if (isSelected) {
                                            selectedItemIds.remove(item.id)
                                        } else {
                                            selectedItemIds.add(item.id)
                                        }
                                    }
                            ) {
                                val thumbBmp = remember(item.id) {
                                    val decryptedFile = viewModel.repository.decrypt(item, item.isPrivate)
                                    if (decryptedFile.exists()) {
                                        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                                        BitmapFactory.decodeFile(decryptedFile.absolutePath, opts)
                                    } else null
                                }

                                if (thumbBmp != null) {
                                    Image(
                                        bitmap = thumbBmp.asImageBitmap(),
                                        contentDescription = item.name,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(ZarcIcons.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }

                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                                    )
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("✓", color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .align(Alignment.BottomCenter),
                                    color = Color.Black.copy(alpha = 0.55f)
                                ) {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Bottom Dialog Actions
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
                            val selectedItems = vaultItems.filter { selectedItemIds.contains(it.id) }
                            val sources = selectedItems.mapNotNull { item ->
                                val decryptedFile = viewModel.repository.decrypt(item, item.isPrivate)
                                if (decryptedFile.exists()) {
                                    ArchivePhotoSource(
                                        displayName = item.name,
                                        sizeBytes = item.size,
                                        localFile = decryptedFile,
                                    )
                                } else null
                            }
                            onAddSelected(sources)
                        },
                        shape = RoundedCornerShape(12.dp),
                        enabled = selectedItemIds.isNotEmpty()
                    ) {
                        Text("Add selected (${selectedItemIds.size})")
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 4. ARCHIVE CONFIGURATION DIALOG (POPUP WITH UNEDITABLE EXTENSION BADGE)
// -----------------------------------------------------------------------------

@Composable
private fun ArchiveConfigDialog(
    photoCount: Int,
    disguiseAsJpeg: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (filename: String, passphrase: String) -> Unit,
) {
    var filename by remember { mutableStateOf("Album") }
    var passphrase by remember { mutableStateOf("") }
    var confirmPassphrase by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    val extension = if (disguiseAsJpeg) ".jpg" else ".zarc"
    val isValid = filename.isNotBlank() && passphrase.length >= 4 && passphrase == confirmPassphrase

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                Text(
                    text = "Archive configuration",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Encapsulating $photoCount photos with Argon2id + XChaCha20-Poly1305",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(18.dp))

                // Output File Name with Uneditable Extension Badge
                Text(
                    text = "Output file name",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))

                OutlinedTextField(
                    value = filename,
                    onValueChange = { filename = it.replace("/", "").replace("\\", "").trim() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true,
                    trailingIcon = {
                        // Uneditable extension badge pill
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(
                                text = extension,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontSize = 11.sp
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
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))

                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true,
                    placeholder = { Text("At least 4 characters") },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = "Toggle password visibility",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    )
                )

                Spacer(Modifier.height(12.dp))

                // Confirm Passphrase
                Text(
                    text = "Confirm passphrase",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))

                OutlinedTextField(
                    value = confirmPassphrase,
                    onValueChange = { confirmPassphrase = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true,
                    placeholder = { Text("Re-enter passphrase") },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = confirmPassphrase.isNotEmpty() && confirmPassphrase != passphrase,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    )
                )

                if (confirmPassphrase.isNotEmpty() && confirmPassphrase != passphrase) {
                    Text(
                        text = "Passphrases do not match",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                    )
                }

                Spacer(Modifier.height(22.dp))

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
                        onClick = { onConfirm(filename, passphrase) },
                        shape = RoundedCornerShape(12.dp),
                        enabled = isValid
                    ) {
                        Text("Build & save archive")
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 5. EXTRACT ARCHIVE FLOW (ROBUST DECRYPTION & VAULT GALLERY)
// -----------------------------------------------------------------------------

@Composable
private fun ExtractArchiveFlow(
    repository: ArchiveRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var targetUri by remember { mutableStateOf<Uri?>(null) }
    var targetFile by remember { mutableStateOf<File?>(null) }
    var targetFileName by remember { mutableStateOf<String?>(null) }
    var targetFileSize by remember { mutableStateOf<Long?>(null) }

    var isPolyglotJpeg by remember { mutableStateOf(false) }
    var genuineCoverBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var passphrase by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isAuthenticating by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }

    var decryptedManifest by remember { mutableStateOf<ArchiveManifestData?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            targetUri = uri
            val name = queryFileName(context, uri) ?: "Archive"
            targetFileName = name
            targetFileSize = queryFileSize(context, uri)
            authError = null
            genuineCoverBitmap = null

            scope.launch {
                withContext(Dispatchers.IO) {
                    val localCache = File(context.cacheDir, "temp_extract_${System.currentTimeMillis()}.${if (name.endsWith(".jpg", ignoreCase = true)) "jpg" else "zarc"}")
                    context.contentResolver.openInputStream(uri)?.use { inStream ->
                        localCache.outputStream().use { outStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                    targetFile = localCache

                    // Inspect if this is a genuine polyglot JPEG
                    val coverResult = repository.readOuterCover(localCache)
                    coverResult.onSuccess { bytes ->
                        if (bytes != null && bytes.isNotEmpty()) {
                            isPolyglotJpeg = true
                            genuineCoverBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } else {
                            isPolyglotJpeg = false
                        }
                    }
                }
            }
        }
    }

    // When manifest is unlocked, display full Zarc Vault Gallery UI
    if (decryptedManifest != null && targetFile != null) {
        DecryptedGalleryViewer(
            manifest = decryptedManifest!!,
            archiveFile = targetFile!!,
            repository = repository,
            passphrase = passphrase,
            onClose = {
                decryptedManifest = null
                passphrase = ""
            }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 36.dp)
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

        Spacer(Modifier.height(14.dp))

        // Step 1: File Selection
        if (targetUri == null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = ZarcIcons.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = "Select archive (.jpg or .zarc)",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(6.dp))

                    Text(
                        text = "Choose an Album.jpg disguised photo or a standalone .zarc file from storage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp
                    )

                    Spacer(Modifier.height(20.dp))

                    Button(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(ZarcIcons.Import, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Select .jpg / .zarc file")
                    }
                }
            }
        } else {
            // Selected File Card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = targetFileName ?: "Archive file",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${formatBytes(targetFileSize ?: 0)} · ${if (isPolyglotJpeg) "Disguised JPEG container" else "Native ZARC file"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        TextButton(onClick = {
                            targetUri = null
                            targetFile?.delete()
                            targetFile = null
                            authError = null
                            genuineCoverBitmap = null
                        }) {
                            Text("Change")
                        }
                    }

                    // Zero-Knowledge Outer Cover Preview (Visible without password!)
                    if (genuineCoverBitmap != null) {
                        Spacer(Modifier.height(14.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                        ) {
                            Image(
                                bitmap = genuineCoverBitmap!!.asImageBitmap(),
                                contentDescription = "Outer cover",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )

                            Surface(
                                shape = RoundedCornerShape(bottomStart = 8.dp),
                                color = ZarcPalette.verified.copy(alpha = 0.90f),
                                modifier = Modifier.align(Alignment.TopEnd)
                            ) {
                                Text(
                                    text = "GENUINE COVER PREVIEW (UNENCRYPTED)",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                                    color = Color.Black,
                                    fontSize = 8.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // Step 2: Passphrase Authentication Card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                ) {
                    Text(
                        text = "Master passphrase",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Enter encryption key to unlock manifest and photos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(14.dp))

                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = {
                            passphrase = it
                            authError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        placeholder = { Text("Enter passphrase") },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = "Toggle password visibility",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        )
                    )

                    // Error Handling Feedback
                    if (authError != null) {
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "✕",
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = authError!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    Button(
                        onClick = {
                            isAuthenticating = true
                            authError = null

                            scope.launch {
                                val result = repository.readManifest(targetFile!!, passphrase)
                                result.fold(
                                    onSuccess = { manifest ->
                                        isAuthenticating = false
                                        decryptedManifest = manifest
                                    },
                                    onFailure = { error ->
                                        isAuthenticating = false
                                        val msg = error.message ?: "Authentication failed"
                                        authError = when {
                                            msg.contains("authentication tag mismatch", ignoreCase = true) ||
                                            msg.contains("crypto error", ignoreCase = true) -> "Incorrect passphrase or tampered archive ciphertext."
                                            msg.contains("magic", ignoreCase = true) ||
                                            msg.contains("no zarc container", ignoreCase = true) -> "Not a valid Zarc container file."
                                            msg.contains("truncated", ignoreCase = true) ||
                                            msg.contains("unexpected eof", ignoreCase = true) -> "Archive file is truncated or incomplete."
                                            else -> "Authentication failed: $msg"
                                        }
                                    }
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        enabled = passphrase.isNotBlank() && !isAuthenticating && targetFile != null
                    ) {
                        if (isAuthenticating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Decrypting manifest...")
                        } else {
                            Text("Unlock & decrypt archive", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 6. DECRYPTED ZARC VAULT GALLERY UI (MULTI-COLUMN GRID & THUMBNAILS)
// -----------------------------------------------------------------------------

@Composable
private fun DecryptedGalleryViewer(
    manifest: ArchiveManifestData,
    archiveFile: File,
    repository: ArchiveRepository,
    passphrase: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeViewerIndex by remember { mutableStateOf<Int?>(null) }
    var isExtractingAll by remember { mutableStateOf(false) }

    // Launcher for "Extract All" (User picks destination directory via SAF OpenDocumentTree)
    val extractAllTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            isExtractingAll = true
            scope.launch {
                val targetDir = DocumentFile.fromTreeUri(context, treeUri)
                if (targetDir != null && targetDir.canWrite()) {
                    var successCount = 0
                    withContext(Dispatchers.IO) {
                        val tempExtractDir = File(context.cacheDir, "all_extracted_${System.currentTimeMillis()}")
                        val allRes = repository.extractAllEntries(archiveFile, passphrase, tempExtractDir)
                        allRes.onSuccess { extractedFiles ->
                            for (f in extractedFiles) {
                                val mime = if (f.name.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"
                                val newDoc = targetDir.createFile(mime, f.name) ?: continue
                                context.contentResolver.openOutputStream(newDoc.uri)?.use { out ->
                                    f.inputStream().use { inStream ->
                                        inStream.copyTo(out)
                                    }
                                    successCount++
                                }
                            }
                        }
                        tempExtractDir.deleteRecursively()
                    }
                    isExtractingAll = false
                    Toast.makeText(context, "Successfully extracted $successCount photos!", Toast.LENGTH_LONG).show()
                } else {
                    isExtractingAll = false
                    Toast.makeText(context, "Cannot write to selected directory", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Gallery Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = ZarcIcons.Back,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Decrypted vault photos",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                val totalBytes = manifest.objects.sumOf { it.sanitizedSize }
                Text(
                    text = "${manifest.objects.size} photos · ${formatBytes(totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Extract All Action Button
            Button(
                onClick = { extractAllTreeLauncher.launch(null) },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                enabled = !isExtractingAll
            ) {
                if (isExtractingAll) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Extract all", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // 3-Column Decrypted Thumbnail Grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(manifest.objects) { index, obj ->
                var thumbBitmap by remember(obj.id) { mutableStateOf<Bitmap?>(null) }

                LaunchedEffect(obj.id) {
                    val result = repository.extractEntryToBitmap(archiveFile, passphrase, obj.id, maxDimension = 320)
                    result.onSuccess { bmp -> thumbBitmap = bmp }
                }

                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable { activeViewerIndex = index }
                ) {
                    if (thumbBitmap != null) {
                        Image(
                            bitmap = thumbBitmap!!.asImageBitmap(),
                            contentDescription = obj.originalName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Bottom file info badge
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter),
                        color = Color.Black.copy(alpha = 0.60f)
                    ) {
                        Text(
                            text = obj.originalName,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            fontSize = 8.sp
                        )
                    }
                }
            }
        }
    }

    // Interactive Full-Screen Media Viewer with Mini-Roll Filmstrip
    if (activeViewerIndex != null) {
        DecryptedPhotoViewer(
            manifest = manifest,
            initialIndex = activeViewerIndex!!,
            archiveFile = archiveFile,
            repository = repository,
            passphrase = passphrase,
            onClose = { activeViewerIndex = null }
        )
    }
}

// -----------------------------------------------------------------------------
// 7. FULL-SCREEN MEDIA VIEWER WITH MINI-ROLL FILMSTRIP & ZOOM/PAN
// -----------------------------------------------------------------------------

@Composable
private fun DecryptedPhotoViewer(
    manifest: ArchiveManifestData,
    initialIndex: Int,
    archiveFile: File,
    repository: ArchiveRepository,
    passphrase: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentIndex by remember { mutableIntStateOf(initialIndex) }
    val currentObject = manifest.objects.getOrNull(currentIndex) ?: return

    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoadingHighRes by remember { mutableStateOf(true) }

    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    var showInfoModal by remember { mutableStateOf(false) }

    // Load full-resolution image on index change
    LaunchedEffect(currentIndex) {
        isLoadingHighRes = true
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        val result = repository.extractEntryToBitmap(archiveFile, passphrase, currentObject.id, maxDimension = 2048)
        result.fold(
            onSuccess = { bmp ->
                currentBitmap = bmp
                isLoadingHighRes = false
            },
            onFailure = {
                isLoadingHighRes = false
            }
        )
    }

    // Single Photo Extract Launcher
    val saveSingleDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/jpeg")
    ) { destUri ->
        if (destUri != null) {
            scope.launch {
                val tempOut = File(context.cacheDir, "single_export_${System.currentTimeMillis()}.tmp")
                val res = repository.extractSingleEntry(archiveFile, passphrase, currentObject.id, tempOut)
                res.onSuccess { extractedFile ->
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(destUri)?.use { out ->
                            extractedFile.inputStream().use { inStream ->
                                inStream.copyTo(out)
                            }
                        }
                    }
                    tempOut.delete()
                    Toast.makeText(context, "Saved ${currentObject.originalName} successfully!", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    tempOut.delete()
                    Toast.makeText(context, "Failed to extract: ${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Zoomable & Pannable High-Res Photo Viewport
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(currentIndex) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (currentBitmap != null) {
                Image(
                    bitmap = currentBitmap!!.asImageBitmap(),
                    contentDescription = currentObject.originalName,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        ),
                    contentScale = ContentScale.Fit
                )
            } else if (isLoadingHighRes) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        // Top Controls Bar (Dismiss, Index, Info)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Text("✕", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = currentObject.originalName,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${currentIndex + 1} of ${manifest.objects.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }

            IconButton(onClick = { showInfoModal = true }) {
                Icon(
                    imageVector = ZarcIcons.Settings,
                    contentDescription = "Info",
                    tint = Color.White
                )
            }
        }

        // Bottom Controls: Single Photo Extract & Mini-Roll Filmstrip
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(vertical = 10.dp)
        ) {
            // Action button: Extract this photo
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatBytes(currentObject.originalSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f)
                )

                Button(
                    onClick = { saveSingleDocLauncher.launch(currentObject.originalName) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(ZarcIcons.Import, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Extract photo", style = MaterialTheme.typography.labelMedium)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Mini-Roll Filmstrip (Interactive horizontal thumbnail reel)
            val listState = rememberLazyListState()

            LaunchedEffect(currentIndex) {
                listState.animateScrollToItem(currentIndex)
            }

            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(manifest.objects) { index, obj ->
                    val isSelected = (index == currentIndex)
                    var miniThumb by remember(obj.id) { mutableStateOf<Bitmap?>(null) }

                    LaunchedEffect(obj.id) {
                        val res = repository.extractEntryToBitmap(archiveFile, passphrase, obj.id, maxDimension = 120)
                        res.onSuccess { bmp -> miniThumb = bmp }
                    }

                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                width = if (isSelected) 2.5.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { currentIndex = index }
                    ) {
                        if (miniThumb != null) {
                            Image(
                                bitmap = miniThumb!!.asImageBitmap(),
                                contentDescription = obj.originalName,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.DarkGray)
                            )
                        }
                    }
                }
            }
        }
    }

    // Photo Info Inspector Modal
    if (showInfoModal) {
        Dialog(onDismissRequest = { showInfoModal = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Text(
                        text = "Photo details",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(14.dp))

                    DetailRow("File name", currentObject.originalName)
                    DetailRow("MIME type", currentObject.mimeType)
                    DetailRow("Original size", formatBytes(currentObject.originalSize))
                    DetailRow("Sanitized size", formatBytes(currentObject.sanitizedSize))
                    DetailRow(
                        "Dimensions",
                        if (currentBitmap != null) "${currentBitmap!!.width} × ${currentBitmap!!.height}" else "Unknown"
                    )
                    DetailRow("Sanitized EXIF", "Stripped during packaging")
                    DetailRow("Cipher", "XChaCha20-Poly1305 AEAD")

                    Spacer(Modifier.height(18.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showInfoModal = false }) {
                            Text("Done")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// -----------------------------------------------------------------------------
// HELPER UTILITIES
// -----------------------------------------------------------------------------

private fun queryFileName(context: Context, uri: Uri): String? {
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (col != -1 && cursor.moveToFirst()) {
                return cursor.getString(col)
            }
        }
    }
    return uri.lastPathSegment
}

private fun queryFileSize(context: Context, uri: Uri): Long {
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val col = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (col != -1 && cursor.moveToFirst()) {
                return cursor.getLong(col)
            }
        }
    }
    return 0L
}

private fun loadSourceThumbnail(context: Context, source: ArchivePhotoSource): Bitmap? {
    return try {
        if (source.localFile != null && source.localFile.exists()) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeFile(source.localFile.absolutePath, opts)
        } else if (source.uri != null) {
            context.contentResolver.openInputStream(source.uri)?.use { stream ->
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeStream(stream, null, opts)
            }
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}
