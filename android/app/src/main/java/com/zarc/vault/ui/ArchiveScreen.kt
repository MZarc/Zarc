package com.zarc.vault.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
import com.zarc.vault.archive.ArchiveManifestData
import com.zarc.vault.archive.ArchiveObject
import com.zarc.vault.archive.ArchiveRepository
import com.zarc.vault.ui.theme.ZarcPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

private data class SelectedPhoto(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val bitmap: Bitmap?,
)

/**
 * Hallmark-crafted Archive Screen:
 * Supports creating and opening disguised polyglot photo archives (Album.jpg)
 * and standalone .zarc containers.
 */
@Composable
fun ArchiveScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { ArchiveRepository(context) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Create, 1 = Open

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
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
                    text = "Genuine JPEG photo containing custom encrypted container",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Tab Selector (Create vs Open)
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.primary,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = MaterialTheme.colorScheme.primary,
                    height = 3.dp
                )
            }
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = {
                    Text(
                        "Create (.jpg)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selectedTab == 0) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selectedTab == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = {
                    Text(
                        "Open & extract",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selectedTab == 1) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selectedTab == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }

        if (selectedTab == 0) {
            CreateArchiveTab(repository)
        } else {
            OpenArchiveTab(repository)
        }
    }
}

@Composable
private fun CreateArchiveTab(repository: ArchiveRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedPhotos by remember { mutableStateOf<List<SelectedPhoto>>(emptyList()) }
    var coverIndex by remember { mutableIntStateOf(0) }
    var archiveName by remember { mutableStateOf("Album") }
    var disguiseAsJpeg by remember { mutableStateOf(true) }
    var passphrase by remember { mutableStateOf("") }
    var confirmPassphrase by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var successDialogInfo by remember { mutableStateOf<String?>(null) }
    var errorDialogInfo by remember { mutableStateOf<String?>(null) }

    // Android Photo Picker Launcher
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                val newPhotos = uris.map { uri ->
                    val (name, size) = queryUriNameAndSize(context, uri)
                    val bmp = loadThumbnail(context, uri)
                    SelectedPhoto(uri, name, size, bmp)
                }
                withContext(Dispatchers.Main) {
                    selectedPhotos = selectedPhotos + newPhotos
                }
            }
        }
    }

    // Document creation launcher for saving .jpg / .zarc file
    val saveArchiveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(if (disguiseAsJpeg) "image/jpeg" else "application/octet-stream")
    ) { destUri ->
        if (destUri != null) {
            scope.launch {
                isProcessing = true
                statusMessage = "Sanitizing cover & encrypting payload with XChaCha20-Poly1305..."
                try {
                    val tempExt = if (disguiseAsJpeg) "jpg" else "zarc"
                    val tempOutputFile = File(context.cacheDir, "build_${System.currentTimeMillis()}.$tempExt")
                    val inputUris = selectedPhotos.map { it.uri }
                    val displayNames = selectedPhotos.map { it.name }

                    val res = repository.createArchive(
                        outputFile = tempOutputFile,
                        inputUris = inputUris,
                        displayNames = displayNames,
                        coverIndex = coverIndex,
                        passphrase = passphrase,
                        createAsPolyglotJpeg = disguiseAsJpeg,
                    )

                    res.fold(
                        onSuccess = { createdFile ->
                            statusMessage = "Exporting file..."
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openOutputStream(destUri)?.use { out ->
                                    createdFile.inputStream().use { input ->
                                        input.copyTo(out)
                                    }
                                }
                                createdFile.delete()
                            }
                            successDialogInfo = if (disguiseAsJpeg) {
                                "Disguised photo archive created successfully.\n\nSaved as genuine JPEG with unencrypted outer cover photo.\nCompatible with Android Gallery, Google Photos, and PC viewers.\nEncrypted private photos inside: ${selectedPhotos.size}\nEncryption: Argon2id + XChaCha20-Poly1305."
                            } else {
                                "Standalone .zarc container created successfully.\n\nEncrypted photos: ${selectedPhotos.size}\nEncryption: Argon2id + XChaCha20-Poly1305."
                            }
                            selectedPhotos = emptyList()
                            passphrase = ""
                            confirmPassphrase = ""
                        },
                        onFailure = { err ->
                            errorDialogInfo = err.message ?: "Archive packaging failed"
                        }
                    )
                } catch (e: Exception) {
                    errorDialogInfo = e.message ?: "Failed to save archive"
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Disguise Notice Tile
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = ZarcIcons.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "JPEG photo disguise active",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "The container is saved as a genuine .jpg image with a valid cover photo. Phone galleries and Google Photos display the cover normally; private contents remain 100% encrypted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        // Photo Picker Section
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "Archive photos",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${selectedPhotos.size} photos selected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Button(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = ZarcIcons.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Add photos")
                        }
                    }

                    if (selectedPhotos.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Select designated cover photo:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            itemsIndexed(selectedPhotos) { idx, photo ->
                                val isCover = idx == coverIndex
                                Box(
                                    modifier = Modifier
                                        .size(100.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .border(
                                            width = if (isCover) 2.dp else 1.dp,
                                            color = if (isCover) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                        .clickable { coverIndex = idx }
                                ) {
                                    if (photo.bitmap != null) {
                                        Image(
                                            bitmap = photo.bitmap.asImageBitmap(),
                                            contentDescription = photo.name,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                photo.name.take(3),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                    }

                                    if (isCover) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(bottomEnd = 8.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    ZarcIcons.Star,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.size(11.dp)
                                                )
                                                Spacer(Modifier.width(3.dp))
                                                Text(
                                                    "Cover",
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp),
                                                )
                                            }
                                        }
                                    }

                                    // Remove Photo Button
                                    IconButton(
                                        onClick = {
                                            val updated = selectedPhotos.toMutableList().apply { removeAt(idx) }
                                            selectedPhotos = updated
                                            if (coverIndex >= updated.size) {
                                                coverIndex = (updated.size - 1).coerceAtLeast(0)
                                            }
                                        },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(26.dp)
                                            .padding(2.dp)
                                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                    ) {
                                        Icon(
                                            ZarcIcons.Close,
                                            contentDescription = "Remove",
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Container Settings Section
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "File & encryption parameters",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = archiveName,
                        onValueChange = { archiveName = it.replace(" ", "_") },
                        label = { Text("Output file name") },
                        suffix = { Text(if (disguiseAsJpeg) ".jpg" else ".zarc", color = MaterialTheme.colorScheme.primary) },
                        colors = outlinedFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Disguise as JPEG photo (.jpg)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text("Outer cover visible to any gallery without password", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = disguiseAsJpeg,
                            onCheckedChange = { disguiseAsJpeg = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Encryption passphrase") },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            TextButton(onClick = { showPassword = !showPassword }) {
                                Text(if (showPassword) "Hide" else "Show", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            }
                        },
                        colors = outlinedFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = confirmPassphrase,
                        onValueChange = { confirmPassphrase = it },
                        label = { Text("Confirm passphrase") },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = outlinedFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        isError = confirmPassphrase.isNotEmpty() && confirmPassphrase != passphrase
                    )

                    if (confirmPassphrase.isNotEmpty() && confirmPassphrase != passphrase) {
                        Text(
                            "Passphrases do not match",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                        )
                    }
                }
            }
        }

        // Build Button
        item {
            val canBuild = selectedPhotos.isNotEmpty() &&
                    passphrase.isNotBlank() &&
                    passphrase == confirmPassphrase &&
                    !isProcessing

            Button(
                onClick = {
                    val ext = if (disguiseAsJpeg) ".jpg" else ".zarc"
                    val finalName = if (archiveName.endsWith(ext, ignoreCase = true)) archiveName else "$archiveName$ext"
                    saveArchiveLauncher.launch(finalName)
                },
                enabled = canBuild,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(statusMessage, style = MaterialTheme.typography.bodySmall)
                } else {
                    Icon(ZarcIcons.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (disguiseAsJpeg) "Build disguised Album.jpg" else "Build .zarc container",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        }
    }

    if (successDialogInfo != null) {
        AlertDialog(
            onDismissRequest = { successDialogInfo = null },
            confirmButton = {
                TextButton(onClick = { successDialogInfo = null }) {
                    Text("Done", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            },
            title = { Text("Container ready", style = MaterialTheme.typography.titleLarge) },
            text = { Text(successDialogInfo ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    if (errorDialogInfo != null) {
        AlertDialog(
            onDismissRequest = { errorDialogInfo = null },
            confirmButton = {
                TextButton(onClick = { errorDialogInfo = null }) {
                    Text("Dismiss", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                }
            },
            title = { Text("Packaging failed", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleLarge) },
            text = { Text(errorDialogInfo ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
private fun OpenArchiveTab(repository: ArchiveRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedArchiveUri by remember { mutableStateOf<Uri?>(null) }
    var selectedArchiveName by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    var isUnlocking by remember { mutableStateOf(false) }
    var unlockedManifest by remember { mutableStateOf<ArchiveManifestData?>(null) }
    var localCachedArchiveFile by remember { mutableStateOf<File?>(null) }
    var outerCoverBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var inspectingObject by remember { mutableStateOf<ArchiveObject?>(null) }
    var inspectedObjectBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isExtracting by remember { mutableStateOf(false) }

    var errorDialogInfo by remember { mutableStateOf<String?>(null) }
    var messageDialogInfo by remember { mutableStateOf<String?>(null) }

    // Launcher for picking .jpg or .zarc container
    val pickArchiveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedArchiveUri = uri
            val (name, _) = queryUriNameAndSize(context, uri)
            selectedArchiveName = name
            unlockedManifest = null
            outerCoverBitmap = null

            // Copy to temp cache and immediately inspect for outer cover photo
            scope.launch(Dispatchers.IO) {
                val cacheArchive = File(context.cacheDir, "open_${System.currentTimeMillis()}_$name")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(cacheArchive).use { out -> input.copyTo(out) }
                }
                localCachedArchiveFile = cacheArchive

                // Attempt to read outer cover without password
                val outerCoverRes = repository.readOuterCover(cacheArchive)
                val outerBytes = outerCoverRes.getOrNull()
                if (outerBytes != null) {
                    val bmp = BitmapFactory.decodeByteArray(outerBytes, 0, outerBytes.size)
                    withContext(Dispatchers.Main) {
                        outerCoverBitmap = bmp
                    }
                }
            }
        }
    }

    // Launcher for extracting all files to a chosen directory
    val extractAllLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null && localCachedArchiveFile != null && unlockedManifest != null) {
            scope.launch {
                isExtracting = true
                try {
                    val destTempDir = File(context.cacheDir, "extract_all_${System.currentTimeMillis()}")
                    val res = repository.extractAllEntries(
                        archiveFile = localCachedArchiveFile!!,
                        passphrase = passphrase,
                        destinationDir = destTempDir
                    )

                    res.fold(
                        onSuccess = { files ->
                            withContext(Dispatchers.IO) {
                                for (file in files) {
                                    val docUri = DocumentFile
                                        .fromTreeUri(context, treeUri)
                                        ?.createFile("image/jpeg", file.name)
                                        ?.uri

                                    if (docUri != null) {
                                        context.contentResolver.openOutputStream(docUri)?.use { out ->
                                            file.inputStream().use { input -> input.copyTo(out) }
                                        }
                                    }
                                }
                                destTempDir.deleteRecursively()
                            }
                            messageDialogInfo = "All ${files.size} photos successfully extracted to selected folder."
                        },
                        onFailure = { err ->
                            errorDialogInfo = err.message ?: "Extraction failed"
                        }
                    )
                } catch (e: Exception) {
                    errorDialogInfo = e.message ?: "Extraction failed"
                } finally {
                    isExtracting = false
                }
            }
        }
    }

    // Launcher for extracting single file
    val extractSingleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/jpeg")
    ) { saveUri ->
        if (saveUri != null && inspectingObject != null && localCachedArchiveFile != null) {
            scope.launch {
                isExtracting = true
                try {
                    val tempExtFile = File(context.cacheDir, "ext_${inspectingObject!!.id}.dat")
                    val res = repository.extractSingleEntry(
                        archiveFile = localCachedArchiveFile!!,
                        passphrase = passphrase,
                        objectId = inspectingObject!!.id,
                        destinationFile = tempExtFile
                    )
                    res.fold(
                        onSuccess = { extFile ->
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openOutputStream(saveUri)?.use { out ->
                                    extFile.inputStream().use { input -> input.copyTo(out) }
                                }
                                extFile.delete()
                            }
                            Toast.makeText(context, "Saved ${inspectingObject!!.originalName}", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { err ->
                            errorDialogInfo = err.message ?: "Single extraction failed"
                        }
                    )
                } catch (e: Exception) {
                    errorDialogInfo = e.message ?: "Failed to save photo"
                } finally {
                    isExtracting = false
                }
            }
        }
    }

    if (unlockedManifest == null) {
        // Locked state: Select file + enter password
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (outerCoverBitmap != null) {
                                Image(
                                    bitmap = outerCoverBitmap!!.asImageBitmap(),
                                    contentDescription = "Cover",
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = ZarcIcons.Shield,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    if (outerCoverBitmap != null) "Disguised photo (.jpg)" else "Select archive (.jpg or .zarc)",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    if (selectedArchiveUri != null) selectedArchiveName else "Tap below to select photo/archive",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (outerCoverBitmap != null) {
                            Spacer(Modifier.height(10.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
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
                                        "Genuine outer JPEG cover rendered without password",
                                        color = ZarcPalette.verified,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        OutlinedButton(
                            onClick = {
                                pickArchiveLauncher.launch(
                                    arrayOf("image/jpeg", "image/*", "application/octet-stream", "*/*")
                                )
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text(if (selectedArchiveUri == null) "Select .jpg / .zarc file" else "Change file")
                        }
                    }
                }
            }

            if (selectedArchiveUri != null) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Unlock container",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Enter the passphrase to authenticate and decrypt private contents:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(14.dp))

                            OutlinedTextField(
                                value = passphrase,
                                onValueChange = { passphrase = it },
                                label = { Text("Passphrase") },
                                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = {
                                    TextButton(onClick = { showPassword = !showPassword }) {
                                        Text(if (showPassword) "Hide" else "Show", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                    }
                                },
                                colors = outlinedFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(Modifier.height(16.dp))

                            Button(
                                onClick = {
                                    scope.launch {
                                        isUnlocking = true
                                        try {
                                            val cacheArchive = localCachedArchiveFile ?: return@launch
                                            val manifestRes = repository.readManifest(cacheArchive, passphrase)
                                            manifestRes.fold(
                                                onSuccess = { manifest ->
                                                    unlockedManifest = manifest
                                                },
                                                onFailure = { err ->
                                                    errorDialogInfo = err.message ?: "Authentication failed: invalid passphrase or corrupted container"
                                                }
                                            )
                                        } catch (e: Exception) {
                                            errorDialogInfo = e.message ?: "Failed to open archive"
                                        } finally {
                                            isUnlocking = false
                                        }
                                    }
                                },
                                enabled = passphrase.isNotBlank() && !isUnlocking,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                if (isUnlocking) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Unwrapping DEK & authenticating...")
                                } else {
                                    Icon(ZarcIcons.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Unlock & decrypt photos", style = MaterialTheme.typography.titleSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        // Unlocked state: Browse images & extract
        val manifest = unlockedManifest!!
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Unlocked Container Summary Surface
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (outerCoverBitmap != null) {
                            Image(
                                bitmap = outerCoverBitmap!!.asImageBitmap(),
                                contentDescription = "Cover",
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(ZarcIcons.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }

                        Spacer(Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                selectedArchiveName,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${manifest.objects.size} private photos, ${formatFileSize(manifest.objects.sumOf { it.sanitizedSize })}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "ID: ${manifest.containerId.take(8)} (Argon2id + XChaCha20)",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // Action Bar: Extract All & Lock
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { extractAllLauncher.launch(null) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).height(46.dp)
                        ) {
                            if (isExtracting) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(ZarcIcons.Import, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Extract all photos", style = MaterialTheme.typography.labelLarge)
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                unlockedManifest = null
                                localCachedArchiveFile?.delete()
                                localCachedArchiveFile = null
                                outerCoverBitmap = null
                                passphrase = ""
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(46.dp)
                        ) {
                            Icon(ZarcIcons.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Lock", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Text(
                "Decrypted archive contents (${manifest.objects.size})",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            // Grid of Photos in Archive
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(manifest.objects) { obj ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.85f)
                            .clickable {
                                inspectingObject = obj
                                scope.launch(Dispatchers.IO) {
                                    val tempFile = File(context.cacheDir, "preview_${obj.id}.dat")
                                    val res = repository.extractSingleEntry(
                                        archiveFile = localCachedArchiveFile!!,
                                        passphrase = passphrase,
                                        objectId = obj.id,
                                        destinationFile = tempFile
                                    )
                                    if (res.isSuccess) {
                                        val bmp = BitmapFactory.decodeFile(tempFile.absolutePath)
                                        withContext(Dispatchers.Main) {
                                            inspectedObjectBitmap = bmp
                                        }
                                        tempFile.delete()
                                    }
                                }
                            }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    obj.originalName.takeLast(4).uppercase(),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary,
                                )

                                if (obj.isCover) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(bottomEnd = 6.dp))
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text("Cover", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
                                    }
                                }
                            }

                            Spacer(Modifier.height(4.dp))

                            Text(
                                obj.originalName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                formatFileSize(obj.sanitizedSize),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal Dialog: Inspect / Extract Single Photo
    if (inspectingObject != null) {
        val obj = inspectingObject!!
        Dialog(onDismissRequest = {
            inspectingObject = null
            inspectedObjectBitmap = null
        }) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (inspectedObjectBitmap != null) {
                        Image(
                            bitmap = inspectedObjectBitmap!!.asImageBitmap(),
                            contentDescription = obj.originalName,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Text(
                        obj.originalName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(10.dp))

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("MIME encoding:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(obj.mimeType, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Sanitized size:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatFileSize(obj.sanitizedSize), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Chunks count:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${obj.totalChunks} (64 KiB payload)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Cover status:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (obj.isCover) "Designated outer cover" else "Standard archive photo", style = MaterialTheme.typography.labelSmall, color = if (obj.isCover) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { extractSingleLauncher.launch(obj.originalName) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).height(46.dp)
                        ) {
                            Icon(ZarcIcons.Import, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Extract", style = MaterialTheme.typography.labelLarge)
                        }

                        OutlinedButton(
                            onClick = {
                                inspectingObject = null
                                inspectedObjectBitmap = null
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(46.dp)
                        ) {
                            Text("Close", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }

    if (errorDialogInfo != null) {
        AlertDialog(
            onDismissRequest = { errorDialogInfo = null },
            confirmButton = {
                TextButton(onClick = { errorDialogInfo = null }) {
                    Text("Dismiss", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                }
            },
            title = { Text("Archive error", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleLarge) },
            text = { Text(errorDialogInfo ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    if (messageDialogInfo != null) {
        AlertDialog(
            onDismissRequest = { messageDialogInfo = null },
            confirmButton = {
                TextButton(onClick = { messageDialogInfo = null }) {
                    Text("OK", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            },
            title = { Text("Extraction complete", style = MaterialTheme.typography.titleLarge) },
            text = { Text(messageDialogInfo ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.2f MB", mb)
}

private fun queryUriNameAndSize(context: Context, uri: Uri): Pair<String, Long> {
    var name = "Album.jpg"
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

private fun loadThumbnail(context: Context, uri: Uri): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val sample = calculateInSampleSize(bounds, 200, 200)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    } catch (_: Exception) {
        null
    }
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val (height: Int, width: Int) = options.outHeight to options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

@Composable
private fun outlinedFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    cursorColor = MaterialTheme.colorScheme.primary
)
