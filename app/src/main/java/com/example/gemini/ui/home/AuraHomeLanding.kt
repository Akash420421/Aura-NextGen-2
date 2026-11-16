package com.example.gemini.ui.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AuraMuted = Color(0xFF8B8D98)

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
        val showSuggestions = maxHeight >= 640.dp

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
