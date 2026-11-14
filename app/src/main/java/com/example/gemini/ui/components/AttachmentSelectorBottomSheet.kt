package com.example.gemini.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    onPickCamera: () -> Unit = {},
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
                .padding(bottom = 12.dp)
        ) {
            // Header row (52dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color(0xFF1A1A1A),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Text(
                    text = "Add to chat",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1A1A1A),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )

                // Balanced spacing on the right
                Spacer(modifier = Modifier.size(44.dp))
            }

            // Scrollable Content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
            ) {
                // Horizontally scrollable attachment cards
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AttachmentCard(
                        icon = Icons.Outlined.PhotoCamera,
                        label = "Camera /\nPhoto",
                        modifier = Modifier.width(96.dp),
                        onClick = {
                            onDismiss()
                            onPickCamera()
                        }
                    )

                    AttachmentCard(
                        icon = Icons.Outlined.Image,
                        label = "Photo /\nImage",
                        modifier = Modifier.width(96.dp),
                        onClick = {
                            onDismiss()
                            onPickImage()
                        }
                    )

                    AttachmentCard(
                        icon = Icons.Outlined.Description,
                        label = "Document /\nFile",
                        modifier = Modifier.width(96.dp),
                        onClick = {
                            onDismiss()
                            onPickFile()
                        }
                    )

                    AttachmentCard(
                        icon = Icons.Outlined.FolderOpen,
                        label = "From\nFiles",
                        modifier = Modifier.width(96.dp),
                        onClick = {
                            onDismiss()
                            onPickFromFiles()
                        }
                    )
                }

                // Subtle caption
                Text(
                    text = "For large files, use From Files",
                    fontSize = 11.5.sp,
                    color = Color(0xFF8A8A8E),
                    modifier = Modifier.padding(start = 20.dp, top = 8.dp, end = 20.dp)
                )

                // Model Section - Filtered to Gemini models only (Low, Medium, High)
                val geminiModels = remember(availableModels) {
                    val filtered = availableModels.filter {
                        it.family == com.example.gemini.domain.model.ModelFamily.GEMINI ||
                        it.id.contains("gemini", ignoreCase = true) ||
                        it.displayName.contains("gemini", ignoreCase = true)
                    }
                    if (filtered.isNotEmpty()) filtered else availableModels
                }

                if (geminiModels.isNotEmpty()) {
                    var isModelExpanded by remember { mutableStateOf(false) }
                    val currentModel = geminiModels.find { it.id == selectedModelId }
                        ?: geminiModels.firstOrNull()

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Model",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF8A8A8E)
                        )
                        if (geminiModels.size > 1) {
                            Text(
                                text = if (isModelExpanded) "Collapse" else "Change",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF2F6FED),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { isModelExpanded = !isModelExpanded }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = Color.White,
                        shadowElevation = 2.dp,
                        border = BorderStroke(1.dp, Color(0x10000000))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Active Model Row (Always visible, checkmark removed)
                            if (currentModel != null) {
                                val displayName = currentModel.displayName.ifBlank { currentModel.id }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 52.dp)
                                        .clip(
                                            if (isModelExpanded && geminiModels.size > 1) {
                                                RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
                                            } else {
                                                RoundedCornerShape(18.dp)
                                            }
                                        )
                                        .clickable {
                                            if (geminiModels.size > 1) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                isModelExpanded = !isModelExpanded
                                            }
                                        }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = displayName,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFF1A1A1A)
                                        )
                                        val tierOrDesc = currentModel.tier?.let { "$it effort" }
                                            ?: currentModel.description.takeIf { it.isNotBlank() }
                                        if (!tierOrDesc.isNullOrBlank()) {
                                            Text(
                                                text = tierOrDesc,
                                                fontSize = 12.sp,
                                                color = Color(0xFF8A8A8E),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    if (geminiModels.size > 1) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(
                                            imageVector = if (isModelExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                            contentDescription = if (isModelExpanded) "Collapse" else "Expand",
                                            tint = Color(0xFF8A8A8E),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }

                            // Expanded list of other available models (Scrollable ~140dp max height)
                            AnimatedVisibility(
                                visible = isModelExpanded && geminiModels.size > 1,
                                enter = fadeIn(),
                                exit = fadeOut()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 140.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    val otherModels = geminiModels.filter { it.id != currentModel?.id }
                                    otherModels.forEachIndexed { index, model ->
                                        HorizontalDivider(
                                            thickness = 0.5.dp,
                                            color = Color(0xFFECECEE),
                                            modifier = Modifier.padding(horizontal = 16.dp)
                                        )

                                        val displayName = model.displayName.ifBlank { model.id }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .defaultMinSize(minHeight = 50.dp)
                                                .clip(
                                                    if (index == otherModels.lastIndex) {
                                                        RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp)
                                                    } else {
                                                        RoundedCornerShape(0.dp)
                                                    }
                                                )
                                                .clickable {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    onSelectModel(model.id)
                                                    isModelExpanded = false
                                                }
                                                .padding(horizontal = 16.dp, vertical = 9.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = displayName,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Normal,
                                                    color = Color(0xFF1A1A1A)
                                                )
                                                val tierOrDesc = model.tier?.let { "$it effort" }
                                                    ?: model.description.takeIf { it.isNotBlank() }
                                                if (!tierOrDesc.isNullOrBlank()) {
                                                    Text(
                                                        text = tierOrDesc,
                                                        fontSize = 12.sp,
                                                        color = Color(0xFF8A8A8E),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
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

                // Generous bottom spacing for navigation bar and shadows
                Spacer(modifier = Modifier.height(24.dp))
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
        modifier = modifier.height(96.dp),
        shape = RoundedCornerShape(18.dp),
        backgroundColor = Color.White,
        elevation = 2.dp,
        border = BorderStroke(1.dp, Color(0x10000000)),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF0EFED)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label.replace("\n", " "),
                    tint = Color(0xFF2B2B2B),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                color = Color(0xFF1A1A1A),
                textAlign = TextAlign.Center,
                maxLines = 2,
                lineHeight = 15.sp,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
