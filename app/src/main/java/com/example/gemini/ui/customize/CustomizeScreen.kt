package com.example.gemini.ui.customize

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.gemini.data.preferences.AuraContact
import com.example.gemini.data.preferences.AuraCredential
import com.example.gemini.data.preferences.AuraCustomizePreferences
import com.example.gemini.data.preferences.AuraLocationSettings
import com.example.gemini.theme.isAppInDarkTheme

enum class CustomizeSubPage {
    PERSONALITY,
    MEMORY,
    PLUGINS,
    EMAIL,
    CALENDAR,
    CONTACTS,
    PASSWORDS,
    LOCATION
}

@Composable
fun CustomizeScreen(
    onBack: () -> Unit,
    onOpenPersonality: (() -> Unit)? = null,
    onOpenMemory: (() -> Unit)? = null,
    onOpenPlugins: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var activeSubPage by remember { mutableStateOf<CustomizeSubPage?>(null) }

    // Live states from AuraCustomizePreferences
    var currentPersonality by remember { mutableStateOf(AuraCustomizePreferences.getPersonality(context)) }
    var currentMemories by remember { mutableStateOf(AuraCustomizePreferences.getMemories(context)) }
    var currentContacts by remember { mutableStateOf(AuraCustomizePreferences.getContacts(context)) }
    var currentCredentials by remember { mutableStateOf(AuraCustomizePreferences.getCredentials(context)) }
    var currentLocation by remember { mutableStateOf(AuraCustomizePreferences.getLocationSettings(context)) }
    var currentEmail by remember { mutableStateOf(AuraCustomizePreferences.getEmail(context)) }

    val isDark = isAppInDarkTheme()
    val bgColor = MaterialTheme.colorScheme.background
    val cardBg = if (isDark) Color(0xFF1C1C1E) else Color.White
    val textColor = if (isDark) Color(0xFFEDEDED) else Color(0xFF1A1A1A)
    val subtitleColor = if (isDark) Color(0xFFA1A1AA) else Color(0xFF8A8A8E)
    val dividerColor = if (isDark) Color(0xFF2C2C2E) else Color(0xFFECECEE)
    val topBarBg = if (isDark) Color(0xFF1C1C1E) else Color.White

    // Handle SubPages
    if (activeSubPage != null) {
        when (activeSubPage!!) {
            CustomizeSubPage.PERSONALITY -> {
                PersonalitySubScreen(
                    currentPreset = currentPersonality,
                    onSave = { preset, instructions ->
                        AuraCustomizePreferences.setPersonality(context, preset)
                        AuraCustomizePreferences.setCustomInstructions(context, instructions)
                        currentPersonality = preset
                        activeSubPage = null
                    },
                    onBack = { activeSubPage = null }
                )
            }
            CustomizeSubPage.MEMORY -> {
                MemorySubScreen(
                    memories = currentMemories,
                    onAdd = { note ->
                        AuraCustomizePreferences.addMemory(context, note)
                        currentMemories = AuraCustomizePreferences.getMemories(context)
                    },
                    onRemove = { index ->
                        AuraCustomizePreferences.removeMemory(context, index)
                        currentMemories = AuraCustomizePreferences.getMemories(context)
                    },
                    onClear = {
                        AuraCustomizePreferences.clearMemories(context)
                        currentMemories = emptyList()
                    },
                    onBack = { activeSubPage = null }
                )
            }
            CustomizeSubPage.PLUGINS -> {
                if (onOpenPlugins != null) {
                    LaunchedEffect(Unit) {
                        activeSubPage = null
                        onOpenPlugins()
                    }
                } else {
                    activeSubPage = null
                }
            }
            CustomizeSubPage.EMAIL -> {
                EmailSubScreen(
                    currentEmail = currentEmail,
                    onSave = { email ->
                        AuraCustomizePreferences.setEmail(context, email)
                        currentEmail = email
                        activeSubPage = null
                    },
                    onBack = { activeSubPage = null }
                )
            }
            CustomizeSubPage.CALENDAR -> {
                CalendarSubScreen(
                    onBack = { activeSubPage = null }
                )
            }
            CustomizeSubPage.CONTACTS -> {
                ContactsSubScreen(
                    contacts = currentContacts,
                    onAdd = { contact ->
                        AuraCustomizePreferences.addContact(context, contact)
                        currentContacts = AuraCustomizePreferences.getContacts(context)
                    },
                    onRemove = { id ->
                        AuraCustomizePreferences.removeContact(context, id)
                        currentContacts = AuraCustomizePreferences.getContacts(context)
                    },
                    onBack = { activeSubPage = null }
                )
            }
            CustomizeSubPage.PASSWORDS -> {
                PasswordsSubScreen(
                    credentials = currentCredentials,
                    onAdd = { cred ->
                        AuraCustomizePreferences.addCredential(context, cred)
                        currentCredentials = AuraCustomizePreferences.getCredentials(context)
                    },
                    onRemove = { id ->
                        AuraCustomizePreferences.removeCredential(context, id)
                        currentCredentials = AuraCustomizePreferences.getCredentials(context)
                    },
                    onBack = { activeSubPage = null }
                )
            }
            CustomizeSubPage.LOCATION -> {
                LocationSubScreen(
                    settings = currentLocation,
                    onSave = { loc ->
                        AuraCustomizePreferences.saveLocationSettings(context, loc)
                        currentLocation = loc
                        activeSubPage = null
                    },
                    onBack = { activeSubPage = null }
                )
            }
        }
        return
    }

    BackHandler(onBack = onBack)

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

            Text(
                text = "Customize",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = textColor,
                textAlign = TextAlign.Center
            )
        }

        // List of Customize Settings
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Group 1: Behavior & Knowledge
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = cardBg,
                shadowElevation = 1.dp,
                border = BorderStroke(1.dp, Color(0x0C000000)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    CustomizeRow(
                        icon = Icons.Outlined.Psychology,
                        badgeBg = Color(0xFFEDE9FE),
                        badgeFg = Color(0xFF7C3AED),
                        title = "Personality",
                        value = currentPersonality,
                        textColor = textColor,
                        subtitleColor = subtitleColor,
                        onClick = { activeSubPage = CustomizeSubPage.PERSONALITY }
                    )
                    HorizontalDivider(
                        color = dividerColor,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(start = 64.dp, end = 16.dp)
                    )
                    CustomizeRow(
                        icon = Icons.Outlined.Lightbulb,
                        badgeBg = Color(0xFFFEF3C7),
                        badgeFg = Color(0xFFD97706),
                        title = "Memory",
                        value = "${currentMemories.size} ${if (currentMemories.size == 1) "note" else "notes"}",
                        textColor = textColor,
                        subtitleColor = subtitleColor,
                        onClick = { activeSubPage = CustomizeSubPage.MEMORY }
                    )
                    HorizontalDivider(
                        color = dividerColor,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(start = 64.dp, end = 16.dp)
                    )
                    CustomizeRow(
                        icon = Icons.Outlined.Extension,
                        badgeBg = Color(0xFFE0E7FF),
                        badgeFg = Color(0xFF4F46E5),
                        title = "Plugins",
                        value = "MCP & Skills",
                        textColor = textColor,
                        subtitleColor = subtitleColor,
                        onClick = { activeSubPage = CustomizeSubPage.PLUGINS }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Group 2: Integrations & Vault
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = cardBg,
                shadowElevation = 1.dp,
                border = BorderStroke(1.dp, Color(0x0C000000)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    CustomizeRow(
                        icon = Icons.Outlined.Email,
                        badgeBg = Color(0xFFFEE2E2),
                        badgeFg = Color(0xFFDC2626),
                        title = "Email",
                        value = currentEmail,
                        textColor = textColor,
                        subtitleColor = subtitleColor,
                        onClick = { activeSubPage = CustomizeSubPage.EMAIL }
                    )
                    HorizontalDivider(
                        color = dividerColor,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(start = 64.dp, end = 16.dp)
                    )
                    CustomizeRow(
                        icon = Icons.Outlined.CalendarMonth,
                        badgeBg = Color(0xFFFFEDD5),
                        badgeFg = Color(0xFFEA580C),
                        title = "Calendar",
                        value = "Synced",
                        textColor = textColor,
                        subtitleColor = subtitleColor,
                        onClick = { activeSubPage = CustomizeSubPage.CALENDAR }
                    )
                    HorizontalDivider(
                        color = dividerColor,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(start = 64.dp, end = 16.dp)
                    )
                    CustomizeRow(
                        icon = Icons.Outlined.Contacts,
                        badgeBg = Color(0xFFDCFCE7),
                        badgeFg = Color(0xFF16A34A),
                        title = "Contacts",
                        value = "${currentContacts.size} ${if (currentContacts.size == 1) "contact" else "contacts"}",
                        textColor = textColor,
                        subtitleColor = subtitleColor,
                        onClick = { activeSubPage = CustomizeSubPage.CONTACTS }
                    )
                    HorizontalDivider(
                        color = dividerColor,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(start = 64.dp, end = 16.dp)
                    )
                    CustomizeRow(
                        icon = Icons.Outlined.VpnKey,
                        badgeBg = Color(0xFFF3E8FF),
                        badgeFg = Color(0xFF9333EA),
                        title = "Passwords",
                        value = "${currentCredentials.size} ${if (currentCredentials.size == 1) "item" else "items"}",
                        textColor = textColor,
                        subtitleColor = subtitleColor,
                        onClick = { activeSubPage = CustomizeSubPage.PASSWORDS }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Group 3: Location
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = cardBg,
                shadowElevation = 1.dp,
                border = BorderStroke(1.dp, Color(0x0C000000)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    CustomizeRow(
                        icon = Icons.Outlined.LocationOn,
                        badgeBg = Color(0xFFE0F2FE),
                        badgeFg = Color(0xFF0284C7),
                        title = "Location",
                        value = if (currentLocation.homeAddress.isNotBlank()) "Configured" else "Set up",
                        textColor = textColor,
                        subtitleColor = subtitleColor,
                        onClick = { activeSubPage = CustomizeSubPage.LOCATION }
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun CustomizeRow(
    icon: ImageVector,
    badgeBg: Color,
    badgeFg: Color,
    title: String,
    value: String,
    textColor: Color,
    subtitleColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pixel-perfect 36dp Circular Badge
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(badgeBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = badgeFg,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Text(
                text = title,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Medium,
                color = textColor,
                modifier = Modifier.weight(1f)
            )

            if (value.isNotBlank()) {
                Text(
                    text = value,
                    fontSize = 13.5.sp,
                    color = subtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(6.dp))
            }

            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = subtitleColor,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// -----------------------------------------------------------------------------------------
// SubScreens
// -----------------------------------------------------------------------------------------

@Composable
private fun PersonalitySubScreen(
    currentPreset: String,
    onSave: (String, String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var selectedPreset by remember { mutableStateOf(currentPreset) }
    var customInstructions by remember { mutableStateOf(AuraCustomizePreferences.getCustomInstructions(context)) }

    val presets = listOf(
        Triple("Jarvis", "Witty, British-toned, concise, sophisticated AI companion.", Color(0xFF7C3AED)),
        Triple("Professional", "Structured, comprehensive, formal, objective tone.", Color(0xFF2563EB)),
        Triple("Friendly", "Warm, enthusiastic, conversational and empathetic.", Color(0xFF16A34A)),
        Triple("Direct", "Minimalist, code-first, straight to the point, zero fluff.", Color(0xFFEA580C))
    )

    SubScreenScaffold(title = "Personality", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "Assistant Tone",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF8A8A8E),
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            presets.forEach { (name, desc, color) ->
                val isSelected = selectedPreset.equals(name, ignoreCase = true)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                    shadowElevation = 2.dp,
                    border = BorderStroke(1.dp, if (isSelected) color.copy(alpha = 0.5f) else Color(0x0C000000)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { selectedPreset = name }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1A1A1A))
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(text = desc, fontSize = 12.5.sp, color = Color(0xFF8A8A8E))
                        }
                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = "Selected", tint = color, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Custom Instructions",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF8A8A8E),
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            OutlinedTextField(
                value = customInstructions,
                onValueChange = { customInstructions = it },
                placeholder = { Text("e.g. Always respond in bullet points, focus on modern Android Compose...", fontSize = 13.5.sp) },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    focusedBorderColor = Color(0xFF2F6FED),
                    unfocusedBorderColor = Color(0x18000000)
                ),
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { onSave(selectedPreset, customInstructions) },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2F6FED)),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("Save Personality", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
        }
    }
}

@Composable
private fun MemorySubScreen(
    memories: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    var showAddDialog by remember { mutableStateOf(false) }
    var newMemoryText by remember { mutableStateOf("") }

    SubScreenScaffold(
        title = "Memory",
        onBack = onBack,
        actions = {
            if (memories.isNotEmpty()) {
                TextButton(onClick = onClear) {
                    Text("Clear All", color = Color(0xFFDC2626), fontSize = 13.5.sp)
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "Facts and instructions Aura has remembered across your conversations to personalize your responses.",
                fontSize = 13.5.sp,
                color = Color(0xFF8A8A8E),
                lineHeight = 18.sp,
                modifier = Modifier.padding(bottom = 14.dp)
            )

            if (memories.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No remembered notes yet.", color = Color(0xFF8A8A8E), fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(memories.indices.toList()) { index ->
                        val memory = memories[index]
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color.White,
                            shadowElevation = 1.dp,
                            border = BorderStroke(1.dp, Color(0x0C000000)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = memory,
                                    fontSize = 14.sp,
                                    color = Color(0xFF1A1A1A),
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { onRemove(index) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Delete",
                                        tint = Color(0xFF8A8A8E),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { showAddDialog = true },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2F6FED)),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add Memory Note", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }

    if (showAddDialog) {
        Dialog(onDismissRequest = { showAddDialog = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Add Memory", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1A1A1A))
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newMemoryText,
                        onValueChange = { newMemoryText = it },
                        placeholder = { Text("e.g. Always use metric units", fontSize = 13.5.sp) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
                        Button(
                            onClick = {
                                if (newMemoryText.isNotBlank()) {
                                    onAdd(newMemoryText)
                                    newMemoryText = ""
                                    showAddDialog = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2F6FED))
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactsSubScreen(
    contacts: List<AuraContact>,
    onAdd: (AuraContact) -> Unit,
    onRemove: (String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    var showAddDialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var relation by remember { mutableStateOf("Family") }

    SubScreenScaffold(title = "Contacts", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "Primary contacts for Aura / Jarvis voice calling and messaging integration.",
                fontSize = 13.5.sp,
                color = Color(0xFF8A8A8E),
                modifier = Modifier.padding(bottom = 14.dp)
            )

            if (contacts.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No contacts added yet.", color = Color(0xFF8A8A8E), fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(contacts, key = { it.id }) { c ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color.White,
                            shadowElevation = 1.dp,
                            border = BorderStroke(1.dp, Color(0x0C000000)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFDCFCE7)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = c.name.take(1).uppercase(),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF16A34A)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = c.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1A1A1A))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFFF0F0F2))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(text = c.relation, fontSize = 10.5.sp, color = Color(0xFF6B7280))
                                        }
                                    }
                                    Text(text = c.phone, fontSize = 12.5.sp, color = Color(0xFF8A8A8E))
                                }
                                IconButton(
                                    onClick = { onRemove(c.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Delete",
                                        tint = Color(0xFF8A8A8E),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { showAddDialog = true },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add Contact", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }

    if (showAddDialog) {
        Dialog(onDismissRequest = { showAddDialog = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Add Contact", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1A1A1A))
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Phone Number") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = relation,
                        onValueChange = { relation = it },
                        label = { Text("Relationship (e.g. Family, Friend, Work)") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
                        Button(
                            onClick = {
                                if (name.isNotBlank() && phone.isNotBlank()) {
                                    onAdd(AuraContact(name = name, phone = phone, relation = relation.ifBlank { "Friend" }))
                                    name = ""
                                    phone = ""
                                    showAddDialog = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordsSubScreen(
    credentials: List<AuraCredential>,
    onAdd: (AuraCredential) -> Unit,
    onRemove: (String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var revealedIds by remember { mutableStateOf(setOf<String>()) }

    SubScreenScaffold(title = "Passwords & Vault", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "Secure local vault for API keys, credentials, and sensitive accounts.",
                fontSize = 13.5.sp,
                color = Color(0xFF8A8A8E),
                modifier = Modifier.padding(bottom = 14.dp)
            )

            if (credentials.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No credentials saved yet.", color = Color(0xFF8A8A8E), fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(credentials, key = { it.id }) { c ->
                        val isRevealed = revealedIds.contains(c.id)
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color.White,
                            shadowElevation = 1.dp,
                            border = BorderStroke(1.dp, Color(0x0C000000)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = c.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1A1A1A))
                                    if (c.account.isNotBlank()) {
                                        Text(text = c.account, fontSize = 12.sp, color = Color(0xFF8A8A8E))
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (isRevealed) c.secret else "••••••••••••",
                                        fontSize = 13.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        color = if (isRevealed) Color(0xFF7C3AED) else Color(0xFF6B7280)
                                    )
                                }

                                // Toggle Visibility
                                IconButton(
                                    onClick = {
                                        revealedIds = if (isRevealed) revealedIds - c.id else revealedIds + c.id
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Reveal",
                                        tint = Color(0xFF8A8A8E),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                // Copy button
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText(c.title, c.secret))
                                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ContentCopy,
                                        contentDescription = "Copy",
                                        tint = Color(0xFF8A8A8E),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                // Delete
                                IconButton(
                                    onClick = { onRemove(c.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Delete",
                                        tint = Color(0xFF8A8A8E),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { showAddDialog = true },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9333EA)),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add Credential", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }

    if (showAddDialog) {
        Dialog(onDismissRequest = { showAddDialog = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Add Credential", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1A1A1A))
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title (e.g. OpenAI Key, AWS)") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = account,
                        onValueChange = { account = it },
                        label = { Text("Username / Account (optional)") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = { Text("Password or Secret Key") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
                        Button(
                            onClick = {
                                if (title.isNotBlank() && secret.isNotBlank()) {
                                    onAdd(AuraCredential(title = title, account = account, secret = secret))
                                    title = ""
                                    account = ""
                                    secret = ""
                                    showAddDialog = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9333EA))
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationSubScreen(
    settings: AuraLocationSettings,
    onSave: (AuraLocationSettings) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    var home by remember { mutableStateOf(settings.homeAddress) }
    var work by remember { mutableStateOf(settings.workAddress) }
    var useGps by remember { mutableStateOf(settings.useLiveGps) }

    SubScreenScaffold(title = "Location", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "Saved locations for quick commute calculations, local weather, and contextual assistant queries.",
                fontSize = 13.5.sp,
                color = Color(0xFF8A8A8E),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedTextField(
                value = home,
                onValueChange = { home = it },
                label = { Text("Home Address") },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = work,
                onValueChange = { work = it },
                label = { Text("Work / Office Address") },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.White,
                shadowElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Use Real-Time GPS", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("Allow Aura to query current device location when relevant", fontSize = 12.sp, color = Color(0xFF8A8A8E))
                    }
                    Switch(
                        checked = useGps,
                        onCheckedChange = { useGps = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF0284C7))
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { onSave(AuraLocationSettings(homeAddress = home, workAddress = work, useLiveGps = useGps)) },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("Save Location Settings", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun EmailSubScreen(
    currentEmail: String,
    onSave: (String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    var email by remember { mutableStateOf(currentEmail) }

    SubScreenScaffold(title = "Email", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "Configure your default email account for Aura daily digests and drafted correspondence.",
                fontSize = 13.5.sp,
                color = Color(0xFF8A8A8E),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email Address") },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { onSave(email) },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("Save Email", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun CalendarSubScreen(
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    var syncBriefings by remember { mutableStateOf(true) }
    var eventSuggestions by remember { mutableStateOf(true) }

    SubScreenScaffold(title = "Calendar", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "Manage how Aura accesses your schedule and assists with daily planning.",
                fontSize = 13.5.sp,
                color = Color(0xFF8A8A8E),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                shadowElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Morning Schedule Briefing", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("Summarize today's agenda on initial chat", fontSize = 12.sp, color = Color(0xFF8A8A8E))
                        }
                        Switch(
                            checked = syncBriefings,
                            onCheckedChange = { syncBriefings = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFEA580C))
                        )
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = Color(0xFFECECEE))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Event Suggestions", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("Detect dates in chats and offer reminder creation", fontSize = 12.sp, color = Color(0xFF8A8A8E))
                        }
                        Switch(
                            checked = eventSuggestions,
                            onCheckedChange = { eventSuggestions = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFEA580C))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubScreenScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit
) {
    val isDark = isAppInDarkTheme()
    val bgColor = MaterialTheme.colorScheme.background
    val topBarBg = if (isDark) Color(0xFF1C1C1E) else Color.White
    val textColor = if (isDark) Color(0xFFEDEDED) else Color(0xFF1A1A1A)

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

            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = textColor,
                textAlign = TextAlign.Center
            )

            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            content = content
        )
    }
}
