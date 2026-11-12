package com.example.gemini.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.domain.model.AiModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentSelectorBottomSheet(
    selectedModelId: String = "",
    availableModels: List<AiModel> = emptyList(),
    onSelectModel: (String) -> Unit = {},
    thinkingPreference: com.example.gemini.domain.model.ThinkingPreference = com.example.gemini.domain.model.ThinkingPreference(),
    onPreferenceSelected: ((com.example.gemini.domain.model.ThinkingPreference) -> Unit)? = null,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onPickFromFiles: () -> Unit,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val scrollState = rememberScrollState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        scrimColor = Color.Black.copy(alpha = 0.32f),
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFC9C9CC))
            )
        },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            // Header row (56dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color(0xFF1A1A1A),
                        modifier = Modifier.size(22.dp)
                    )
                }

                Text(
                    text = "Add to chat",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1A1A1A),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )

                // Balanced spacing on the right
                Spacer(modifier = Modifier.size(48.dp))
            }

            // Scrollable Content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
            ) {
                // Row of 3 EQUAL cards
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AttachmentCard(
                        icon = Icons.Outlined.Image,
                        label = "Photo /\nImage",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onDismiss()
                            onPickImage()
                        }
                    )

                    AttachmentCard(
                        icon = Icons.Outlined.Description,
                        label = "Document /\nFile",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onDismiss()
                            onPickFile()
                        }
                    )

                    AttachmentCard(
                        icon = Icons.Outlined.FolderOpen,
                        label = "From\nFiles",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onDismiss()
                            onPickFromFiles()
                        }
                    )
                }

                // Caption 12dp below card row
                Text(
                    text = "For large files, use From Files",
                    fontSize = 12.sp,
                    color = Color(0xFF8A8A8E),
                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, end = 20.dp)
                )

                // Model Section
                if (availableModels.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "Model",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF8A8A8E),
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White,
                        shadowElevation = 2.dp,
                        border = BorderStroke(1.dp, Color(0x10000000))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            availableModels.forEachIndexed { index, model ->
                                val isSelected = model.id == selectedModelId
                                val displayName = model.displayName.ifBlank { model.id }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 56.dp)
                                        .clip(
                                            when {
                                                availableModels.size == 1 -> RoundedCornerShape(20.dp)
                                                index == 0 -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                                                index == availableModels.lastIndex -> RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)
                                                else -> RoundedCornerShape(0.dp)
                                            }
                                        )
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onSelectModel(model.id)
                                        }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = displayName,
                                            fontSize = 16.sp,
                                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                                            color = Color(0xFF1A1A1A)
                                        )
                                        val tierOrDesc = model.tier?.let { "$it effort" } ?: model.description.takeIf { it.isNotBlank() }
                                        if (!tierOrDesc.isNullOrBlank()) {
                                            Text(
                                                text = tierOrDesc,
                                                fontSize = 13.sp,
                                                color = Color(0xFF8A8A8E),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    AnimatedVisibility(
                                        visible = isSelected,
                                        enter = fadeIn(),
                                        exit = fadeOut()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = Color(0xFF2F6FED),
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }

                                if (index < availableModels.lastIndex) {
                                    HorizontalDivider(
                                        thickness = 0.5.dp,
                                        color = Color(0xFFECECEE),
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Thinking preference segmented control if available
                    if (onPreferenceSelected != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Thinking Level",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF8A8A8E),
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White,
                            shadowElevation = 2.dp,
                            border = BorderStroke(1.dp, Color(0x10000000))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf(
                                    com.example.gemini.domain.model.ThinkingLevel.LOW to "Low",
                                    com.example.gemini.domain.model.ThinkingLevel.MEDIUM to "Medium",
                                    com.example.gemini.domain.model.ThinkingLevel.HIGH to "High"
                                ).forEach { (lvl, level) ->
                                    val isPrefSelected = thinkingPreference.level == lvl
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(38.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onPreferenceSelected?.invoke(thinkingPreference.copy(level = lvl))
                                            },
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isPrefSelected) Color(0xFF2F6FED).copy(alpha = 0.12f) else Color.Transparent,
                                        border = if (isPrefSelected) BorderStroke(1.dp, Color(0xFF2F6FED).copy(alpha = 0.4f)) else null
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            Text(
                                                text = level,
                                                fontSize = 14.sp,
                                                fontWeight = if (isPrefSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (isPrefSelected) Color(0xFF2F6FED) else Color(0xFF1A1A1A)
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
    }
}

@Composable
private fun AttachmentCard(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    SoftElevatedBox(
        modifier = modifier.height(104.dp),
        shape = RoundedCornerShape(20.dp),
        backgroundColor = Color.White,
        elevation = 2.dp,
        border = BorderStroke(1.dp, Color(0x10000000)),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF0EFED)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label.replace("\n", " "),
                    tint = Color(0xFF2B2B2B),
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = label,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Normal,
                color = Color(0xFF1A1A1A),
                textAlign = TextAlign.Center,
                maxLines = 2,
                lineHeight = 17.sp
            )
        }
    }
}
