package com.example.gemini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import kotlin.math.roundToInt

@Immutable
data class FullScreenImageItem(
    val url: String,
    val title: String = ""
)

/**
 * Fullscreen dark image lightbox with smooth pinch-to-zoom, panning, explicit +/- zoom controls,
 * double-tap zoom reset, download/copy/share, follow-up edit shortcut, and prev/next navigation
 * across multiple generated images.
 */
@Composable
fun FullScreenImageDialog(
    imageUrl: String,
    title: String = "",
    images: List<FullScreenImageItem> = emptyList(),
    initialIndex: Int = 0,
    onFollowUpEdit: ((url: String, title: String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val effectiveImages = remember(images, imageUrl, title) {
        if (images.isNotEmpty()) images else listOf(FullScreenImageItem(imageUrl, title))
    }
    var currentIndex by remember(effectiveImages, initialIndex) {
        mutableIntStateOf(initialIndex.coerceIn(0, (effectiveImages.size - 1).coerceAtLeast(0)))
    }
    val activeItem = effectiveImages.getOrNull(currentIndex) ?: FullScreenImageItem(imageUrl, title)
    val activeUrl = activeItem.url
    val activeTitle = activeItem.title.ifBlank { title }

    var scale by remember(currentIndex, activeUrl) { mutableFloatStateOf(1f) }
    var offset by remember(currentIndex, activeUrl) { mutableStateOf(Offset.Zero) }
    var swipeAccumulator by remember { mutableFloatStateOf(0f) }

    val dialogSnackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val memKey = remember(activeUrl) { com.example.gemini.data.remote.HubMediaResolver.normalizeKey(activeUrl) }
    var resolvedUri by remember(activeUrl) {
        mutableStateOf(if (activeUrl.isNotBlank()) com.example.gemini.data.remote.HubMediaResolver.getResolvedUriSync(context, activeUrl) else "")
    }

    LaunchedEffect(activeUrl) {
        if (activeUrl.isNotBlank() && !com.example.gemini.data.remote.HubMediaResolver.isLocalOrCached(context, activeUrl)) {
            val res = com.example.gemini.data.remote.HubMediaResolver.resolveMediaUri(context, activeUrl)
            if (res.isNotBlank()) {
                resolvedUri = res
            }
        }
    }

    val finalUri = resolvedUri.ifBlank { activeUrl }
    val coilData: Any? = remember(finalUri, memKey) {
        val cachedBytes = com.example.gemini.data.remote.HubMediaResolver.getImageBytes(memKey)
        when {
            cachedBytes != null -> cachedBytes
            finalUri.startsWith("data:image/") -> {
                try {
                    val b64 = finalUri.substringAfter("base64,")
                    android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                } catch (_: Exception) {
                    finalUri
                }
            }
            finalUri.isNotBlank() -> finalUri
            else -> null
        }
    }

    val hasMultiple = effectiveImages.size > 1

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF609090C))
        ) {
            // Main Zoomable, Pannable, and Swipeable (when 1x and multi-image) Area
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(currentIndex, hasMultiple) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1.2f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.4f
                                }
                            }
                        )
                    }
                    .pointerInput(currentIndex, hasMultiple) {
                        if (hasMultiple) {
                            detectHorizontalDragGestures(
                                onDragStart = { swipeAccumulator = 0f },
                                onHorizontalDrag = { _, dragAmount ->
                                    if (scale <= 1.05f) {
                                        swipeAccumulator += dragAmount
                                    }
                                },
                                onDragEnd = {
                                    if (scale <= 1.05f) {
                                        if (swipeAccumulator < -110f && currentIndex < effectiveImages.lastIndex) {
                                            currentIndex++
                                        } else if (swipeAccumulator > 110f && currentIndex > 0) {
                                            currentIndex--
                                        }
                                    }
                                    swipeAccumulator = 0f
                                }
                            )
                        }
                    }
                    .pointerInput(currentIndex) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(0.85f, 5f)
                            scale = nextScale
                            if (nextScale <= 1.02f) {
                                offset = Offset.Zero
                            } else {
                                offset = Offset(
                                    x = offset.x + pan.x,
                                    y = offset.y + pan.y
                                )
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (coilData != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(coilData)
                            .memoryCacheKey(memKey.ifBlank { null })
                            .setHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 AntiGem/1.0")
                            .decoderFactory(SvgDecoder.Factory())
                            .crossfade(false)
                            .build(),
                        contentDescription = activeTitle,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 72.dp)
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            )
                    )
                }
            }

            // Multi-image Previous / Next Floating Navigation Buttons
            if (hasMultiple) {
                if (currentIndex > 0) {
                    Surface(
                        onClick = { currentIndex-- },
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.55f),
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 12.dp)
                            .size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.ChevronLeft,
                                contentDescription = "Previous image",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }
                if (currentIndex < effectiveImages.lastIndex) {
                    Surface(
                        onClick = { currentIndex++ },
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.55f),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 12.dp)
                            .size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = "Next image",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }
            }

            // Top Overlay Bar (Close, Title / Pager Index, Copy, Share, Download)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.14f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = if (hasMultiple) {
                        "${currentIndex + 1}/${effectiveImages.size}  ${activeTitle.ifBlank { "Image Viewer" }}"
                    } else {
                        activeTitle.ifBlank { "Image Viewer" }
                    },
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (onFollowUpEdit != null) {
                    IconButton(
                        onClick = {
                            onDismiss()
                            onFollowUpEdit(finalUri, activeTitle)
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.14f))
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = "Edit with follow-up prompt",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                IconButton(
                    onClick = {
                        ImageDownloadHelper.copyImageToClipboard(
                            context = context,
                            imageSource = finalUri,
                            title = activeTitle,
                            coroutineScope = coroutineScope
                        )
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.14f))
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy image",
                        tint = Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                IconButton(
                    onClick = {
                        ImageDownloadHelper.shareImage(
                            context = context,
                            imageSource = finalUri,
                            title = activeTitle,
                            coroutineScope = coroutineScope
                        )
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.14f))
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = "Share image",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                IconButton(
                    onClick = {
                        ImageDownloadHelper.downloadImage(
                            context = context,
                            imageSource = finalUri,
                            title = activeTitle,
                            coroutineScope = coroutineScope,
                            snackbarHostState = dialogSnackbarHostState
                        )
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.14f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Download",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Bottom Floating Zoom Controls Pill (+ / % / - / Reset)
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.Black.copy(alpha = 0.62f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 18.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = {
                            val next = (scale - 0.5f).coerceAtLeast(1f)
                            scale = next
                            if (next <= 1f) offset = Offset.Zero
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ZoomOut,
                            contentDescription = "Zoom out",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Text(
                        text = "${(scale * 100f).roundToInt()}%",
                        color = Color.White,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    IconButton(
                        onClick = {
                            scale = (scale + 0.5f).coerceAtMost(5f)
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ZoomIn,
                            contentDescription = "Zoom in",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    if (scale != 1f) {
                        IconButton(
                            onClick = {
                                scale = 1f
                                offset = Offset.Zero
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reset Zoom",
                                tint = Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }

            // In-dialog Snackbar Host
            SnackbarHost(
                hostState = dialogSnackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 72.dp)
            )
        }
    }
}
