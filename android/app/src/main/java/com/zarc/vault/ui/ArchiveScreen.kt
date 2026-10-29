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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.window.DialogProperties
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zarc.vault.archive.ArchiveManifestData
import com.zarc.vault.archive.ArchiveObject
import com.zarc.vault.archive.ArchivePhotoSource
import com.zarc.vault.archive.ArchiveRepository
import com.zarc.vault.gallery.MediaItem
import com.zarc.vault.ui.theme.ZarcPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Maximum safe number of photos per single archive container.
 * Prevents mobile device flash exhaustion and OOM errors during packaging.
 */
const val MAX_ARCHIVE_PHOTOS = 250

/**
 * Screen modes for Zarc Disguised Archive.
 */
enum class ArchiveScreenMode {
    LAUNCHER,
    CREATE,
    EXTRACT,
}

/**
 * Zarc Disguised Photo Archive Screen with Sticky Headers and Hallmark Aesthetics.
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
// 1. ARCHIVE LAUNCHER SCREEN (STICKY HEADER)
// -----------------------------------------------------------------------------

@Composable
private fun ArchiveLauncherScreen(
    onBack: () -> Unit,
    onCreateClick: () -> Unit,
    onExtractClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Sticky Top App Bar (starts immediately below notch, no extra statusBarsPadding)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 8.dp, vertical = 6.dp),
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

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        // Scrollable Body Content
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            Spacer(Modifier.height(14.dp))

            // Security Status Tag
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
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(ZarcPalette.verified)
                        )
                        Text(
                            text = "Native libsodium engine · 100% offline",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            // Main Action Hub Cards
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Card 1: Create Archive
                Surface(
                    onClick = onCreateClick,
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    tonalElevation = 1.dp
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
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = ZarcIcons.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp)
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
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
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
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Card 2: Extract Archive
                Surface(
                    onClick = onExtractClick,
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    tonalElevation = 1.dp
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
                                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = ZarcIcons.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(26.dp)
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
                                text = "Open disguised photo (.jpg) or .zarc container, authenticate, browse gallery, and extract media.",
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
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Disguised JPEG photos display their cover normally in any phone gallery or cloud backup without exposing encrypted photos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    lineHeight = 16.sp
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 2. CREATE ARCHIVE FLOW (STICKY HEADER & SAFE LIMITS)
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
    var externalCoverSource by remember { mutableStateOf<ArchivePhotoSource?>(null) }
    var disguiseAsJpeg by remember { mutableStateOf(true) }

    var showVaultPicker by remember { mutableStateOf(false) }
    var showConfigDialog by remember { mutableStateOf(false) }
    var previewingSource by remember { mutableStateOf<ArchivePhotoSource?>(null) }

    var isBuilding by remember { mutableStateOf(false) }
    var buildProgress by remember { mutableStateOf<String?>(null) }
    var builtTempFile by remember { mutableStateOf<File?>(null) }
    var pendingExportName by remember { mutableStateOf("") }

    val phonePhotoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = MAX_ARCHIVE_PHOTOS)
    ) { uris ->
        if (uris.isNotEmpty()) {
            val remaining = (MAX_ARCHIVE_PHOTOS - selectedSources.size).coerceAtLeast(0)
            if (remaining <= 0) {
                Toast.makeText(context, "Archive limit reached: max $MAX_ARCHIVE_PHOTOS photos", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            val toAdd = uris.take(remaining)
            toAdd.forEach { uri ->
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
            if (uris.size > remaining) {
                Toast.makeText(context, "Added $remaining photos (container limit is $MAX_ARCHIVE_PHOTOS photos)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Picker for custom external cover photo
    val externalCoverPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val name = queryFileName(context, uri) ?: "cover_${System.currentTimeMillis()}.jpg"
            val size = queryFileSize(context, uri)
            externalCoverSource = ArchivePhotoSource(
                displayName = name,
                sizeBytes = size,
                uri = uri
            )
            Toast.makeText(context, "External cover selected", Toast.LENGTH_SHORT).show()
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
        modifier = Modifier.fillMaxSize()
    ) {
        // Sticky Top App Bar (starts immediately below notch, no extra statusBarsPadding)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 8.dp, vertical = 6.dp),
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

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        // Scrollable Body Content
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 32.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            // Concise Metadata Sanitization Notice
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
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "EXIF metadata, GPS, and serials are stripped automatically before packaging.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // Photos Selection Section
            if (selectedSources.isEmpty()) {
                // Empty Dropzone State
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
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
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
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Add up to $MAX_ARCHIVE_PHOTOS photos from device or vault",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(20.dp))

                        // Responsive action buttons with zero clipping
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
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(ZarcIcons.Import, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "From Phone",
                                    maxLines = 1,
                                    softWrap = false,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }

                            OutlinedButton(
                                onClick = { showVaultPicker = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Icon(ZarcIcons.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "From Vault",
                                    maxLines = 1,
                                    softWrap = false,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                        }
                    }
                }
            } else {
                // Selected Photos Container
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
                        // Header with count & Clear all button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                val totalBytes = selectedSources.sumOf { it.sizeBytes }
                                Text(
                                    text = "${selectedSources.size} / $MAX_ARCHIVE_PHOTOS photos",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = formatBytes(totalBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            TextButton(
                                onClick = { selectedSources.clear() },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(
                                    imageVector = ZarcIcons.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Clear all", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Dedicated, spacious Add buttons with custom padding (zero text cutoff!)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    phonePhotoPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Icon(ZarcIcons.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "From Phone",
                                    maxLines = 1,
                                    softWrap = false,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            OutlinedButton(
                                onClick = { showVaultPicker = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Icon(ZarcIcons.Lock, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "From Vault",
                                    maxLines = 1,
                                    softWrap = false,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "Tap a photo to preview full-screen or toggle cover",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))

                        // Horizontal Thumbnails Strip
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            itemsIndexed(selectedSources) { index, source ->
                                val isCover = (index == coverIndex && externalCoverSource == null)
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
                                        .clickable {
                                            previewingSource = source
                                        }
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

                                    // Quick remove button
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

                // External Cover Photo Selector Card
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(18.dp),
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
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = ZarcIcons.Image,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Cover Photo",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (externalCoverSource != null) "Using custom external photo as outer cover"
                                           else "Using selected photo #${coverIndex + 1} as outer cover",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (externalCoverSource != null) {
                                TextButton(onClick = { externalCoverSource = null }) {
                                    Text("Reset", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        externalCoverPicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Choose External", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        // If external cover is chosen, display preview thumbnail with big preview tap
                        if (externalCoverSource != null) {
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .clickable { previewingSource = externalCoverSource }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                val extBmp = remember(externalCoverSource) {
                                    loadSourceThumbnail(context, externalCoverSource!!)
                                }
                                if (extBmp != null) {
                                    Image(
                                        bitmap = extBmp.asImageBitmap(),
                                        contentDescription = "External Cover",
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                    ) {
                                        Text(
                                            text = "EXTERNAL COVER",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 8.sp,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Text(
                                        text = externalCoverSource?.displayName ?: "cover.jpg",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = "Tap to preview",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Disguise as JPEG Option
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
                                text = "Compatible with any gallery or Google Photos. Private payload stays 100% encrypted.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 15.sp
                            )
                        }

                        Spacer(Modifier.width(10.dp))

                        Switch(
                            checked = disguiseAsJpeg,
                            onCheckedChange = { disguiseAsJpeg = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            )
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                // Bottom Continue Action Button
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
                        Icon(imageVector = ZarcIcons.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Continue to Passphrase", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
    }

    // Vault Multi-Photo Picker Dialog with Safe Selection Limit & Pagination
    if (showVaultPicker) {
        VaultPhotoPickerDialog(
            viewModel = viewModel,
            currentCount = selectedSources.size,
            onDismiss = { showVaultPicker = false },
            onAddSelected = { addedSources ->
                val remaining = (MAX_ARCHIVE_PHOTOS - selectedSources.size).coerceAtLeast(0)
                selectedSources.addAll(addedSources.take(remaining))
                showVaultPicker = false
            }
        )
    }

    // Big Photo Preview Modal (With tap-to-hide chrome & fixed non-truncated buttons)
    if (previewingSource != null) {
        PhotoPreviewModal(
            source = previewingSource!!,
            isCover = (selectedSources.indexOf(previewingSource) == coverIndex && externalCoverSource == null) || (previewingSource == externalCoverSource),
            isExternal = (previewingSource == externalCoverSource),
            onSetAsCover = {
                val idx = selectedSources.indexOf(previewingSource)
                if (idx >= 0) {
                    coverIndex = idx
                    externalCoverSource = null
                }
                previewingSource = null
            },
            onRemove = {
                if (previewingSource == externalCoverSource) {
                    externalCoverSource = null
                } else {
                    selectedSources.remove(previewingSource)
                    if (coverIndex >= selectedSources.size) {
                        coverIndex = (selectedSources.size - 1).coerceAtLeast(0)
                    }
                }
                previewingSource = null
            },
            onClose = { previewingSource = null }
        )
    }

    // Archive Configuration Dialog (With automatic keyboard bringing-into-view)
    if (showConfigDialog) {
        ArchiveConfigDialog(
            photoCount = selectedSources.size,
            disguiseAsJpeg = disguiseAsJpeg,
            onDismiss = { showConfigDialog = false },
            onConfirm = { filename, passphrase ->
                showConfigDialog = false
                isBuilding = true
                buildProgress = "Packaging and encrypting..."

                val ext = if (disguiseAsJpeg) ".jpg" else ".zarc"
                val cleanBase = filename.trim().ifEmpty { "archive_${System.currentTimeMillis()}" }
                val finalName = if (cleanBase.endsWith(ext, ignoreCase = true)) cleanBase else "$cleanBase$ext"
                pendingExportName = finalName

                scope.launch {
                    val tempOut = File(context.cacheDir, "built_${System.currentTimeMillis()}$ext")
                    val result = repository.createArchiveFromSources(
                        outputFile = tempOut,
                        sources = selectedSources.toList(),
                        coverIndex = coverIndex,
                        passphrase = passphrase,
                        createAsPolyglotJpeg = disguiseAsJpeg,
                        externalCoverSource = externalCoverSource
                    )

                    result.fold(
                        onSuccess = { createdFile ->
                            builtTempFile = createdFile
                            buildProgress = "Container built! Choose destination..."
                            saveDocumentLauncher.launch(finalName)
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
// 3. VAULT PHOTO PICKER DIALOG (BOUNDED TO MAX 250 PHOTOS FOR MEMORY SAFETY)
// -----------------------------------------------------------------------------

@Composable
private fun VaultPhotoPickerDialog(
    viewModel: GalleryViewModel,
    currentCount: Int,
    onDismiss: () -> Unit,
    onAddSelected: (List<ArchivePhotoSource>) -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val vaultItems = remember(state.roll) {
        state.roll.filter { !it.isDeleted && !it.isVideo }
    }
    val selectedItemIds = remember { mutableStateListOf<String>() }

    val remainingQuota = (MAX_ARCHIVE_PHOTOS - currentCount).coerceAtLeast(0)

    // Pagination: 24 items per page
    val pageSize = 24
    var pageCount by remember { mutableIntStateOf(1) }
    val visibleItems = remember(vaultItems, pageCount) {
        vaultItems.take(pageCount * pageSize)
    }
    val gridState = rememberLazyGridState()

    // Infinite scroll listener
    LaunchedEffect(gridState, vaultItems.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastIdx ->
                if (lastIdx != null && lastIdx >= visibleItems.size - 6 && visibleItems.size < vaultItems.size) {
                    pageCount += 1
                }
            }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 400.dp, max = 580.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header with Quota Indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Select from Vault",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${selectedItemIds.size} selected · Limit $remainingQuota photos",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selectedItemIds.size >= remainingQuota && remainingQuota > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    TextButton(
                        onClick = {
                            if (selectedItemIds.size >= remainingQuota) {
                                selectedItemIds.clear()
                            } else {
                                selectedItemIds.clear()
                                val allowed = vaultItems.take(remainingQuota)
                                selectedItemIds.addAll(allowed.map { it.id })
                            }
                        }
                    ) {
                        Text(
                            text = if (selectedItemIds.size >= remainingQuota) "Deselect" else "Select max ($remainingQuota)",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Photos Grid with Infinite Scroll
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
                        state = gridState,
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(visibleItems) { _, item ->
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
                                            if (selectedItemIds.size >= remainingQuota) {
                                                Toast.makeText(context, "Limit reached: max $MAX_ARCHIVE_PHOTOS photos total", Toast.LENGTH_SHORT).show()
                                            } else {
                                                selectedItemIds.add(item.id)
                                            }
                                        }
                                    }
                            ) {
                                MediaThumbnail(
                                    repository = viewModel.repository,
                                    item = item,
                                    isPrivate = item.isPrivate,
                                    modifier = Modifier.fillMaxSize()
                                )

                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
                                        contentAlignment = Alignment.TopEnd
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(bottomStart = 8.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                        ) {
                                            Text(
                                                text = "✓",
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }

                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .align(Alignment.BottomCenter),
                                    color = Color.Black.copy(alpha = 0.60f)
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

                        // Load More trigger item
                        if (visibleItems.size < vaultItems.size) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                        .clickable { pageCount += 1 },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "+ More\n(${vaultItems.size - visibleItems.size})",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Footer Actions
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
                                        sizeBytes = decryptedFile.length(),
                                        localFile = decryptedFile,
                                    )
                                } else null
                            }
                            onAddSelected(sources)
                        },
                        enabled = selectedItemIds.isNotEmpty(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Add selected (${selectedItemIds.size})")
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 4. BIG PHOTO PREVIEW MODAL (TAP TO HIDE CHROME & NON-TRUNCATED BUTTONS)
// -----------------------------------------------------------------------------

@Composable
private fun PhotoPreviewModal(
    source: ArchivePhotoSource,
    isCover: Boolean,
    isExternal: Boolean,
    onSetAsCover: () -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val fullBmp = remember(source) {
        loadSourceBitmap(context, source, maxDimension = 2048)
    }
    var chromeVisible by remember { mutableStateOf(true) }

    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { chromeVisible = !chromeVisible },
                        onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2.5f
                            offsetX = 0f
                            offsetY = 0f
                        }
                    )
                }
        ) {
            // High-Res Image View with zoom & pan
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
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
                if (fullBmp != null) {
                    Image(
                        bitmap = fullBmp.asImageBitmap(),
                        contentDescription = source.displayName,
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
                } else {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }

            // Top Bar (Fades out when chromeVisible is false!)
            AnimatedVisibility(
                visible = chromeVisible,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(imageVector = ZarcIcons.Back, contentDescription = "Close", tint = Color.White)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = source.displayName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = formatBytes(source.sizeBytes) + if (isCover) " · Current Cover" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.75f)
                        )
                    }
                }
            }

            // Bottom Actions Bar (Fades out when chromeVisible is false; zero truncation!)
            AnimatedVisibility(
                visible = chromeVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.90f))))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isExternal) {
                        Button(
                            onClick = onSetAsCover,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isCover) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primary,
                                contentColor = if (isCover) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text(
                                text = if (isCover) "✓ Current Cover" else "★ Set as Cover",
                                maxLines = 1,
                                softWrap = false,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onRemove,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(ZarcIcons.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Remove",
                            maxLines = 1,
                            softWrap = false,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 5. ARCHIVE CONFIGURATION DIALOG (AUTOMATIC ABOVE KEYBOARD INSETS)
// -----------------------------------------------------------------------------

@Composable
private fun ArchiveConfigDialog(
    photoCount: Int,
    disguiseAsJpeg: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (filename: String, passphrase: String) -> Unit,
) {
    var filename by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var confirmPassphrase by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    val extension = if (disguiseAsJpeg) ".jpg" else ".zarc"
    val isValid = filename.isNotBlank() && passphrase.length >= 4 && passphrase == confirmPassphrase

    val scope = rememberCoroutineScope()
    val nameRequester = remember { BringIntoViewRequester() }
    val passRequester = remember { BringIntoViewRequester() }
    val confirmRequester = remember { BringIntoViewRequester() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState()),
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

                    // Output File Name
                    Text(
                        text = "Output file name",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))

                    OutlinedTextField(
                        value = filename,
                        onValueChange = { filename = it.replace("/", "").replace("\\", "").trim() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .bringIntoViewRequester(nameRequester)
                            .onFocusChanged {
                                if (it.isFocused) {
                                    scope.launch { delay(120); nameRequester.bringIntoView() }
                                }
                            },
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        placeholder = {
                            Text(
                                text = "e.g. Vacation_2026",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        },
                        trailingIcon = {
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .bringIntoViewRequester(passRequester)
                            .onFocusChanged {
                                if (it.isFocused) {
                                    scope.launch { delay(120); passRequester.bringIntoView() }
                                }
                            },
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .bringIntoViewRequester(confirmRequester)
                            .onFocusChanged {
                                if (it.isFocused) {
                                    scope.launch { delay(120); confirmRequester.bringIntoView() }
                                }
                            },
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

                    Spacer(Modifier.height(24.dp))

                    // Full-Width Action Button
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onConfirm(filename.trim(), passphrase) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            enabled = isValid
                        ) {
                            Icon(
                                imageVector = ZarcIcons.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Build & Save Container",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }

                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                        ) {
                            Text(
                                text = "Cancel",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 6. EXTRACT ARCHIVE FLOW (STICKY HEADER & AUTOMATIC KEYBOARD INSET)
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
    var showCoverPreviewModal by remember { mutableStateOf(false) }

    var passphrase by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isAuthenticating by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }

    var decryptedManifest by remember { mutableStateOf<ArchiveManifestData?>(null) }

    val passRequester = remember { BringIntoViewRequester() }

    // SAF Picker for selecting archive (.jpg or .zarc)
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            targetUri = uri
            val name = queryFileName(context, uri) ?: "Archive.jpg"
            val size = queryFileSize(context, uri)
            targetFileName = name
            targetFileSize = size
            authError = null
            passphrase = ""
            decryptedManifest = null
            genuineCoverBitmap = null

            scope.launch {
                val tempCacheFile = File(context.cacheDir, "inspect_${System.currentTimeMillis()}.tmp")
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { inStream ->
                        FileOutputStream(tempCacheFile).use { outStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                }
                targetFile = tempCacheFile

                // Inspect for genuine outer cover JPEG without password
                val coverRes = repository.readOuterCover(tempCacheFile)
                coverRes.onSuccess { coverBytes ->
                    if (coverBytes != null && coverBytes.isNotEmpty()) {
                        isPolyglotJpeg = true
                        genuineCoverBitmap = BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size)
                    } else {
                        isPolyglotJpeg = false
                        genuineCoverBitmap = null
                    }
                }
            }
        }
    }

    if (decryptedManifest != null && targetFile != null) {
        // Vault Gallery View for decrypted archive with multi-select and viewer
        DecryptedGalleryViewer(
            manifest = decryptedManifest!!,
            archiveFile = targetFile!!,
            repository = repository,
            passphrase = passphrase,
            onClose = {
                decryptedManifest = null
            }
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding() // Placed before scrollable content for instant keyboard adjustment!
        ) {
            // Sticky Top App Bar (starts immediately below notch, no extra statusBarsPadding)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
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

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Scrollable Body
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp)
            ) {
                Spacer(Modifier.height(16.dp))

                if (targetFile == null) {
                    // File Selection Dropzone
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
                                text = "Choose a disguised photo (.jpg) or a standalone .zarc file from storage.",
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

                            // Zero-Knowledge Outer Cover Preview (Tappable for full screen)
                            if (genuineCoverBitmap != null) {
                                Spacer(Modifier.height(14.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(170.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                                        .clickable { showCoverPreviewModal = true }
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
                                            text = "UNENCRYPTED COVER (GENUINE JPEG)",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                                            color = Color.White,
                                            fontSize = 8.sp,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                        )
                                    }

                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .align(Alignment.BottomCenter),
                                        color = Color.Black.copy(alpha = 0.65f)
                                    ) {
                                        Text(
                                            text = "Tap to inspect full cover photo",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    // Passphrase & Decrypt Prompt
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
                                text = "Archive passphrase",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Enter master passphrase to authenticate and open gallery",
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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .bringIntoViewRequester(passRequester)
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            scope.launch { delay(120); passRequester.bringIntoView() }
                                        }
                                    },
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
                                    .height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                enabled = !isAuthenticating && passphrase.isNotEmpty()
                            ) {
                                if (isAuthenticating) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Verifying authenticity...", style = MaterialTheme.typography.labelMedium)
                                } else {
                                    Icon(imageVector = ZarcIcons.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Unlock & Open Gallery", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Cover Photo Preview Dialog (Tap to hide chrome)
    if (showCoverPreviewModal && genuineCoverBitmap != null) {
        var coverChromeVisible by remember { mutableStateOf(true) }
        Dialog(
            onDismissRequest = { showCoverPreviewModal = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { coverChromeVisible = !coverChromeVisible }
                        )
                    }
            ) {
                Image(
                    bitmap = genuineCoverBitmap!!.asImageBitmap(),
                    contentDescription = "Cover preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )

                AnimatedVisibility(
                    visible = coverChromeVisible,
                    modifier = Modifier.align(Alignment.TopCenter),
                    enter = fadeIn() + slideInVertically { -it },
                    exit = fadeOut() + slideOutVertically { -it },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)))
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { showCoverPreviewModal = false }) {
                            Icon(imageVector = ZarcIcons.Back, contentDescription = "Close", tint = Color.White)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = targetFileName ?: "Disguised Cover",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                            Text(
                                text = "Genuine JPEG outer cover photo",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.75f)
                            )
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 7. DECRYPTED GALLERY VIEWER (FULL GALLERY EXPERIENCE WITH MULTI-SELECT & STICKY HEADER)
// -----------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
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
    var isExtracting by remember { mutableStateOf(false) }

    // Multi-Selection State for Decrypted Gallery
    val selectedObjectIds = remember { mutableStateListOf<String>() }
    val isSelecting = selectedObjectIds.isNotEmpty()

    // Handle back button: dismiss selection if active, otherwise close viewer
    BackHandler {
        if (isSelecting) {
            selectedObjectIds.clear()
        } else {
            onClose()
        }
    }

    // Extraction helper for any list of object IDs (single, multiple, or all)
    val extractTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            isExtracting = true
            scope.launch {
                val targetDir = DocumentFile.fromTreeUri(context, treeUri)
                if (targetDir != null && targetDir.canWrite()) {
                    var successCount = 0
                    withContext(Dispatchers.IO) {
                        val tempExtractDir = File(context.cacheDir, "decrypted_extract_${System.currentTimeMillis()}")
                        tempExtractDir.mkdirs()

                        // Determine objects to extract: if selecting, extract selected; else extract all
                        val objectsToExtract = if (selectedObjectIds.isNotEmpty()) {
                            manifest.objects.filter { selectedObjectIds.contains(it.id) }
                        } else {
                            manifest.objects
                        }

                        for (obj in objectsToExtract) {
                            val tempFile = File(tempExtractDir, obj.originalName)
                            val res = repository.extractSingleEntry(archiveFile, passphrase, obj.id, tempFile)
                            if (res.isSuccess) {
                                val mime = if (obj.originalName.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"
                                val newDoc = targetDir.createFile(mime, obj.originalName)
                                if (newDoc != null) {
                                    context.contentResolver.openOutputStream(newDoc.uri)?.use { out ->
                                        tempFile.inputStream().use { it.copyTo(out) }
                                    }
                                    successCount++
                                }
                            }
                            tempFile.delete()
                        }
                        tempExtractDir.deleteRecursively()
                    }
                    isExtracting = false
                    selectedObjectIds.clear()
                    Toast.makeText(context, "Successfully extracted $successCount photos!", Toast.LENGTH_LONG).show()
                } else {
                    isExtracting = false
                    Toast.makeText(context, "Cannot write to selected directory", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Sticky Top Bar (starts immediately below notch, no extra statusBarsPadding)
            if (isSelecting) {
                // Selection Mode Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { selectedObjectIds.clear() }) {
                        Icon(
                            imageVector = ZarcIcons.Close,
                            contentDescription = "Close selection",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${selectedObjectIds.size} selected",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val selectedBytes = manifest.objects.filter { selectedObjectIds.contains(it.id) }.sumOf { it.sanitizedSize }
                        Text(
                            text = formatBytes(selectedBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    TextButton(
                        onClick = {
                            if (selectedObjectIds.size == manifest.objects.size) {
                                selectedObjectIds.clear()
                            } else {
                                selectedObjectIds.clear()
                                selectedObjectIds.addAll(manifest.objects.map { it.id })
                            }
                        }
                    ) {
                        Text(
                            text = if (selectedObjectIds.size == manifest.objects.size) "Deselect all" else "Select all",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            } else {
                // Normal Gallery Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
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

                // Extract All Button
                Button(
                    onClick = { extractTreeLauncher.launch(null) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    enabled = !isExtracting
                ) {
                    if (isExtracting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(ZarcIcons.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Extract all", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        // Decrypted Photos Grid with Long-Press Multi-Select
        Box(modifier = Modifier.weight(1f)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(manifest.objects) { index, obj ->
                    val isSelected = selectedObjectIds.contains(obj.id)
                    var thumbBitmap by remember(obj.id) { mutableStateOf<Bitmap?>(null) }

                    LaunchedEffect(obj.id) {
                        val result = repository.extractEntryToBitmap(archiveFile, passphrase, obj.id, maxDimension = 320)
                        result.onSuccess { bmp -> thumbBitmap = bmp }
                    }

                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .combinedClickable(
                                onClick = {
                                    if (isSelecting) {
                                        ZarcHaptics.click(context)
                                        if (isSelected) selectedObjectIds.remove(obj.id)
                                        else selectedObjectIds.add(obj.id)
                                    } else {
                                        activeViewerIndex = index
                                    }
                                },
                                onLongClick = {
                                    ZarcHaptics.longPress(context)
                                    if (isSelected) selectedObjectIds.remove(obj.id)
                                    else selectedObjectIds.add(obj.id)
                                }
                            )
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
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        // Selection Checkmark Overlay
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                                contentAlignment = Alignment.TopEnd
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(bottomStart = 8.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                ) {
                                    Text(
                                        text = "✓",
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        } else if (isSelecting) {
                            // Unselected selection indicator ring
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                                    .background(Color.Black.copy(alpha = 0.3f))
                            )
                        }

                        // Bottom file info badge (non-clickable so tap registers on parent card)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .background(Color.Black.copy(alpha = 0.55f))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = obj.originalName,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 8.sp
                            )
                        }
                    }
                }
            }

            // Floating Selection Action Bar (when selecting items)
            androidx.compose.animation.AnimatedVisibility(
                visible = isSelecting,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it }
            ) {
                Surface(
                    onClick = { extractTreeLauncher.launch(null) },
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primary,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isExtracting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Extracting ${selectedObjectIds.size} photos...",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                imageVector = ZarcIcons.Download,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Extract Selected (${selectedObjectIds.size})",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }
    }

    // Full-Screen Gallery Viewer with Swipe, Zoom, Tap-to-Hide Chrome & Mini-Roll
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
}

// -----------------------------------------------------------------------------
// 8. FULL-SCREEN GALLERY VIEWER WITH HORIZONTAL PAGER & MINI-ROLL
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

    val pagerState = rememberPagerState(initialPage = initialIndex) { manifest.objects.size }
    LaunchedEffect(initialIndex) {
        if (pagerState.currentPage != initialIndex) {
            pagerState.scrollToPage(initialIndex)
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val currentObject = manifest.objects.getOrNull(pagerState.currentPage) ?: run {
            onClose()
            return@Dialog
        }

        var chromeVisible by remember { mutableStateOf(true) }
        var showInfoSheet by remember { mutableStateOf(false) }

    // In-memory cache for decrypted bitmaps during viewing session
    val bitmapCache = remember { mutableMapOf<String, Bitmap>() }

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
        // HorizontalPager for natural swipe to next/prev with Compose physics
        HorizontalPager(
            state = pagerState,
            key = { manifest.objects[it].id },
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val obj = manifest.objects[page]
            var pageBitmap by remember(obj.id) { mutableStateOf(bitmapCache[obj.id]) }
            var isDecoding by remember(obj.id) { mutableStateOf(pageBitmap == null) }

            LaunchedEffect(obj.id) {
                if (pageBitmap == null) {
                    val res = repository.extractEntryToBitmap(archiveFile, passphrase, obj.id, maxDimension = 2048)
                    res.onSuccess { bmp ->
                        bitmapCache[obj.id] = bmp
                        pageBitmap = bmp
                    }
                    isDecoding = false
                }
            }

            var scale by remember { mutableStateOf(1f) }
            var offsetX by remember { mutableStateOf(0f) }
            var offsetY by remember { mutableStateOf(0f) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                scale = if (scale > 1f) 1f else 2.5f
                                offsetX = 0f
                                offsetY = 0f
                            },
                            onTap = {
                                chromeVisible = !chromeVisible
                            }
                        )
                    }
                    .pointerInput(page) {
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
                if (pageBitmap != null) {
                    Image(
                        bitmap = pageBitmap!!.asImageBitmap(),
                        contentDescription = obj.originalName,
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
                } else if (isDecoding) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // Top App Bar (Fade & Slide with Chrome visibility)
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)
                        )
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(imageVector = ZarcIcons.Back, contentDescription = "Close", tint = Color.White)
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
                        text = "${pagerState.currentPage + 1} of ${manifest.objects.size} · ${formatBytes(currentObject.sanitizedSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f)
                    )
                }

                IconButton(onClick = { showInfoSheet = true }) {
                    Icon(imageVector = ZarcIcons.Info, contentDescription = "Details", tint = Color.White)
                }

                IconButton(onClick = { saveSingleDocLauncher.launch(currentObject.originalName) }) {
                    Icon(imageVector = ZarcIcons.Download, contentDescription = "Extract", tint = Color.White)
                }
            }
        }

        // Bottom Filmstrip MiniRoll & Quick Action Bar (Fade & Slide with Chrome visibility)
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.90f))
                        )
                    )
                    .navigationBarsPadding()
                    .padding(bottom = 8.dp)
            ) {
                // Interactive MiniRoll (Samsung / Apple Gallery style)
                if (manifest.objects.size > 1) {
                    val filmstripState = rememberLazyListState()

                    LaunchedEffect(pagerState.currentPage) {
                        filmstripState.animateScrollToItem(maxOf(0, pagerState.currentPage - 2))
                    }

                    LazyRow(
                        state = filmstripState,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        itemsIndexed(manifest.objects) { index, obj ->
                            val isSelected = index == pagerState.currentPage
                            var miniThumb by remember(obj.id) { mutableStateOf<Bitmap?>(null) }

                            LaunchedEffect(obj.id) {
                                val res = repository.extractEntryToBitmap(archiveFile, passphrase, obj.id, maxDimension = 120)
                                res.onSuccess { bmp -> miniThumb = bmp }
                            }

                            Box(
                                modifier = Modifier
                                    .size(if (isSelected) 48.dp else 40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(
                                        width = if (isSelected) 2.dp else 0.dp,
                                        color = if (isSelected) Color.White else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        ZarcHaptics.click(context)
                                        scope.launch {
                                            pagerState.animateScrollToPage(index)
                                        }
                                    }
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

                    Spacer(Modifier.height(8.dp))
                }

                // Bottom Action Pill
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = { saveSingleDocLauncher.launch(currentObject.originalName) },
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = ZarcIcons.Download,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Extract this photo",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }
    }

    // Photo Details Bottom Sheet
    if (showInfoSheet) {
        ZarcSheet(onDismiss = { showInfoSheet = false }) {
            SheetHeader(
                title = currentObject.originalName,
                subtitle = "${formatBytes(currentObject.sanitizedSize)} sanitized · ${formatBytes(currentObject.originalSize)} original",
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Cipher Suite", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("XChaCha20-Poly1305", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("KDF Algorithm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Argon2id (m=64M, t=3, p=1)", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Object ID", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(currentObject.id.take(16) + "...", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Tamper Verification", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Authenticated AEAD Tag Valid", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = ZarcPalette.verified)
                        }
                    }
                }
            }

            SheetAction(
                icon = ZarcIcons.Download,
                title = "Extract photo to storage",
                subtitle = "Save unencrypted copy to device folder via SAF",
                onClick = {
                    showInfoSheet = false
                    saveSingleDocLauncher.launch(currentObject.originalName)
                }
            )
        }
    }
    }
}

// -----------------------------------------------------------------------------
// 9. HELPER FUNCTIONS
// -----------------------------------------------------------------------------

private fun queryFileName(context: Context, uri: Uri): String? {
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
    }
    return uri.lastPathSegment
}

private fun queryFileSize(context: Context, uri: Uri): Long {
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0) return cursor.getLong(idx)
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
        } else null
    } catch (_: Exception) {
        null
    }
}

private fun loadSourceBitmap(context: Context, source: ArchivePhotoSource, maxDimension: Int): Bitmap? {
    return try {
        if (source.localFile != null && source.localFile.exists()) {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.localFile.absolutePath, boundsOpts)
            val sample = calculateInSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, maxDimension)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(source.localFile.absolutePath, opts)
        } else if (source.uri != null) {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(source.uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOpts)
            }
            val sample = calculateInSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, maxDimension)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(source.uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } else null
    } catch (_: Exception) {
        null
    }
}

private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
    var inSampleSize = 1
    if (width > maxDim || height > maxDim) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while ((halfHeight / inSampleSize) >= maxDim && (halfWidth / inSampleSize) >= maxDim) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}
