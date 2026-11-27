package com.example.gemini.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import coil.Coil
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.memory.MemoryCache
import coil.request.ImageRequest
import com.example.gemini.data.remote.HubMediaResolver
import com.example.gemini.theme.GeminiBlue
import com.example.gemini.theme.isAppInDarkTheme
import kotlinx.coroutines.delay

@Immutable
data class ImageActionCallbacks(
    val onRegenerateMessage: ((messageId: String) -> Unit)? = null,
    val onFollowUpEdit: ((imageUrl: String, title: String) -> Unit)? = null,
    val onImageResolvedOrSized: (() -> Unit)? = null
)

val LocalImageActionHandler = staticCompositionLocalOf<ImageActionCallbacks?> { null }
val LocalCurrentMessageId = staticCompositionLocalOf<String?> { null }

/**
 * Compact header bar shown inside the composer when Image Generation Mode is active.
 */
@Composable
fun ImageGenerationModeHeader(
    aspectRatio: ImageAspectRatio,
    onOpenSettings: () -> Unit,
    onExitImageMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isAppInDarkTheme()
    val accent = GeminiBlue
    val chipBg = if (isDark) Color(0xFF1F2738) else Color(0xFFEAF1FE)
    val neutralBg = if (isDark) Color(0xFF2C2C30) else Color(0xFFF2F3F7)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = chipBg
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Image Mode",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accent
                    )
                }
            }

            Surface(
                onClick = onOpenSettings,
                shape = RoundedCornerShape(14.dp),
                color = neutralBg
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AspectRatioMiniGlyph(
                        aspectRatio = aspectRatio,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                    )
                    Text(
                        text = aspectRatio.ratio,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = "Image settings",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }

        Surface(
            onClick = onExitImageMode,
            shape = CircleShape,
            color = neutralBg,
            modifier = Modifier.size(28.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Exit Image Mode",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

/**
 * Contextual reference preview + conversational follow-up quick prompt chips shown when editing
 * or iterating on a previous image in the composer.
 */
@Composable
fun ImageReferenceAndFollowUpBar(
    referenceLabel: String?,
    onClearReference: (() -> Unit)?,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isAppInDarkTheme()
    val cardBg = if (isDark) Color(0xFF28282C) else Color(0xFFF5F6FA)
    val chipBg = if (isDark) Color(0xFF2E3038) else Color(0xFFEFF2F9)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (!referenceLabel.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = cardBg,
                border = BorderStroke(1.dp, GeminiBlue.copy(alpha = 0.28f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PhotoLibrary,
                            contentDescription = null,
                            tint = GeminiBlue,
                            modifier = Modifier.size(16.dp)
                        )
                        Column {
                            Text(
                                text = "Follow-up reference",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = GeminiBlue
                            )
                            Text(
                                text = referenceLabel,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (onClearReference != null) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Remove reference",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { onClearReference() }
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ImageGenUiHelper.FOLLOW_UP_SUGGESTIONS.forEach { suggestion ->
                Surface(
                    onClick = { onSuggestionClick(suggestion) },
                    shape = RoundedCornerShape(16.dp),
                    color = chipBg,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                ) {
                    Text(
                        text = suggestion,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

/**
 * Compact scrollable strip of aspect ratios placed neatly inside the Image Generation Composer.
 */
@Composable
fun ImageRatioQuickStrip(
    selected: ImageAspectRatio,
    onSelect: (ImageAspectRatio) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isAppInDarkTheme()
    val selectedBg = if (isDark) Color(0xFF1F2738) else Color(0xFFEAF1FE)
    val idleBg = if (isDark) Color(0xFF2B2B2E) else Color(0xFFF3F4F6)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ImageAspectRatio.entries.forEach { ratio ->
            val isSelected = ratio == selected
            Surface(
                onClick = { onSelect(ratio) },
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) selectedBg else idleBg,
                border = if (isSelected) BorderStroke(1.dp, GeminiBlue.copy(alpha = 0.5f)) else null
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    AspectRatioMiniGlyph(
                        aspectRatio = ratio,
                        color = if (isSelected) GeminiBlue else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = ratio.ratio,
                        fontSize = 11.5.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) GeminiBlue else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
                    )
                }
            }
        }

        Surface(
            onClick = onOpenSettings,
            shape = RoundedCornerShape(12.dp),
            color = idleBg
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = "All image options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "More",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AspectRatioMiniGlyph(
    aspectRatio: ImageAspectRatio,
    color: Color
) {
    val (w, h) = when (aspectRatio) {
        ImageAspectRatio.SQUARE -> 11.dp to 11.dp
        ImageAspectRatio.LANDSCAPE -> 14.dp to 9.dp
        ImageAspectRatio.PORTRAIT -> 9.dp to 14.dp
        ImageAspectRatio.STANDARD -> 13.dp to 10.dp
        ImageAspectRatio.TALL -> 10.dp to 13.dp
    }
    Box(
        modifier = Modifier
            .size(width = w, height = h)
            .border(1.3.dp, color, RoundedCornerShape(2.5.dp))
    )
}

/**
 * Clean expandable Settings Bottom Sheet for mobile & desktop showing Aspect Ratio cards
 * plus honest frontend-only capability disclosures for Quality & Image Count.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageGenerationSettingsSheet(
    selectedRatio: ImageAspectRatio,
    onSelectRatio: (ImageAspectRatio) -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = isAppInDarkTheme()
    val cardBg = if (isDark) Color(0xFF232326) else Color(0xFFF6F7FA)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = GeminiBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Image Generation Settings",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Close",
                    modifier = Modifier
                        .size(22.dp)
                        .clickable { onDismiss() }
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Aspect Ratio",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ImageAspectRatio.entries.forEach { ratio ->
                        val active = ratio == selectedRatio
                        Surface(
                            onClick = { onSelectRatio(ratio) },
                            shape = RoundedCornerShape(14.dp),
                            color = if (active) GeminiBlue.copy(alpha = 0.14f) else cardBg,
                            border = BorderStroke(
                                width = if (active) 1.5.dp else 1.dp,
                                color = if (active) GeminiBlue else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                            ),
                            modifier = Modifier.width(86.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AspectRatioMiniGlyph(
                                    aspectRatio = ratio,
                                    color = if (active) GeminiBlue else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = ratio.ratio,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (active) GeminiBlue else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = ratio.label,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // Frontend-only presentation for backend-managed secondary parameters (never faked)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = cardBg,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Quality / Resolution", fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "Auto (determined by active AI model)",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
                        ) {
                            Text(
                                text = "Auto",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Number of Images", fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "Single image per turn (batch count not supported by chat backend)",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
                        ) {
                            Text(
                                text = "1 image",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Polished Video-2 inspired loading state with evolving phase text and smooth aspect-matched
 * shimmer canvas — replaces messy multi-thought/spinner clutter during image generation.
 */
@Composable
fun ImageGenerationLoadingCard(
    prompt: String,
    aspectRatio: ImageAspectRatio = ImageAspectRatio.SQUARE,
    modifier: Modifier = Modifier
) {
    val isDark = isAppInDarkTheme()
    val phases = ImageGenUiHelper.LOADING_PHASES
    var phaseIndex by remember(prompt) { mutableIntStateOf(0) }

    LaunchedEffect(prompt) {
        phaseIndex = 0
        while (true) {
            delay(4_200L)
            if (phaseIndex < phases.lastIndex) {
                phaseIndex++
            }
        }
    }

    val transition = rememberInfiniteTransition(label = "imgGenShimmer")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "imgGenShift"
    )
    val dotPulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "imgGenDotPulse"
    )

    val cardBase = if (isDark) Color(0xFF1A1A1D) else Color(0xFFEEEEF3)
    val cardPeak = if (isDark) Color(0xFF28282E) else Color(0xFFF8F8FB)
    val shimmer = Brush.linearGradient(
        colors = listOf(cardBase, cardPeak, cardBase),
        start = Offset(shift - 360f, shift - 360f),
        end = Offset(shift, shift)
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Subtle 3-dot animated indicator (no generic spinning wheel)
            ThreeDotPulseGlyph(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                pulse = dotPulse
            )
            AnimatedContent(
                targetState = phases[phaseIndex],
                transitionSpec = {
                    (fadeIn(tween(240)) + expandVertically()) togetherWith
                        (fadeOut(tween(180)) + shrinkVertically())
                },
                label = "imgGenPhase"
            ) { phaseLabel ->
                Text(
                    text = phaseLabel,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        val cleanPrompt = remember(prompt) { ImageGenUiHelper.stripUiRatioTag(prompt) }
        if (cleanPrompt.isNotBlank()) {
            Text(
                text = cleanPrompt,
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = cardBase,
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .aspectRatio(aspectRatio.previewAspect.coerceIn(0.65f, 1.8f))
                .heightIn(min = 210.dp, max = 380.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(shimmer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = "Generating image",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f + dotPulse * 0.14f),
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}

@Composable
private fun ThreeDotPulseGlyph(color: Color, pulse: Float) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(4.5.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = pulse))
        )
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = (1.3f - pulse).coerceIn(0.35f, 1f)))
        )
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = pulse * 0.85f))
        )
    }
}

/**
 * Generated Image Result Card preserving natural aspect ratio with rounded corners,
 * smooth entrance animation, corner Share pill (Video 2 style), and clickable Lightbox trigger.
 */
@Composable
fun HeroGeneratedImageCard(
    imageUrl: String,
    alt: String,
    modifier: Modifier = Modifier,
    showToolbar: Boolean = true,
    onOpenFullscreen: () -> Unit,
    onImageReadyForScroll: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = LocalSnackbarHostState.current
    val actionHandler = LocalImageActionHandler.current
    val currentMessageId = LocalCurrentMessageId.current

    val normalizedCacheKey = remember(imageUrl) { HubMediaResolver.normalizeKey(imageUrl) }
    var resolvedUrl by remember(imageUrl) {
        mutableStateOf(HubMediaResolver.getResolvedUriSync(context, imageUrl))
    }
    var resolutionPending by remember(imageUrl) {
        mutableStateOf(HubMediaResolver.needsAsyncResolution(context, imageUrl))
    }
    var knownAspectRatio by remember(imageUrl) {
        mutableStateOf(HubMediaResolver.getOrPeekAspectRatio(context, resolvedUrl.ifBlank { imageUrl }))
    }

    LaunchedEffect(imageUrl) {
        if (HubMediaResolver.needsAsyncResolution(context, imageUrl)) {
            resolutionPending = true
            try {
                val res = HubMediaResolver.resolveMediaUri(context, imageUrl)
                if (res.isNotBlank()) {
                    resolvedUrl = res
                    HubMediaResolver.getOrPeekAspectRatio(context, res)?.let { knownAspectRatio = it }
                }
            } finally {
                resolutionPending = false
            }
        } else {
            resolutionPending = false
        }
    }

    val effectiveUrl = resolvedUrl.ifBlank { imageUrl }
    val failedHubLookup = remember(imageUrl, resolutionPending, effectiveUrl) {
        !resolutionPending &&
            HubMediaResolver.hasFailedRecently(imageUrl) &&
            !HubMediaResolver.isInMemoryOrLocalDisk(effectiveUrl)
    }

    val inMemoryOrDisk = remember(effectiveUrl, normalizedCacheKey, context) {
        val inCoilRam = normalizedCacheKey.isNotBlank() && runCatching {
            Coil.imageLoader(context).memoryCache?.get(MemoryCache.Key(normalizedCacheKey)) != null
        }.getOrDefault(false)
        inCoilRam || HubMediaResolver.isInMemoryOrLocalDisk(effectiveUrl)
    }

    var isImageLoading by remember(imageUrl) {
        mutableStateOf(!inMemoryOrDisk && !failedHubLookup && (resolutionPending || effectiveUrl.startsWith("http", ignoreCase = true)))
    }
    var isImageError by remember(imageUrl) { mutableStateOf(failedHubLookup) }
    var hasEntered by remember(imageUrl) { mutableStateOf(inMemoryOrDisk) }

    val imageAlpha by animateFloatAsState(
        targetValue = if (!isImageLoading && !resolutionPending && !isImageError) 1f else 0f,
        animationSpec = tween(durationMillis = if (inMemoryOrDisk) 0 else 260),
        label = "heroImageFade"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .clickable(enabled = !isImageError && !failedHubLookup) {
                    onOpenFullscreen()
                },
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val targetHeight = remember(knownAspectRatio, maxWidth, isImageError, failedHubLookup) {
                    when {
                        knownAspectRatio != null && knownAspectRatio!! > 0f ->
                            (maxWidth.value / knownAspectRatio!!).coerceIn(170f, 520f).dp
                        isImageError || failedHubLookup -> 180.dp
                        else -> 250.dp
                    }
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(targetHeight)
                ) {
                    if (!failedHubLookup) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(effectiveUrl)
                                .apply {
                                    if (normalizedCacheKey.isNotBlank()) {
                                        memoryCacheKey(normalizedCacheKey)
                                    }
                                }
                                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                                .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                                .crossfade(false)
                                .setHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 AntiGem/1.0")
                                .decoderFactory(SvgDecoder.Factory())
                                .listener(
                                    onStart = {
                                        if (!inMemoryOrDisk && !failedHubLookup) {
                                            isImageLoading = true
                                        }
                                    },
                                    onSuccess = { _, result ->
                                        isImageLoading = false
                                        isImageError = false
                                        val w = result.drawable.intrinsicWidth
                                        val h = result.drawable.intrinsicHeight
                                        if (w > 0 && h > 0) {
                                            HubMediaResolver.putAspectRatio(imageUrl, w, h)
                                            if (effectiveUrl != imageUrl) {
                                                HubMediaResolver.putAspectRatio(effectiveUrl, w, h)
                                            }
                                            knownAspectRatio = (w.toFloat() / h.toFloat()).coerceIn(0.45f, 2.8f)
                                        }
                                        if (!hasEntered) {
                                            hasEntered = true
                                            onImageReadyForScroll?.invoke()
                                            actionHandler?.onImageResolvedOrSized?.invoke()
                                        }
                                    },
                                    onError = { _, res ->
                                        isImageLoading = false
                                        isImageError = true
                                        android.util.Log.e("HeroGeneratedImageCard", "Failed to load image $effectiveUrl: ${res.throwable.message}")
                                    }
                                )
                                .build(),
                            contentDescription = alt.ifBlank { "Generated image" },
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = imageAlpha }
                        )
                    }

                    if ((isImageLoading || resolutionPending) && !isImageError && !failedHubLookup) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(shimmerBrush()),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AutoAwesome,
                                contentDescription = "Loading image",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }

                    if (isImageError || failedHubLookup) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.BrokenImage,
                                contentDescription = "Failed to load image",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = alt.ifBlank { "Image could not be loaded" },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Corner Share pill matching Video 2 aesthetic
                    if (!isImageLoading && !resolutionPending && !isImageError && !failedHubLookup) {
                        Surface(
                            onClick = {
                                ImageDownloadHelper.shareImage(
                                    context = context,
                                    imageSource = effectiveUrl,
                                    title = alt,
                                    coroutineScope = coroutineScope
                                )
                            },
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.45f),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(10.dp)
                                .size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Share,
                                    contentDescription = "Share image",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showToolbar && !isImageError && !failedHubLookup) {
            GeneratedImageActionToolbar(
                imageUrl = effectiveUrl,
                title = alt,
                onFullscreen = onOpenFullscreen,
                onDownload = {
                    ImageDownloadHelper.downloadImage(
                        context = context,
                        imageSource = effectiveUrl,
                        title = alt,
                        coroutineScope = coroutineScope,
                        snackbarHostState = snackbarHostState
                    )
                },
                onCopy = {
                    ImageDownloadHelper.copyImageToClipboard(
                        context = context,
                        imageSource = effectiveUrl,
                        title = alt,
                        coroutineScope = coroutineScope
                    )
                },
                onShare = {
                    ImageDownloadHelper.shareImage(
                        context = context,
                        imageSource = effectiveUrl,
                        title = alt,
                        coroutineScope = coroutineScope
                    )
                },
                onRegenerate = currentMessageId?.let { msgId ->
                    actionHandler?.onRegenerateMessage?.let { regen -> { regen(msgId) } }
                },
                onFollowUpEdit = actionHandler?.onFollowUpEdit?.let { followUp ->
                    { followUp(effectiveUrl, alt) }
                },
                onOpenExternal = {
                    ImageDownloadHelper.openImageExternally(
                        context = context,
                        imageSource = effectiveUrl,
                        title = alt,
                        coroutineScope = coroutineScope
                    )
                }
            )
        }
    }
}

