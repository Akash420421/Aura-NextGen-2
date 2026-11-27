package com.example.gemini.ui.contacts

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.gemini.data.automation.AuraContactsManager
import com.example.gemini.theme.AppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        AuraContactsManager.init(context)
    }

    val contactsList by AuraContactsManager.contacts.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingContact by remember { mutableStateOf<AuraContactsManager.ContactItem?>(null) }

    val filteredContacts = remember(contactsList, searchQuery) {
        if (searchQuery.isBlank()) contactsList
        else {
            val q = searchQuery.trim().lowercase()
            contactsList.filter {
                it.name.lowercase().contains(q) ||
                    it.number.contains(q) ||
                    it.aliases.any { a -> a.lowercase().contains(q) }
            }
        }
    }

    val cardBg = MaterialTheme.colorScheme.surface
    val cardBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Contacts & Quick Dial", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("WhatsApp & Call contacts with custom nicknames", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingContact = null
                    showAddDialog = true
                },
                containerColor = AppColors.accentTeal,
                contentColor = Color.White,
                shape = CircleShape
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "Add Contact")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search by name, number or nickname…", fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppColors.accentTeal,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )

            Spacer(modifier = Modifier.height(14.dp))

            if (filteredContacts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.Contacts,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) "No contacts found" else "No custom contacts yet",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Add contacts with nicknames like 'Bhai' or 'Papa' for voice calls",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(filteredContacts, key = { it.id }) { contact ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = cardBg,
                            border = cardBorder,
                            shadowElevation = 1.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Avatar with initial
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(AppColors.accentTeal.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = contact.name.take(1).uppercase(),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AppColors.accentTeal
                                    )
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                // Contact Details
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = contact.name,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (contact.autoReplyEnabled) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = AppColors.accentTeal.copy(alpha = 0.14f)
                                            ) {
                                                Text(
                                                    text = "Auto-Reply",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = AppColors.accentTeal,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = contact.number,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    if (contact.aliases.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Nicknames: ",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                            )
                                            contact.aliases.forEach { alias ->
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = AppColors.accentIndigo.copy(alpha = 0.12f)
                                                ) {
                                                    Text(
                                                        text = alias,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = AppColors.accentIndigo,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Actions
                                Row {
                                    IconButton(
                                        onClick = {
                                            editingContact = contact
                                            showAddDialog = true
                                        }
                                    ) {
                                        Icon(
                                            Icons.Outlined.Edit,
                                            contentDescription = "Edit",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            AuraContactsManager.deleteContact(contact.id)
                                            Toast.makeText(context, "Deleted ${contact.name}", Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Icon(
                                            Icons.Outlined.DeleteOutline,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp)
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

    if (showAddDialog) {
        ContactEditDialog(
            initial = editingContact,
            onDismiss = { showAddDialog = false },
            onSave = { name, number, aliases, isWhatsApp, isPhone, autoReplyEnabled ->
                if (editingContact == null) {
                    AuraContactsManager.addContact(name, number, aliases, isWhatsApp, isPhone, autoReplyEnabled)
                    Toast.makeText(context, "Added $name", Toast.LENGTH_SHORT).show()
                } else {
                    AuraContactsManager.updateContact(editingContact!!.id, name, number, aliases, isWhatsApp, isPhone, autoReplyEnabled)
                    Toast.makeText(context, "Updated $name", Toast.LENGTH_SHORT).show()
                }
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun ContactEditDialog(
    initial: AuraContactsManager.ContactItem?,
    onDismiss: () -> Unit,
    onSave: (name: String, number: String, aliases: List<String>, isWhatsApp: Boolean, isPhone: Boolean, autoReplyEnabled: Boolean) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var number by remember { mutableStateOf(initial?.number ?: "") }
    var aliasesText by remember { mutableStateOf(initial?.aliases?.joinToString(", ") ?: "") }
    var isWhatsApp by remember { mutableStateOf(initial?.isWhatsApp ?: true) }
    var isPhone by remember { mutableStateOf(initial?.isPhone ?: true) }
    var autoReplyEnabled by remember { mutableStateOf(initial?.autoReplyEnabled ?: false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                Text(
                    text = if (initial == null) "Add Voice Contact" else "Edit Contact",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Aura will use this to call or send WhatsApp messages.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Contact Name (e.g. Akash Sharma)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    label = { Text("Phone Number (e.g. +91XXXXXXXXXX)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = aliasesText,
                    onValueChange = { aliasesText = it },
                    label = { Text("Nicknames / Aliases (comma-separated)") },
                    placeholder = { Text("Bhai, Bhaiya, Akash, Bro") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = isWhatsApp, onCheckedChange = { isWhatsApp = it })
                    Text("WhatsApp Enabled", fontSize = 13.sp)

                    Spacer(modifier = Modifier.width(16.dp))

                    Checkbox(checked = isPhone, onCheckedChange = { isPhone = it })
                    Text("Direct Call", fontSize = 13.sp)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = autoReplyEnabled,
                        onCheckedChange = { autoReplyEnabled = it },
                        enabled = isWhatsApp
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Allow Direct WhatsApp Auto-Reply", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "Auto-replies via notifications without asking (groups & OTPs still confirm first)",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (name.isBlank() || number.isBlank()) return@Button
                            val aliasesList = aliasesText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            onSave(name, number, aliasesList, isWhatsApp, isPhone, autoReplyEnabled)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.accentTeal),
                        shape = RoundedCornerShape(12.dp),
                        enabled = name.isNotBlank() && number.isNotBlank()
                    ) {
                        Text("Save Contact", color = Color.White)
                    }
                }
            }
        }
    }
}
