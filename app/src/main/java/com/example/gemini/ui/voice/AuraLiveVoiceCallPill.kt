package com.example.gemini.ui.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.live.AuraLiveVoiceManager
import com.example.gemini.data.vision.AuraScreenShareBridge

/**
 * AuraLiveVoiceCallPill: Minimized persistent calling pill for the Gemini Live Jarvis engine.
 * Displays real-time phase, transcript status, animated pulsing orb, permanent Screen Share button,
 * and End Call button.
 */
@Composable
fun AuraLiveVoiceCallPill(
    modifier: Modifier = Modifier
) {
    val phase by AuraLiveVoiceManager.phase.collectAsState()
    val statusText by AuraLiveVoiceManager.statusText.collectAsState()
    val amplitude by AuraLiveVoiceManager.amplitude.collectAsState()
    val isSharing by AuraScreenShareBridge.isSharing.collectAsState()

    val isLive = phase != AuraLiveVoiceManager.Phase.IDLE

    AnimatedVisibility(
        visible = isLive,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        val pulse by animateFloatAsState(
            targetValue = 1f + 0.35f * amplitude.coerceIn(0f, 1f),
            animationSpec = tween(120),
            label = "live_pill_pulse"
        )

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color.Transparent,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF2A1648), Color(0xFF14151E))
                        )
                    )
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                    .heightIn(min = 44.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pulsing glowing Orb
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer { scaleX = pulse; scaleY = pulse }
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                when (phase) {
                                    AuraLiveVoiceManager.Phase.SPEAKING -> listOf(Color(0xFF8B5CF6), Color(0xFF6D28D9), Color(0xFF3B0764))
                                    AuraLiveVoiceManager.Phase.THINKING -> listOf(Color(0xFFFBBF24), Color(0xFFD97706), Color(0xFF451A03))
                                    else -> listOf(Color(0xFF38BDF8), Color(0xFF2563EB), Color(0xFF0F172A))
                                }
                            )
                        )
                )

                Spacer(Modifier.width(10.dp))

                // Title & Live Transcript/Status
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Jarvis Live",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    when (phase) {
                                        AuraLiveVoiceManager.Phase.SPEAKING -> Color(0xFF8B5CF6).copy(alpha = 0.25f)
                                        AuraLiveVoiceManager.Phase.THINKING -> Color(0xFFFBBF24).copy(alpha = 0.25f)
                                        else -> Color(0xFF38BDF8).copy(alpha = 0.25f)
                                    }
                                )
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = phase.name.lowercase().replaceFirstChar { it.uppercase() },
                                color = when (phase) {
                                    AuraLiveVoiceManager.Phase.SPEAKING -> Color(0xFFA78BFA)
                                    AuraLiveVoiceManager.Phase.THINKING -> Color(0xFFFCD34D)
                                    else -> Color(0xFF7DD3FC)
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Text(
                        text = statusText.ifBlank { "Jarvis connected" },
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Permanent Screen Share Toggle Button
                IconButton(
                    onClick = { AuraScreenShareBridge.toggleScreenShare() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DesktopWindows,
                        contentDescription = if (isSharing) "Screen Sharing Active" else "Share Screen",
                        tint = if (isSharing) Color(0xFF22C55E) else Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(Modifier.width(4.dp))

                // End Call Button
                Surface(
                    onClick = { AuraLiveVoiceManager.stopSession() },
                    shape = CircleShape,
                    color = Color(0xFFE5484D),
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.CallEnd,
                            contentDescription = "End call",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
