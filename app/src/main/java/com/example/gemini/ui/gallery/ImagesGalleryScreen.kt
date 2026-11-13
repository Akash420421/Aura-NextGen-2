package com.example.gemini.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.outlined.PhotoLibrary
import android.widget.Toast
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.ToolType
import com.example.gemini.theme.isAppInDarkTheme
import com.example.gemini.ui.components.FullScreenImageDialog
import com.example.gemini.ui.components.ImageDownloadHelper
import com.example.gemini.ui.components.LocalSnackbarHostState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val IMAGE_REGEX = Regex(".*\\.(png|jpe?g|webp|gif)(\\?.*)?$", RegexOption.IGNORE_CASE)
private val MARKDOWN_IMAGE_REGEX = Regex("!\\[(.*?)\\]\\((.*?)\\)")

data class GalleryItem(
    val id: String,
    val uri: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Composable
fun ImagesGalleryScreen(
    messages: List<ChatMessage> = emptyList(),
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val isDark = isAppInDarkTheme()
    val bgColor = MaterialTheme.colorScheme.background
    val topBarBg = if (isDark) Color(0xFF1C1C1E) else Color.White
    val textColor = if (isDark) Color(0xFFEDEDED) else Color(0xFF1A1A1A)
    val descColor = if (isDark) Color(0xFFA1A1AA) else Color(0xFF8A8A8E)
    val cardBg = if (isDark) Color(0xFF27272A) else Color.White

    var galleryItems by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var fullScreenItem by remember { mutableStateOf<GalleryItem?>(null) }
    var itemToDelete by remember { mutableStateOf<GalleryItem?>(null) }

    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = LocalSnackbarHostState.current

    // Scan for images across active messages and local storage
    LaunchedEffect(messages) {
        isLoading = true
        val items = withContext(Dispatchers.IO) {
            val list = mutableListOf<GalleryItem>()
            val seenUris = mutableSetOf<String>()

            fun addItem(uri: String, title: String, timestamp: Long) {
                val clean = uri.trim()
                if (clean.isNotBlank() && seenUris.add(clean)) {
                    list.add(
                        GalleryItem(
                            id = "img_${list.size}_${clean.hashCode()}",
                            uri = clean,
                            title = title.ifBlank { "Generated Image" },
                            timestamp = timestamp
                        )
                    )
                }
            }

            // 1. From Active Messages (Tool calls, attachments, markdown, raw file paths)
            messages.forEach { msg ->
                val time = msg.createdAt
                msg.toolCalls.forEach { tc ->
                    val out = tc.output.trim()
                    val isImg = tc.toolType == ToolType.GENERATE_IMAGE ||
                            out.startsWith("data:image/") ||
                            out.matches(IMAGE_REGEX) ||
                            out.contains(Regex("\\.(png|jpe?g|webp|gif)", RegexOption.IGNORE_CASE))
                    if (isImg && out.isNotBlank()) {
                        val cleanUri = if (out.startsWith("/") && !out.startsWith("file://")) "file://$out" else out
                        addItem(cleanUri, tc.command.ifBlank { "Generated image" }, time)
                    }
                }
                msg.attachments.forEach { att ->
                    if (att.isImage && att.path.isNotBlank()) {
                        val uri = if (att.path.startsWith("file://") || att.path.startsWith("http") || att.path.startsWith("data:")) att.path
                        else if (att.path.startsWith("/")) "file://${att.path}" else "file://${att.path}"
                        addItem(uri, att.name.ifBlank { "Attached image" }, time)
                    }
                }
                MARKDOWN_IMAGE_REGEX.findAll(msg.content).forEach { match ->
                    val alt = match.groupValues.getOrNull(1) ?: "Chat image"
                    val rawUrl = match.groupValues.getOrNull(2)?.trim().orEmpty()
                    if (rawUrl.isNotBlank()) {
                        val cleanUrl = if (rawUrl.startsWith("/") && !rawUrl.startsWith("file://")) "file://$rawUrl" else rawUrl
                        addItem(cleanUrl, alt, time)
                    }
                }
                // Also scan for file paths matching image extension inside assistant text response
                Regex("(/[^\\s\"'<>]+?\\.(png|jpe?g|webp|gif))", RegexOption.IGNORE_CASE).findAll(msg.content).forEach { match ->
                    val path = match.groupValues[1].trim()
                    if (File(path).exists()) {
                        addItem("file://$path", File(path).nameWithoutExtension.replace('_', ' '), time)
                    }
                }
            }

            // 2. Scan Aura & AntiGem Download/Picture directories
            val scanFolders = listOf(
                File("/sdcard/Download/AntiGem"),
                File("/sdcard/Pictures/AURA_Vision"),
                File("/sdcard/DCIM/AURA_AI_Chats"),
                File(context.filesDir, "images"),
                File(context.cacheDir, "images"),
                File("/data/user/0/com.termux/files/home/.antigem/uploads"),
                File("/data/user/0/com.termux/files/home/.gemini/antigravity-cli/brain")
            )

            scanFolders.forEach { folder ->
                if (folder.exists() && folder.isDirectory) {
                    val files = if (folder.name == "brain") {
                        folder.walkTopDown().maxDepth(4).filter { it.isFile && it.name.matches(IMAGE_REGEX) }.toList()
                    } else {
                        folder.listFiles()?.filter { it.isFile && it.name.matches(IMAGE_REGEX) } ?: emptyList()
                    }
                    files.forEach { f ->
                        addItem(f.absolutePath, f.nameWithoutExtension.replace('_', ' '), f.lastModified())
                    }
                }
            }

            // 3. Scan root Downloads for AI files (file_000...png, 179...png, IMG_...png/jpg)
            val downloadsFolder = File("/sdcard/Download")
            if (downloadsFolder.exists() && downloadsFolder.isDirectory) {
                downloadsFolder.listFiles()?.filter { f ->
                    f.isFile && (f.name.startsWith("file_000") || f.name.startsWith("IMG_") || f.name.startsWith("AURA_")) && f.name.matches(IMAGE_REGEX)
                }?.forEach { f ->
                    addItem(f.absolutePath, f.nameWithoutExtension.replace('_', ' '), f.lastModified())
                }
            }

            // Sort by most recent first
            list.sortedByDescending { it.timestamp }
        }

        galleryItems = items
        isLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                onClick = onBack,
                shape = CircleShape,
                color = topBarBg,
                shadowElevation = 2.dp,
                modifier = Modifier
                    .size(44.dp)
                    .align(Alignment.CenterStart)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        tint = textColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Images",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = textColor,
                    textAlign = TextAlign.Center
                )
                if (galleryItems.isNotEmpty()) {
                    Text(
                        text = "${galleryItems.size} ${if (galleryItems.size == 1) "image" else "images"}",
                        fontSize = 12.sp,
                        color = descColor
                    )
                }
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = Color(0xFF2F6FED)
                )
            }
        } else if (galleryItems.isEmpty()) {
            // Empty State
            Spacer(modifier = Modifier.weight(0.35f))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(if (isDark) Color(0xFF27272A) else Color(0xFFE5E5EA)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PhotoLibrary,
                        contentDescription = null,
                        tint = descColor,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "No images yet",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = textColor,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Images generated by Aura during your chats or saved to your device will appear here.",
                    fontSize = 14.5.sp,
                    color = descColor,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
            }
            Spacer(modifier = Modifier.weight(0.65f))
        } else {
            // 2-Column Responsive Image Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(galleryItems, key = { it.id }) { item ->
                    GalleryCard(
                        item = item,
                        cardBg = cardBg,
                        textColor = textColor,
                        onClick = { fullScreenItem = item },
                        onDownload = {
                            ImageDownloadHelper.downloadImage(
                                context = context,
                                imageSource = item.uri,
                                title = item.title,
                                coroutineScope = coroutineScope,
                                snackbarHostState = snackbarHostState
                            )
                        },
                        onDelete = { itemToDelete = item }
                    )
                }
            }
        }
    }

    if (itemToDelete != null) {
        val target = itemToDelete!!
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = {
                Text(
                    text = "Delete Image",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    color = textColor
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete \"${target.title}\"?",
                    fontSize = 14.sp,
                    color = descColor
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val path = target.uri.removePrefix("file://")
                        val file = File(path)
                        if (file.exists() && file.isFile) {
                            file.delete()
                        }
                        galleryItems = galleryItems.filter { it.id != target.id }
                        Toast.makeText(context, "Image deleted", Toast.LENGTH_SHORT).show()
                        itemToDelete = null
                        if (fullScreenItem?.id == target.id) fullScreenItem = null
                    }
                ) {
                    Text("Delete", color = Color(0xFFDC2626), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("Cancel", color = textColor)
                }
            },
            containerColor = cardBg,
            shape = RoundedCornerShape(20.dp)
        )
    }

    if (fullScreenItem != null) {
        FullScreenImageDialog(
            imageUrl = fullScreenItem!!.uri,
            title = fullScreenItem!!.title,
            onDismiss = { fullScreenItem = null }
        )
    }
}

@Composable
private fun GalleryCard(
    item: GalleryItem,
    cardBg: Color,
    textColor: Color,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = cardBg,
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(Color(0xFFE5E5EA)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(if (item.uri.startsWith("file://") || item.uri.startsWith("http") || item.uri.startsWith("data:")) item.uri else "file://${item.uri}")
                        .crossfade(true)
                        .build(),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Bottom Gradient Scrim for subtle title readability
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                                startY = 120f
                            )
                        )
                )

                // Delete icon overlay (top left)
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.40f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Quick Fullscreen Icon overlay (top right)
                IconButton(
                    onClick = onClick,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = "Expand",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Download icon overlay (bottom right)
                IconButton(
                    onClick = onDownload,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Download",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Title label (bottom left)
                Text(
                    text = item.title,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 8.dp, bottom = 8.dp, end = 40.dp)
                )
            }
        }
    }
}
