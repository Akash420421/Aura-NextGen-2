package com.example.gemini.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
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
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Hi Sir, what's on your mind?",
            color = Color(0xFF171717), fontSize = 28.sp, lineHeight = 36.sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Ask a question, brainstorm ideas, or start coding",
            color = AuraMuted, fontSize = 16.sp, lineHeight = 22.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        val suggestions = listOf(
            "⚡ Create a mobile web app",
            "🔍 Analyze code and fix bugs",
            "🎨 Design a landing page"
        )
        suggestions.forEach { prompt ->
            Surface(
                onClick = { onPromptSelected(prompt.substringAfter(" ")) },
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                shadowElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
            ) {
                Text(
                    text = prompt,
                    color = Color(0xFF374151),
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)
                )
            }
        }
    }
}

@Composable
fun AuraVoiceWelcome(onStart: () -> Unit, modifier: Modifier = Modifier) {
    val phase by com.example.gemini.data.jarvis.JarvisController.phase.collectAsState()
    val prompt = when (phase) {
        com.example.gemini.data.jarvis.JarvisController.Phase.LISTENING -> "Listening — tap the orb to send"
        com.example.gemini.data.jarvis.JarvisController.Phase.THINKING -> "Aura is thinking…"
        com.example.gemini.data.jarvis.JarvisController.Phase.SPEAKING -> "Aura is speaking — tap to talk"
        else -> "Tap the orb to start conversation"
    }
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(0.40f))
        Text("Hi Sir,", color = AuraInk, fontSize = 38.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(prompt, color = AuraMuted, fontSize = 19.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(0.28f))
        AuraParticleOrb(modifier = Modifier.size(278.dp).clickable(onClick = onStart), phase = phase)
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
    onStart: () -> Unit,
    onScreen: () -> Unit,
    onToggleAuto: () -> Unit,
    modifier: Modifier = Modifier
) {
    val phase by com.example.gemini.data.jarvis.JarvisController.phase.collectAsState()
    val startLabel = when (phase) {
        com.example.gemini.data.jarvis.JarvisController.Phase.LISTENING -> "Tap to Send"
        com.example.gemini.data.jarvis.JarvisController.Phase.THINKING -> "Thinking…"
        com.example.gemini.data.jarvis.JarvisController.Phase.SPEAKING -> "Tap to Talk"
        else -> "Tap to Start"
    }
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        VoiceSideAction(Icons.Outlined.DesktopWindows, "Screen", onScreen)
        Surface(
            onClick = {
                when (phase) {
                    com.example.gemini.data.jarvis.JarvisController.Phase.LISTENING -> com.example.gemini.data.jarvis.JarvisController.finishListening()
                    com.example.gemini.data.jarvis.JarvisController.Phase.SPEAKING -> com.example.gemini.data.jarvis.JarvisController.silence()
                    com.example.gemini.data.jarvis.JarvisController.Phase.THINKING -> com.example.gemini.data.jarvis.JarvisController.stopEverything()
                    else -> onStart()
                }
            },
            shape = CircleShape, color = Color.White,
            shadowElevation = 4.dp, modifier = Modifier.weight(1f).padding(horizontal = 12.dp).height(56.dp)
        ) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (phase == com.example.gemini.data.jarvis.JarvisController.Phase.SPEAKING) Icons.Filled.Stop
                        else Icons.Rounded.Mic,
                    contentDescription = null,
                    tint = AuraBlue,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(startLabel, color = AuraBlue, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        VoiceSideAction(Icons.Outlined.Language, if (autoSend) "Auto on" else "Auto", onToggleAuto)
    }
}

@Composable
private fun VoiceSideAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp).clickable(onClick = onClick)) {
        Surface(shape = CircleShape, color = Color.White, shadowElevation = 3.dp, modifier = Modifier.size(56.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = AuraInk, modifier = Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = AuraInk, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

private data class Particle(val x: Float, val y: Float, val z: Float, val size: Float, val hue: Float)

@Composable
private fun AuraParticleOrb(
    modifier: Modifier = Modifier,
    phase: com.example.gemini.data.jarvis.JarvisController.Phase
) {
    val transition = rememberInfiniteTransition(label = "aura_particle_morph")
    val morphDuration = when (phase) {
        com.example.gemini.data.jarvis.JarvisController.Phase.LISTENING -> 1900
        com.example.gemini.data.jarvis.JarvisController.Phase.THINKING -> 1100
        com.example.gemini.data.jarvis.JarvisController.Phase.SPEAKING -> 800
        else -> 4200
    }
    val morph by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(morphDuration, easing = LinearEasing), RepeatMode.Reverse),
        label = "sphere_to_triangle"
    )
    val rotation by transition.animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(12000, easing = LinearEasing), RepeatMode.Restart),
        label = "particle_rotation"
    )
    val particles = androidx.compose.runtime.remember {
        List(720) { i ->
            val n = 720f
            val y = 1f - 2f * (i + 0.5f) / n
            val radius = sqrt(1f - y * y)
            val theta = i * 2.3999632f
            Particle(cos(theta) * radius, y, sin(theta) * radius, 0.65f + ((i * 37) % 100) / 100f * 1.35f, ((i * 61) % 100) / 100f)
        }
    }
    Canvas(modifier = modifier) {
        val scale = size.minDimension * 0.46f
        val c = center
        val ca = cos(rotation)
        val sa = sin(rotation)
        particles.forEach { p ->
            val sphereX = p.x
            val sphereY = p.y
            val latitudeRadius = sqrt((1f - p.y * p.y).coerceAtLeast(0.001f))
            val triangleWidth = (p.y + 1f) * 0.42f
            val triangleX = (p.x / latitudeRadius).coerceIn(-1f, 1f) * triangleWidth
            val triangleY = -0.86f + (p.y + 1f) * 0.86f
            val blend = morph * morph * (3f - 2f * morph)
            val x = sphereX + (triangleX - sphereX) * blend
            val y = sphereY + (triangleY - sphereY) * blend
            val z = p.z * (1f - blend) + (if (p.z >= 0) 0.52f else -0.52f) * blend
            val rx = x * ca - z * sa
            val rz = x * sa + z * ca
            val perspective = 0.82f + (rz + 1f) * 0.16f
            val alpha = (0.28f + (rz + 1f) * 0.31f).coerceIn(0.18f, 0.9f)
            val color = when {
                p.hue > 0.78f -> Color(0xFF9168E8)
                p.hue > 0.35f -> Color(0xFF3381E8)
                else -> Color(0xFF6CA9F3)
            }
            drawCircle(color.copy(alpha = alpha), (p.size * (0.72f + rz * 0.22f)).dp.toPx(),
                Offset(c.x + rx * scale * perspective, c.y + y * scale * perspective))
        }
    }
}
