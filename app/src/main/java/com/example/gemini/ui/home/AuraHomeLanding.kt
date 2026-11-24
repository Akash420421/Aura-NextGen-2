package com.example.gemini.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private val AuraInk = Color(0xFF151B2A)
private val AuraMuted = Color(0xFF8B8D98)
private val AuraBlue = Color(0xFF3478D4)

@Composable
fun AuraChatWelcome(
    onPromptSelected: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()

    BoxWithConstraints(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        val showSuggestions = maxHeight >= 260.dp

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "Hi Sir, what's on your mind?",
                color = if (isDark) Color(0xFFEDEDED) else Color(0xFF171717),
                fontSize = 28.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Ask a question, brainstorm ideas, or start coding",
                color = if (isDark) Color(0xFF9E9EA4) else AuraMuted,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center
            )

            if (showSuggestions) {
                Spacer(Modifier.height(24.dp))
                val suggestions = listOf(
                    "Create a mobile web app",
                    "Analyze code and fix bugs",
                    "Design a landing page"
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    suggestions.forEach { prompt ->
                        AuraSuggestionPillChip(
                            text = prompt,
                            isDark = isDark,
                            onClick = { onPromptSelected(prompt) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AuraSuggestionPillChip(
    text: String,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "chip_scale"
    )

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(22.dp),
        color = if (isDark) Color(0xFF2A2A2C) else Color.White,
        border = BorderStroke(1.dp, if (isDark) Color(0xFF3A3A3C) else Color(0xFFE8E8EC)),
        shadowElevation = 0.dp,
        modifier = modifier
            .wrapContentWidth()
            .height(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = if (isDark) Color(0xFFEDEDED) else Color(0xFF1A1A1A),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

@Composable
fun AuraVoiceWelcome(
    userName: String? = null,
    isPaused: Boolean = false,
    onActionTap: () -> Unit,
    onActionDoubleTap: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val livePhase by com.example.gemini.data.live.AuraLiveVoiceManager.phase.collectAsState()
    val liveStatusText by com.example.gemini.data.live.AuraLiveVoiceManager.statusText.collectAsState()
    val spokenSentence by com.example.gemini.data.live.AuraLiveVoiceManager.spokenSentence.collectAsState()

    var displayedSpokenText by remember { mutableStateOf("") }
    LaunchedEffect(spokenSentence, livePhase) {
        if (livePhase != com.example.gemini.data.live.AuraLiveVoiceManager.Phase.SPEAKING) {
            displayedSpokenText = ""
            return@LaunchedEffect
        }
        val target = spokenSentence.trim()
        if (target.length > displayedSpokenText.length) {
            for (i in (displayedSpokenText.length + 1)..target.length) {
                displayedSpokenText = target.take(i)
                delay(14L) // typewriter character reveal for full readable sentence
            }
        } else {
            displayedSpokenText = target
        }
    }

    val rawName = userName?.trim().orEmpty()
    val greetingName = if (rawName.isNotBlank()) rawName.substringBefore(' ') else "Sir"
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val timeGreeting = when (hour) {
        in 4..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Hello"
    }
    val greetingTitle = "$timeGreeting, $greetingName"

    val legacyPhase = when (livePhase) {
        com.example.gemini.data.live.AuraLiveVoiceManager.Phase.LISTENING -> com.example.gemini.data.jarvis.JarvisController.Phase.LISTENING
        com.example.gemini.data.live.AuraLiveVoiceManager.Phase.THINKING,
        com.example.gemini.data.live.AuraLiveVoiceManager.Phase.WORKING -> com.example.gemini.data.jarvis.JarvisController.Phase.THINKING
        com.example.gemini.data.live.AuraLiveVoiceManager.Phase.SPEAKING -> com.example.gemini.data.jarvis.JarvisController.Phase.SPEAKING
        else -> com.example.gemini.data.jarvis.JarvisController.Phase.IDLE
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(18.dp))
        Text(
            text = greetingTitle,
            color = AuraInk,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(270.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onActionTap() },
                        onDoubleTap = { onActionDoubleTap() }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            com.example.gemini.ui.components.AuraOrbView(
                modifier = Modifier.fillMaxSize(),
                phase = legacyPhase,
                livePhase = livePhase,
                isPaused = isPaused,
                onOrbTapped = onActionTap
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
fun AuraHomeModeSwitcher(isVoice: Boolean, onSelect: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    com.example.gemini.ui.components.AuraModeToggle(
        currentMode = if (isVoice) "voice" else "chat",
        onModeSelected = { onSelect(it == "voice") },
        modifier = modifier
    )
}

@Composable
fun AuraVoiceActions(
    autoSend: Boolean,
    onActionTap: () -> Unit,
    onActionDoubleTap: () -> Unit = {},
    onScreen: () -> Unit,
    onToggleAuto: () -> Unit,
    modifier: Modifier = Modifier
) {
    val livePhase by com.example.gemini.data.live.AuraLiveVoiceManager.phase.collectAsState()
    val isSharing by com.example.gemini.data.vision.ScreenCaptureManager.getInstance().isSharing.collectAsState()
    val currentLang by com.example.gemini.data.voice.AgyVoicePreferences.language.collectAsState()
    var showLanguageDialog by remember { mutableStateOf(false) }

    val isLiveActive = com.example.gemini.data.live.AuraLiveVoiceManager.isLive()
    val (statusLabel, accentColor, iconVector) = when (livePhase) {
        com.example.gemini.data.live.AuraLiveVoiceManager.Phase.CONNECTING -> Triple("Connecting…", Color(0xFFD97706), Icons.Default.HourglassEmpty)
        com.example.gemini.data.live.AuraLiveVoiceManager.Phase.IDLE_READY -> Triple("Speak to ask", Color(0xFF475569), Icons.Rounded.Mic)
        com.example.gemini.data.live.AuraLiveVoiceManager.Phase.LISTENING -> Triple("Listening…", Color(0xFF16A34A), Icons.Rounded.Mic)
        com.example.gemini.data.live.AuraLiveVoiceManager.Phase.THINKING -> Triple("Thinking…", Color(0xFF8B5CF6), Icons.Default.HourglassEmpty)
        com.example.gemini.data.live.AuraLiveVoiceManager.Phase.WORKING -> Triple("Working…", Color(0xFFD97706), Icons.Default.HourglassEmpty)
        com.example.gemini.data.live.AuraLiveVoiceManager.Phase.SPEAKING -> Triple("Speaking…", Color(0xFF2563EB), Icons.Filled.VolumeUp)
        com.example.gemini.data.live.AuraLiveVoiceManager.Phase.MUTED -> Triple("Muted", Color(0xFFDC2626), Icons.Filled.MicOff)
        com.example.gemini.data.live.AuraLiveVoiceManager.Phase.IDLE -> if (isLiveActive) Triple("Speak to ask", Color(0xFF475569), Icons.Rounded.Mic) else Triple("Start", AuraBlue, Icons.Rounded.Mic)
    }

    val languageLabel = remember(currentLang) {
        if (currentLang == com.example.gemini.data.voice.AgyVoicePreferences.LANGUAGE_AUTO) "Auto"
        else com.example.gemini.data.voice.AgyVoicePreferences.languages.firstOrNull { it.first == currentLang }?.second ?: "Language"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center
        ) {
            VoiceSideAction(
                icon = Icons.Outlined.DesktopWindows,
                label = if (isSharing) "Sharing" else "Screen",
                iconTint = if (isSharing) Color(0xFF16A34A) else AuraInk,
                onClick = onScreen
            )
        }

        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color.White,
            shadowElevation = 3.dp,
            modifier = Modifier
                .height(56.dp)
                .defaultMinSize(minWidth = 160.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onActionTap() },
                        onDoubleTap = { onActionDoubleTap() }
                    )
                }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = iconVector,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusLabel,
                    color = accentColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center
        ) {
            VoiceSideAction(
                icon = Icons.Outlined.Language,
                label = languageLabel,
                iconTint = if (currentLang != com.example.gemini.data.voice.AgyVoicePreferences.LANGUAGE_AUTO) Color(0xFF2F6FED) else AuraInk,
                onClick = { showLanguageDialog = true }
            )
        }
    }

    if (showLanguageDialog) {
        VoiceLanguagePickerModal(
            currentLang = currentLang,
            onSelect = { tag ->
                com.example.gemini.data.voice.AgyVoicePreferences.setLanguage(tag)
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false }
        )
    }
}

@Composable
fun VoiceLanguagePickerModal(
    currentLang: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color.White,
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Voice Spoken Language",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1A1A1A)
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color(0xFF8A8A8E),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                val options = listOf(
                    Triple(com.example.gemini.data.voice.AgyVoicePreferences.LANGUAGE_AUTO, "Auto Detect", "Phone system language")
                ) + com.example.gemini.data.voice.AgyVoicePreferences.languages

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    options.forEach { (tag, label, desc) ->
                        val isSelected = currentLang == tag
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) Color(0xFF2F6FED).copy(alpha = 0.08f) else Color(0xFFF8F7FA),
                            border = if (isSelected) BorderStroke(1.dp, Color(0xFF2F6FED).copy(alpha = 0.4f)) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onSelect(tag) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = label,
                                        fontSize = 15.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (isSelected) Color(0xFF2F6FED) else Color(0xFF1A1A1A)
                                    )
                                    Text(
                                        text = desc,
                                        fontSize = 12.sp,
                                        color = Color(0xFF8A8A8E)
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = Color(0xFF2F6FED),
                                        modifier = Modifier.size(18.dp)
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

@Composable
private fun VoiceSideAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    iconTint: Color = AuraInk,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.wrapContentWidth()
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = Color.White,
            shadowElevation = 3.dp,
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = AuraInk,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}