/**
 * Multi-image responsive 2-column grid for assistant turns returning 2+ generated images.
 */
@Composable
fun MultiImageResultGrid(
    images: List<MarkdownBlock.Image>,
    modifier: Modifier = Modifier,
    onOpenAtIndex: (Int) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = LocalSnackbarHostState.current
    val actionHandler = LocalImageActionHandler.current
    val currentMessageId = LocalCurrentMessageId.current
    val firstImg = images.firstOrNull() ?: return

    Column(
        modifier = modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val rows = images.chunked(2)
        rows.forEachIndexed { rowIdx, rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEachIndexed { colIdx, item ->
                    val globalIndex = rowIdx * 2 + colIdx
                    val resolved = remember(item.url) { HubMediaResolver.getResolvedUriSync(context, item.url).ifBlank { item.url } }
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { onOpenAtIndex(globalIndex) }
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(resolved)
                                    .crossfade(true)
                                    .decoderFactory(SvgDecoder.Factory())
                                    .build(),
                                contentDescription = item.alt.ifBlank { "Image ${globalIndex + 1}" },
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Surface(
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.48f),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "${globalIndex + 1}",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        val resolvedFirst = HubMediaResolver.getResolvedUriSync(context, firstImg.url).ifBlank { firstImg.url }
        GeneratedImageActionToolbar(
            imageUrl = resolvedFirst,
            title = firstImg.alt,
            onFullscreen = { onOpenAtIndex(0) },
            onDownload = {
                ImageDownloadHelper.downloadImage(
                    context = context,
                    imageSource = resolvedFirst,
                    title = firstImg.alt,
                    coroutineScope = coroutineScope,
                    snackbarHostState = snackbarHostState
                )
            },
            onCopy = {
                ImageDownloadHelper.copyImageToClipboard(
                    context = context,
                    imageSource = resolvedFirst,
                    title = firstImg.alt,
                    coroutineScope = coroutineScope
                )
            },
            onShare = {
                ImageDownloadHelper.shareImage(
                    context = context,
                    imageSource = resolvedFirst,
                    title = firstImg.alt,
                    coroutineScope = coroutineScope
                )
            },
            onRegenerate = currentMessageId?.let { msgId ->
                actionHandler?.onRegenerateMessage?.let { regen -> { regen(msgId) } }
            },
            onFollowUpEdit = actionHandler?.onFollowUpEdit?.let { followUp ->
                { followUp(resolvedFirst, firstImg.alt) }
            },
            onOpenExternal = {
                ImageDownloadHelper.openImageExternally(
                    context = context,
                    imageSource = resolvedFirst,
                    title = firstImg.alt,
                    coroutineScope = coroutineScope
                )
            }
        )
    }
}

/**
 * Clean, uncrowded Action Toolbar below generated images:
 * Download, Copy, Share, Fullscreen, Regenerate, Edit (Follow-up), and More menu.
 */
@Composable
fun GeneratedImageActionToolbar(
    imageUrl: String,
    title: String,
    onFullscreen: () -> Unit,
    onDownload: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRegenerate: (() -> Unit)?,
    onFollowUpEdit: (() -> Unit)?,
    onOpenExternal: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    var showMoreMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        ToolbarTooltipIconButton(
            icon = Icons.Outlined.Download,
            label = "Download",
            onClick = onDownload
        )
        ToolbarTooltipIconButton(
            icon = Icons.Outlined.ContentCopy,
            label = "Copy image",
            onClick = onCopy
        )
        ToolbarTooltipIconButton(
            icon = Icons.Outlined.Share,
            label = "Share",
            onClick = onShare
        )
        ToolbarTooltipIconButton(
            icon = Icons.Default.Fullscreen,
            label = "Fullscreen",
            onClick = onFullscreen
        )
        if (onRegenerate != null) {
            ToolbarTooltipIconButton(
                icon = Icons.Outlined.Refresh,
                label = "Regenerate",
                onClick = onRegenerate
            )
        }
        if (onFollowUpEdit != null) {
            Surface(
                onClick = onFollowUpEdit,
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = "Edit with follow-up prompt",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Edit",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )
                }
            }
        }

        Box {
            ToolbarTooltipIconButton(
                icon = Icons.Rounded.MoreHoriz,
                label = "More options",
                onClick = { showMoreMenu = true }
            )
            DropdownMenu(
                expanded = showMoreMenu,
                onDismissRequest = { showMoreMenu = false }
            ) {
                if (onFollowUpEdit != null) {
                    DropdownMenuItem(
                        text = { Text("Use as reference image") },
                        leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(18.dp)) },
                        onClick = {
                            showMoreMenu = false
                            onFollowUpEdit()
                        }
                    )
                }
                if (title.isNotBlank()) {
                    DropdownMenuItem(
                        text = { Text("Copy description") },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)) },
                        onClick = {
                            showMoreMenu = false
                            clipboardManager.setText(AnnotatedString(title))
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Open in external viewer") },
                    leadingIcon = { Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp)) },
                    onClick = {
                        showMoreMenu = false
                        onOpenExternal()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolbarTooltipIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var showTooltip by remember { mutableStateOf(false) }

    LaunchedEffect(showTooltip) {
        if (showTooltip) {
            delay(1_400L)
            showTooltip = false
        }
    }

    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showTooltip = true
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            modifier = Modifier.size(19.dp)
        )

        if (showTooltip) {
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, -68),
                onDismissRequest = { showTooltip = false }
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1F2229),
                    shadowElevation = 4.dp
                ) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}
