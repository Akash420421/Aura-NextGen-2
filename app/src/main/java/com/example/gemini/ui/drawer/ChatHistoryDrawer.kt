package com.example.gemini.ui.drawer

import com.example.gemini.ui.components.accent
import com.example.gemini.ui.components.SoftElevatedBox
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import com.example.gemini.R
import android.util.Log
import kotlinx.coroutines.Dispatchers
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import kotlinx.coroutines.delay
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Person
import com.example.gemini.ui.components.AppToastHelper
import com.example.gemini.ui.components.ChatToastType
import android.widget.Toast
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.gemini.data.remote.AgyHubClient
import com.example.gemini.domain.model.Conversation
import androidx.compose.foundation.border
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import com.example.gemini.theme.GeminiBlue
import androidx.compose.material.icons.outlined.Share
import com.example.gemini.ui.components.ConversationExportHelper
import com.example.gemini.ui.components.ConversationShareHelper
import com.example.gemini.ui.components.DrawerEngineWarmingUpView
import com.example.gemini.ui.components.SidebarChatListSkeleton
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ChatHistoryDrawer(
    conversations: List<Conversation>,
    currentConversationId: String?,
    activeInstances: List<com.example.gemini.data.remote.AgyActiveInstance> = emptyList(),
    isLoading: Boolean = false,
    hasReceivedInitialSync: Boolean = false,
    systemConnectionState: com.example.gemini.data.remote.SystemConnectionState = com.example.gemini.data.remote.SystemConnectionState.Offline,
    errorMessage: String? = null,
    isStreaming: Boolean = false,
    authInfo: AgyHubClient.AgyAuthInfo = AgyHubClient.AgyAuthInfo(),
    isAuthBusy: Boolean = false,
    groupByWorkspace: Boolean = false,
    onToggleGroupByWorkspace: (Boolean) -> Unit = {},
    /** Archive view: only archived chats are listed (otherwise only the others). */
    showArchived: Boolean = false,
    onToggleShowArchived: (Boolean) -> Unit = {},
    onRetry: () -> Unit = {},
    onSelectConversation: (String) -> Unit,
    onNewChat: () -> Unit,
    onDeleteConversation: (String) -> Unit,
    onForkConversation: (String) -> Unit = {},
    onTerminateInstance: (String) -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onOpenSettings: () -> Unit,
    /** Opens the floating theme picker (a palette button next to Settings; hidden when null). */
    onOpenThemes: (() -> Unit)? = null,
    onLogin: () -> Unit = {},
    onLogout: () -> Unit = {},
    onCheckAuth: () -> Unit = {},
    onCancelLogin: () -> Unit = {},
    isOpen: Boolean = false,
    /** Accounts of the enabled agents; replaces the Antigravity-only profile / sign-in button when set. */
    accountsButton: (@Composable () -> Unit)? = null,
    onOpenAccounts: (() -> Unit)? = null,
    /** Antigravity runs: its hub start-up and first sync drive the list's loading states. */
    agyEnabled: Boolean = true,
    claudeActions: ClaudeDrawerActions? = null,
    /** Jarvis (voice mode) is on: the filter row gets a Jarvis chip listing Jarvis' hidden chats. */
    jarvisEnabled: Boolean = false,
    jarvisConversations: List<Conversation> = emptyList(),
    /** Starts a Jarvis text chat (the command center) with that agent, or Jarvis' own agent for null. */
    onNewJarvisChat: ((com.example.gemini.domain.model.AgentKind?) -> Unit)? = null,
    enabledAgents: Set<com.example.gemini.domain.model.AgentKind> = emptySet(),
    onOpenImages: (() -> Unit)? = null,
    onOpenCustomize: (() -> Unit)? = null,
    onOpenProjects: (() -> Unit)? = null,
    onOpenTasks: (() -> Unit)? = null,
    onOpenPlugins: (() -> Unit)? = null,
    onOpenVoiceSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var showFullSearchDialog by remember { mutableStateOf(false) }
    var instanceToTerminate by remember {
        mutableStateOf<Pair<com.example.gemini.data.remote.AgyActiveInstance, String>?>(
            null
        )
    }
    var showSigningInProgressDialog by remember { mutableStateOf(false) }
    var showExitConfirmDialog by remember { mutableStateOf(false) }
    var conversationToDelete by remember { mutableStateOf<Conversation?>(null) }
    var isInitialGracePeriod by remember { mutableStateOf(true) }
    var isMoreExpanded by remember { mutableStateOf(false) }
    val moreChevronRotation by animateFloatAsState(
        targetValue = if (isMoreExpanded) 180f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "more_chevron_rotation"
    )
    LaunchedEffect(isOpen) {
        if (!isOpen) isMoreExpanded = false
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3500)
        isInitialGracePeriod = false
    }
    val context = LocalContext.current

    var agentFilter by remember { mutableStateOf<com.example.gemini.domain.model.AgentKind?>(null) }
    val visibleAgents = remember(enabledAgents, conversations) {
        val base = if (enabledAgents.isNotEmpty()) enabledAgents else conversations.map { it.agent }.distinct().toSet()
        com.example.gemini.domain.model.AgentKind.entries.filter { it in base }
    }
    val showFilterBar = (visibleAgents.size + if (jarvisEnabled) 1 else 0) > 1
    if (agentFilter != null && agentFilter !in visibleAgents) agentFilter = null
    var jarvisFilter by remember { mutableStateOf(false) }
    if (!jarvisEnabled && jarvisFilter) jarvisFilter = false
    val jarvisIds = remember(jarvisConversations) { jarvisConversations.map { it.id }.toSet() }
    // archived chats, sub-agent chats included when a parent up the chain is archived (they move with it)
    val archivedIds = remember(conversations, jarvisConversations) {
        val byId = (conversations + jarvisConversations).associateBy { it.id }
        fun isArchived(c: Conversation): Boolean {
            var cur: Conversation? = c
            var hops = 0
            while (cur != null && hops++ < 32) {
                if (cur.archived) return true
                val p = cur.parentConversationId
                if (p == null || p == cur.id) return false
                cur = byId[p]
            }
            return false
        }
        byId.values.filter(::isArchived).map { it.id }.toSet()
    }
    val filtered = remember(conversations, jarvisConversations, searchQuery, agentFilter, jarvisFilter, showArchived, archivedIds) {
        val byAgent = (if (jarvisFilter) jarvisConversations
        else agentFilter?.let { a -> conversations.filter { it.agent == a } } ?: conversations)
            .filter { (it.id in archivedIds) == showArchived }
        if (searchQuery.isBlank()) byAgent
        else byAgent.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                    (it.subagentRole != null && it.subagentRole.contains(searchQuery, ignoreCase = true))
        }
    }

    val allFilteredIds = remember(filtered) { filtered.map { it.id }.toSet() }

    val context = LocalContext.current
    var pinnedIds by remember {
        mutableStateOf(com.example.gemini.data.preferences.AuraCustomizePreferences.getPinnedConversationIds(context))
    }

    // Subagents/nested tool threads are hidden from the drawer to keep a clean, premium chat list
    val subagentsByParent = remember { emptyMap<String, List<Conversation>>() }

    // Top-level conversations (filter out subagent tasks so only user conversations appear, sorted by pinned first)
    val topLevelChats = remember(filtered, pinnedIds) {
        filtered.filter { conv ->
            (conv.parentConversationId == null || conv.parentConversationId == conv.id) &&
                    conv.subagentTypeName == null
        }.sortedWith(
            compareByDescending<Conversation> { pinnedIds.contains(it.id) }
                .thenByDescending { it.updatedAt }
        )
    }

    var expandedParentIds by remember { mutableStateOf(setOf<String>()) }

    // Auto expand all ancestor parents if current conversation is a nested subagent or parent with subagents
    LaunchedEffect(currentConversationId) {
        if (!currentConversationId.isNullOrBlank()) {
            val toExpand = mutableSetOf<String>()
            val curr = conversations.find { it.id == currentConversationId }
            if (curr != null) {
                // If current has subagents, expand it
                if (conversations.any { it.parentConversationId == curr.id }) {
                    toExpand.add(curr.id)
                }
                // Walk up the parent chain to expand all ancestors (nested parent of parent)
                var pId = curr.parentConversationId
                while (!pId.isNullOrBlank()) {
                    val parentConv = conversations.find { it.id == pId }
                    if (parentConv != null) {
                        toExpand.add(parentConv.id)
                        pId = parentConv.parentConversationId
                    } else {
                        break
                    }
                }
            }
            if (toExpand.isNotEmpty()) {
                expandedParentIds = expandedParentIds + toExpand
            }
        }
    }

    val grouped = remember(topLevelChats) {
        topLevelChats.groupBy { conv ->
            if (conv.workspaceUri.isNotBlank()) {
                val clean = conv.workspaceUri.removePrefix("file://").trimEnd('/')
                java.io.File(clean).name.ifBlank { "Workspace" }
            } else {
                "General"
            }
        }
    }
    var expandedGroups by remember { mutableStateOf(setOf<String>()) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Ensure selected conversation is visible, or scroll to very top if on a new chat
    LaunchedEffect(isOpen, currentConversationId, filtered.isEmpty()) {
        if (!isOpen) return@LaunchedEffect

        val isExistingChat = !currentConversationId.isNullOrBlank() && filtered.any { it.id == currentConversationId }
        if (!isExistingChat) {
            // Brand new chat or conversation not in list -> always start at the very top!
            listState.scrollToItem(0)
            return@LaunchedEffect
        }

        val targetIndex = if (!groupByWorkspace) {
            val visibleTree = buildVisibleChatTree(topLevelChats, subagentsByParent, expandedParentIds)
            visibleTree.indexOfFirst { it.conv.id == currentConversationId }
        } else {
            val targetGroup = grouped.entries.firstOrNull { (_, chats) ->
                chats.any { top ->
                    val allDescendantIds = mutableSetOf<String>()
                    fun collect(id: String) {
                        allDescendantIds.add(id)
                        subagentsByParent[id]?.forEach { collect(it.id) }
                    }
                    collect(top.id)
                    allDescendantIds.contains(currentConversationId)
                }
            }
            val currentExpanded = if (targetGroup != null) {
                val chatIndexInGroup = targetGroup.value.indexOfFirst { top ->
                    val allDescendantIds = mutableSetOf<String>()
                    fun collect(id: String) {
                        allDescendantIds.add(id)
                        subagentsByParent[id]?.forEach { collect(it.id) }
                    }
                    collect(top.id)
                    allDescendantIds.contains(currentConversationId)
                }
                if (chatIndexInGroup >= 5 && !expandedGroups.contains(targetGroup.key)) {
                    expandedGroups = expandedGroups + targetGroup.key
                    kotlinx.coroutines.yield()
                    expandedGroups + targetGroup.key
                } else {
                    expandedGroups
                }
            } else {
                expandedGroups
            }

            var index = 0
            var foundIndex = -1
            for ((groupName, chats) in grouped) {
                index++ // item(key = "hdr_$groupName")
                val isExp = currentExpanded.contains(groupName)
                val displayChats = if (isExp) chats else chats.take(5)
                val groupTree = buildVisibleChatTree(displayChats, subagentsByParent, expandedParentIds)
                val idxInTree = groupTree.indexOfFirst { it.conv.id == currentConversationId }
                if (idxInTree != -1) {
                    foundIndex = index + idxInTree
                    break
                }
                index += groupTree.size
                if (chats.size > 5) {
                    index++ // item(key = "more_$groupName")
                }
            }
            foundIndex
        }

        if (targetIndex >= 0) {
            val visibleInfo = listState.layoutInfo.visibleItemsInfo
            val visibleItem = visibleInfo.firstOrNull { it.index == targetIndex }
            val isFullyVisible = visibleItem != null &&
                    visibleItem.offset >= listState.layoutInfo.viewportStartOffset &&
                    (visibleItem.offset + visibleItem.size) <= listState.layoutInfo.viewportEndOffset

            if (!isFullyVisible) {
                val scrollPos = (targetIndex - 1).coerceAtLeast(0)
                listState.scrollToItem(scrollPos)
            }
        } else {
            listState.scrollToItem(0)
        }
    }

    if (instanceToTerminate != null) {
        val (inst, title) = instanceToTerminate!!
        AlertDialog(
            onDismissRequest = { instanceToTerminate = null },
            title = { Text("Active CLI Process", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column {
                    Text(
                        text = "A live Antigravity CLI process is running in the background for this chat:",
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Chat: $title", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("Model: ${inst.model}", fontSize = 12.sp)
                            Text("Uptime: ${inst.uptimeSeconds}s", fontSize = 12.sp)
                            if (inst.pid > 0) {
                                Text("PID: ${inst.pid}", fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Terminate this instance to immediately free RAM?",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onTerminateInstance(inst.conversationId)
                        instanceToTerminate = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Terminate & Free RAM", fontSize = 13.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { instanceToTerminate = null }) {
                    Text("Keep Running", fontSize = 13.sp)
                }
            }
        )
    }

    if (showSigningInProgressDialog) {
        AlertDialog(
            onDismissRequest = { showSigningInProgressDialog = false },
            title = {
                Text(
                    text = "Sign-In in Progress",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = "A sign-in request is currently pending. If you already completed authentication in your browser, tap 'Check Status'. You can also restart sign-in if needed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            showSigningInProgressDialog = false
                            onCheckAuth()
                        }
                    ) {
                        Text("Check Status")
                    }
                    Button(
                        onClick = {
                            showSigningInProgressDialog = false
                            onLogin()
                        }
                    ) {
                        Text("Sign In Again")
                    }
                }
            },
            dismissButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            showSigningInProgressDialog = false
                            onCancelLogin()
                        }
                    ) {
                        Text("Later")
                    }
                    TextButton(onClick = { showSigningInProgressDialog = false }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    if (showExitConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showExitConfirmDialog = false },
            icon = {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
            title = {
                Text(
                    text = "Exit AntiGem?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "Do you want to exit the app? This will stop all active background services, servers, and terminals.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExitConfirmDialog = false
                        try {
                            com.example.gemini.data.service.TermuxService.stop(context)
                        } catch (e: Exception) {
                            Log.e("ChatHistoryDrawer", "Error stopping service", e)
                            try {
                                com.example.gemini.data.local.LocalServerManager.forceKillAll()
                            } catch (_: Exception) {
                            }
                            try {
                                com.example.gemini.data.local.LocalTerminalManager.closeAll()
                            } catch (_: Exception) {
                            }
                            (context as? android.app.Activity)?.finishAffinity()
                            android.os.Process.killProcess(android.os.Process.myPid())
                            kotlin.system.exitProcess(0)
                        }
                        try {
                            (context as? android.app.Activity)?.finishAffinity()
                        } catch (e: Exception) {
                            Log.e("ChatHistoryDrawer", "Error finishing activity", e)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Exit App", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showExitConfirmDialog = false },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Cancel", fontSize = 13.5.sp)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(18.dp)
        )
    }

    if (conversationToDelete != null) {
        val targetConv = conversationToDelete!!
        AlertDialog(
            onDismissRequest = { conversationToDelete = null },
            icon = {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
            title = {
                Text(
                    text = "Delete Conversation?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete \"${targetConv.title.ifBlank { "this conversation" }}\"? This will permanently remove all messages and trajectory data.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val id = targetConv.id
                        conversationToDelete = null
                        onDeleteConversation(id)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Delete", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { conversationToDelete = null },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Cancel", fontSize = 13.5.sp)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(18.dp)
        )
    }

    val drawerBg = MaterialTheme.colorScheme.background
    val textPrimary = Color(0xFF1A1A1A)
    val textSecondary = Color(0xFF8A8A8E)

    ModalDrawerSheet(
        modifier = modifier
            .fillMaxWidth(0.80f)
            .widthIn(max = 320.dp)
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
                ambientColor = Color.Black.copy(alpha = 0.08f),
                spotColor = Color.Black.copy(alpha = 0.12f)
            ),
        drawerContainerColor = drawerBg,
        drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
        drawerTonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // ---------------- header: Aura AI on the left, circular 48dp soft elevated search on the right ----------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Aura AI",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.3).sp,
                    fontFamily = FontFamily(Font(R.font.manrope_semibold, FontWeight.SemiBold)),
                    color = textPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )

                SoftElevatedBox(
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    backgroundColor = Color.White,
                    elevation = 3.dp,
                    onClick = {
                        isSearchActive = !isSearchActive
                        if (!isSearchActive) {
                            searchQuery = ""
                            onSearchQueryChange("")
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = "Search",
                        tint = textPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Compact Top Navigation Menu (Images, Customize, Projects, Tasks, More -> Plugins)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SidebarNavigationRow(
                    icon = Icons.Outlined.PhotoLibrary,
                    label = "Images",
                    textColor = textPrimary,
                    onClick = { onOpenImages?.invoke() }
                )
                SidebarNavigationRow(
                    icon = Icons.Outlined.Tune,
                    label = "Customize",
                    textColor = textPrimary,
                    onClick = { onOpenCustomize?.invoke() }
                )
                SidebarNavigationRow(
                    icon = Icons.Outlined.FolderOpen,
                    label = "Projects",
                    textColor = textPrimary,
                    onClick = { onOpenProjects?.invoke() }
                )
                SidebarNavigationRow(
                    icon = Icons.Outlined.Schedule,
                    label = "Tasks",
                    textColor = textPrimary,
                    onClick = { onOpenTasks?.invoke() }
                )
                SidebarNavigationRow(
                    icon = Icons.Rounded.MoreHoriz,
                    label = "More",
                    textColor = textPrimary,
                    trailingContent = {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isMoreExpanded) "Collapse" else "Expand",
                            tint = textSecondary,
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(moreChevronRotation)
                        )
                    },
                    onClick = { isMoreExpanded = !isMoreExpanded }
                )
                AnimatedVisibility(
                    visible = isMoreExpanded,
                    enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                    exit = shrinkVertically(tween(200)) + fadeOut(tween(200))
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SidebarNavigationRow(
                            icon = Icons.Outlined.Extension,
                            label = "Plugins",
                            textColor = textPrimary,
                            onClick = { onOpenPlugins?.invoke() }
                        )
                        SidebarNavigationRow(
                            icon = Icons.Outlined.RecordVoiceOver,
                            label = "Voice Setting",
                            textColor = textPrimary,
                            onClick = { onOpenVoiceSettings?.invoke() }
                        )
                    }
                }
            }

            // ---------------- search field: only after tapping the header's search icon ----------------
            val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(isSearchActive) {
                if (isSearchActive) runCatching { delay(120); searchFocus.requestFocus() }
            }
            AnimatedVisibility(
                visible = isSearchActive || searchQuery.isNotBlank(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(
                        1.dp,
                        if (searchQuery.isNotBlank()) textPrimary.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f)
                    ),
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth()
                        .height(40.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = if (searchQuery.isNotBlank()) textPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                onSearchQueryChange(it)
                            },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                lineHeight = 18.sp
                            ),
                            cursorBrush = SolidColor(textPrimary),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(searchFocus),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "Search chats",
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    searchQuery = ""
                                    onSearchQueryChange("")
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        // Deep search (message contents, filters)
                        IconButton(
                            onClick = { showFullSearchDialog = true },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Advanced Search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }

            // result count + shortcut to the deep search while filtering
            AnimatedVisibility(
                visible = searchQuery.isNotBlank(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, end = 2.dp, top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${filtered.size} chat${if (filtered.size != 1) "s" else ""} found",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "Advanced Search ›",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1976D2),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { showFullSearchDialog = true }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            // breathing room between the header and the list
            Spacer(modifier = Modifier.height(10.dp))

            if (showFilterBar) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val allItems = buildList {
                        add(null to "All")
                        visibleAgents.forEach { agent ->
                            val label = when (agent) {
                                com.example.gemini.domain.model.AgentKind.AGY -> "Antigravity"
                                com.example.gemini.domain.model.AgentKind.CLAUDE -> "Claude"
                                com.example.gemini.domain.model.AgentKind.OPENCODE -> "OpenCode"
                                com.example.gemini.domain.model.AgentKind.CODEX -> "Codex"
                            }
                            add(agent to label)
                        }
                    }
                    allItems.forEach { (agent, label) ->
                        val selected = !jarvisFilter && agentFilter == agent
                        val accent = agent?.accent() ?: textPrimary
                        Text(
                            text = label,
                            fontSize = 11.5.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) accent.copy(alpha = 0.14f) else Color.Transparent)
                                .clickable { jarvisFilter = false; agentFilter = agent }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    if (jarvisEnabled) {
                        val jarvisAccent = com.example.gemini.ui.jarvis.Cyan
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (jarvisFilter) jarvisAccent.copy(alpha = 0.14f) else Color.Transparent)
                                .clickable { agentFilter = null; jarvisFilter = true }
                                .padding(start = 7.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            com.example.gemini.ui.jarvis.JarvisBotIcon(15.dp, if (jarvisFilter) jarvisAccent else jarvisAccent.copy(alpha = 0.7f))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Jarvis",
                                fontSize = 11.5.sp,
                                fontWeight = if (jarvisFilter) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (jarvisFilter) jarvisAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            // archive view: say so, or the list looks like chats went missing
            if (showArchived) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Archive,
                        contentDescription = null,
                        tint = textSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Archived chats · ${topLevelChats.size}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Back to chats",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onToggleShowArchived(false) }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            // the Jarvis tab: start a Jarvis text chat (a command center that leads teams of agents)
            if (jarvisEnabled && jarvisFilter && onNewJarvisChat != null && !showArchived) {
                JarvisNewChatRow(agents = visibleAgents, onNew = onNewJarvisChat)
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Section Label "Chats"
            Text(
                text = "Chats",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = textSecondary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
            )

            val isEngineConnecting = agyEnabled && (systemConnectionState.status == com.example.gemini.data.remote.SystemStatus.STARTING ||
                    systemConnectionState.status == com.example.gemini.data.remote.SystemStatus.ACQUIRING_CSRF ||
                    (systemConnectionState.status == com.example.gemini.data.remote.SystemStatus.OFFLINE && isInitialGracePeriod)) && conversations.isEmpty()
            val showSkeleton =
                agyEnabled && (!hasReceivedInitialSync || isLoading) && conversations.isEmpty() && errorMessage.isNullOrBlank()

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Crossfade(
                    targetState = when {
                        isEngineConnecting -> "engine_warming"
                        showSkeleton -> "loading"
                        !errorMessage.isNullOrBlank() && conversations.isEmpty() -> "error"
                        filtered.isEmpty() -> "empty"
                        else -> "content"
                    },
                    label = "drawerChatListFade",
                    modifier = Modifier.fillMaxSize()
                ) { state ->
                when (state) {
                    "engine_warming" -> {
                        DrawerEngineWarmingUpView()
                    }

                    "loading" -> {
                        SidebarChatListSkeleton()
                    }

                    "error" -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Unable to load chats",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = errorMessage ?: "Connection error",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            SoftElevatedBox(
                                modifier = Modifier
                                    .height(44.dp)
                                    .padding(horizontal = 4.dp),
                                shape = RoundedCornerShape(22.dp),
                                backgroundColor = Color.White,
                                elevation = 3.dp,
                                onClick = onRetry
                            ) {
                                Text(
                                    text = "Retry",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF1976D2),
                                    modifier = Modifier.padding(horizontal = 24.dp)
                                )
                            }
                        }
                    }

                    "empty" -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 40.dp),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Text(
                                text = if (searchQuery.isNotBlank()) "No ${if (showArchived) "archived " else ""}chats found matching \"$searchQuery\""
                                    else if (showArchived) "No archived chats. Archive a chat from its ⋮ menu to keep it here, out of the main list."
                                    else if (jarvisFilter) "No Jarvis chats yet. Start one above: type a big job and Jarvis plans it, runs it with teams of agents and keeps going until it's done."
                                    else "No recent chats",
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp),
                                fontSize = 13.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                            )
                        }
                    }

                    else -> {
                        // iOS-style bounce at both ends, also when the chats fit and the list cannot scroll
                        val chatListBounce = com.example.gemini.ui.components.rememberBouncyLazyScroll(listState)
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(chatListBounce.modifier),
                            userScrollEnabled = chatListBounce.userScrollEnabled,
                            overscrollEffect = chatListBounce.overscrollEffect
                        ) {
                            if (!groupByWorkspace) {
                                val visibleTree =
                                    buildVisibleChatTree(topLevelChats, subagentsByParent, expandedParentIds)
                                items(visibleTree, key = { it.conv.id }) { item ->
                                    val conv = item.conv
                                    val isSelected = conv.id == currentConversationId
                                    val activeInst = activeInstances.find { it.conversationId == conv.id }
                                    val isActivelyRunning = conv.isRunning && (systemConnectionState.isHubOnline || conv.agent != com.example.gemini.domain.model.AgentKind.AGY)
                                    val isScheduledOrBackground =
                                        systemConnectionState.isHubOnline && !isActivelyRunning && (conv.notFullyIdle || conv.hasActivity || activeInst != null)

                                    ChatHistoryItemRow(
                                        conv = conv,
                                        isJarvis = conv.id in jarvisIds,
                                        isSelected = isSelected,
                                        isPinned = pinnedIds.contains(conv.id),
                                        onTogglePin = {
                                            val nowPinned = com.example.gemini.data.preferences.AuraCustomizePreferences.togglePinConversation(context, conv.id)
                                            pinnedIds = com.example.gemini.data.preferences.AuraCustomizePreferences.getPinnedConversationIds(context)
                                            AppToastHelper.showToast(if (nowPinned) "Chat pinned to top" else "Chat unpinned", ChatToastType.SUCCESS)
                                        },
                                        activeInst = activeInst,
                                        isActivelyRunning = isActivelyRunning,
                                        isScheduledOrBackground = isScheduledOrBackground,
                                        depth = item.depth,
                                        displayName = if (item.depth > 0) conv.subagentRole?.takeIf { it.isNotBlank() }
                                            ?: conv.title else conv.title,
                                        subagents = item.subagents,
                                        isSubagentsExpanded = item.isExpanded,
                                        onToggleSubagents = if (item.subagents.isNotEmpty()) {
                                            {
                                                expandedParentIds =
                                                    if (item.isExpanded) expandedParentIds - conv.id else expandedParentIds + conv.id
                                            }
                                        } else null,
                                        onSelectConversation = {
                                            if (item.subagents.isNotEmpty()) {
                                                expandedParentIds = expandedParentIds + conv.id
                                            }
                                            Log.d(
                                                "CHAT_OPEN_DEBUG",
                                                "📂 [Drawer] User clicked conversation: id=${conv.id}, title='${conv.title}'"
                                            )
                                            onSelectConversation(conv.id)
                                        },
                                        onForkConversation = onForkConversation,
                                        onRequestDelete = { conversationToDelete = it },
                                        claudeActions = claudeActions,
                                        onTerminateInstance = { inst, title ->
                                            instanceToTerminate = inst to title
                                        }
                                    )
                                }
                            } else {
                                grouped.forEach { (groupName, chats) ->
                                    val isExpanded = expandedGroups.contains(groupName)
                                    val displayChats = if (isExpanded) chats else chats.take(5)

                                    item(key = "hdr_$groupName") {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .then(
                                                    if (chats.size > 5) {
                                                        Modifier
                                                            .clip(RoundedCornerShape(6.dp))
                                                            .clickable {
                                                                expandedGroups =
                                                                    if (isExpanded) expandedGroups - groupName else expandedGroups + groupName
                                                            }
                                                    } else Modifier
                                                )
                                                .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (groupName == "General") Icons.Outlined.Public else Icons.Default.Folder,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Spacer(modifier = Modifier.width(7.dp))
                                            Text(
                                                text = groupName,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                                letterSpacing = 0.2.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                            ) {
                                                Text(
                                                    text = "${chats.size}",
                                                    fontSize = 10.5.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                )
                                            }
                                            if (chats.size > 5) {
                                                Spacer(modifier = Modifier.weight(1f))
                                                Icon(
                                                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }

                                    val groupTree =
                                        buildVisibleChatTree(displayChats, subagentsByParent, expandedParentIds)
                                    items(groupTree, key = { it.conv.id }) { item ->
                                        val conv = item.conv
                                        val isSelected = conv.id == currentConversationId
                                        val activeInst = activeInstances.find { it.conversationId == conv.id }
                                        val isActivelyRunning = conv.isRunning && (systemConnectionState.isHubOnline || conv.agent != com.example.gemini.domain.model.AgentKind.AGY)
                                        val isScheduledOrBackground =
                                            systemConnectionState.isHubOnline && !isActivelyRunning && (conv.notFullyIdle || conv.hasActivity || activeInst != null)

                                        ChatHistoryItemRow(
                                            conv = conv,
                                            isJarvis = conv.id in jarvisIds,
                                            isSelected = isSelected,
                                            isPinned = pinnedIds.contains(conv.id),
                                            onTogglePin = {
                                                val nowPinned = com.example.gemini.data.preferences.AuraCustomizePreferences.togglePinConversation(context, conv.id)
                                                pinnedIds = com.example.gemini.data.preferences.AuraCustomizePreferences.getPinnedConversationIds(context)
                                                AppToastHelper.showToast(if (nowPinned) "Chat pinned to top" else "Chat unpinned", ChatToastType.SUCCESS)
                                            },
                                            activeInst = activeInst,
                                            isActivelyRunning = isActivelyRunning,
                                            isScheduledOrBackground = isScheduledOrBackground,
                                            depth = item.depth,
                                            displayName = if (item.depth > 0) conv.subagentRole?.takeIf { it.isNotBlank() }
                                                ?: conv.title else conv.title,
                                            subagents = item.subagents,
                                            isSubagentsExpanded = item.isExpanded,
                                            onToggleSubagents = if (item.subagents.isNotEmpty()) {
                                                {
                                                    expandedParentIds =
                                                        if (item.isExpanded) expandedParentIds - conv.id else expandedParentIds + conv.id
                                                }
                                            } else null,
                                            onSelectConversation = {
                                                if (item.subagents.isNotEmpty()) {
                                                    expandedParentIds = expandedParentIds + conv.id
                                                }
                                                Log.d(
                                                    "CHAT_OPEN_DEBUG",
                                                    "📂 [Drawer Compact] User clicked conversation: id=${conv.id}, title='${conv.title}'"
                                                )
                                                onSelectConversation(conv.id)
                                            },
                                            onForkConversation = onForkConversation,
                                            onRequestDelete = { conversationToDelete = it },
                                            claudeActions = claudeActions,
                                            onTerminateInstance = { inst, title ->
                                                instanceToTerminate = inst to title
                                            }
                                        )
                                    }

                                    if (chats.size > 5) {
                                        item(key = "more_$groupName") {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .padding(start = 12.dp, top = 2.dp, bottom = 4.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .clickable {
                                                        expandedGroups =
                                                            if (isExpanded) expandedGroups - groupName else expandedGroups + groupName
                                                    }
                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = if (isExpanded) "Show less" else "Show ${chats.size - 5} more",
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = Color(0xFF1976D2)
                                                )
                                                Spacer(modifier = Modifier.width(2.dp))
                                                Icon(
                                                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                    contentDescription = null,
                                                    tint = Color(0xFF1976D2),
                                                    modifier = Modifier.size(14.dp)
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

            // 32dp soft alpha fade gradient overlay above footer
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, drawerBg)
                            )
                        )
                )
            }

            // Bottom Footer: Settings gear on left, Accounts button + overflow menu on right
            var showOverflowMenu by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 48dp circular Settings gear button with soft elevation
                SoftElevatedBox(
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    backgroundColor = Color.White,
                    elevation = 3.dp,
                    onClick = { onOpenSettings() }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Settings",
                        tint = textPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Real accounts button in the center
                if (accountsButton != null) {
                    accountsButton()
                } else {
                    Spacer(modifier = Modifier.width(48.dp))
                }

                // 48dp circular 3-dots overflow button with soft elevation on the right
                Box(contentAlignment = Alignment.Center) {
                    SoftElevatedBox(
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        backgroundColor = Color.White,
                        elevation = 3.dp,
                        onClick = { showOverflowMenu = true }
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More Options",
                            tint = textPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                        // Dropdown Menu anchored to the 3-dots button
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                            shape = RoundedCornerShape(16.dp),
                            containerColor = Color.White,
                            tonalElevation = 6.dp,
                            modifier = Modifier.widthIn(min = 220.dp)
                        ) {
                            // 1. Accounts & Sign-in (opens real AccountsDialog)
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = if (authInfo.displayName.isNotBlank()) authInfo.displayName else "Accounts & Sign-in",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = textPrimary
                                        )
                                        val email = authInfo.email
                                        if (email != null && email.isNotBlank()) {
                                            Text(
                                                text = email,
                                                fontSize = 12.sp,
                                                color = textSecondary
                                            )
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Person,
                                        contentDescription = null,
                                        tint = textPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    onOpenAccounts?.invoke() ?: onLogin()
                                }
                            )

                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = textSecondary.copy(alpha = 0.2f),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )

                            // 2. Archived Chats Toggle
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (showArchived) "Hide Archived Chats" else "Show Archived Chats",
                                        fontSize = 14.sp,
                                        color = textPrimary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Archive,
                                        contentDescription = null,
                                        tint = textPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    onToggleShowArchived(!showArchived)
                                }
                            )

                            // 3. Group by Workspace Toggle
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (groupByWorkspace) "Disable Workspace Grouping" else "Group by Workspace",
                                        fontSize = 14.sp,
                                        color = textPrimary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = textPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    onToggleGroupByWorkspace(!groupByWorkspace)
                                }
                            )

                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = textSecondary.copy(alpha = 0.2f),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )

                            // 4. Exit App
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Exit App",
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.PowerSettingsNew,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    showExitConfirmDialog = true
                                }
                            )
                        }
                    }
                }

            if (showFullSearchDialog) {
                SearchChatsDialog(
                    conversations = conversations,
                    currentConversationId = currentConversationId,
                    onSelectConversation = onSelectConversation,
                    onDismiss = { showFullSearchDialog = false }
                )
            }
        }
    }
}

private fun formatRelativeTime(timestampMillis: Long): String {
    if (timestampMillis <= 0L) return ""
    val now = System.currentTimeMillis()
    val diff = (now - timestampMillis).coerceAtLeast(0L)
    val minutes = diff / 60_000L
    val hours = diff / 3_600_000L
    val days = diff / 86_400_000L
    val months = days / 30L
    val years = days / 365L

    return when {
        minutes < 1 -> "now"
        hours < 1 -> "${minutes}m"
        days < 1 -> "${hours}h"
        days < 30 -> "${days}d"
        days < 365 -> "${months.coerceAtLeast(1)}mo"
        else -> "${years.coerceAtLeast(1)}y"
    }
}

private data class VisibleChatTreeItem(
    val conv: Conversation,
    val depth: Int,
    val subagents: List<Conversation>,
    val isExpanded: Boolean
)

private fun buildVisibleChatTree(
    rootChats: List<Conversation>,
    subagentsByParent: Map<String, List<Conversation>>,
    expandedParentIds: Set<String>,
    depth: Int = 0
): List<VisibleChatTreeItem> {
    val result = mutableListOf<VisibleChatTreeItem>()
    for (conv in rootChats) {
        val children = subagentsByParent[conv.id] ?: emptyList()
        val isExpanded = expandedParentIds.contains(conv.id)
        result.add(
            VisibleChatTreeItem(
                conv = conv,
                depth = depth,
                subagents = children,
                isExpanded = isExpanded
            )
        )
        if (isExpanded && children.isNotEmpty()) {
            result.addAll(
                buildVisibleChatTree(
                    rootChats = children,
                    subagentsByParent = subagentsByParent,
                    expandedParentIds = expandedParentIds,
                    depth = depth + 1
                )
            )
        }
    }
    return result
}

@Composable
private fun ChatHistoryItemRow(
    conv: Conversation,
    isSelected: Boolean,
    /** A Jarvis chat: marked with Jarvis' robot face instead of the chat bubble. */
    isJarvis: Boolean = false,
    isPinned: Boolean = false,
    onTogglePin: () -> Unit = {},
    activeInst: com.example.gemini.data.remote.AgyActiveInstance?,
    isActivelyRunning: Boolean,
    isScheduledOrBackground: Boolean,
    depth: Int = 0,
    displayName: String = conv.title,
    subagents: List<Conversation> = emptyList(),
    isSubagentsExpanded: Boolean = false,
    onToggleSubagents: (() -> Unit)? = null,
    onSelectConversation: (String) -> Unit,
    onForkConversation: (String) -> Unit,
    onRequestDelete: (Conversation) -> Unit,
    onTerminateInstance: (com.example.gemini.data.remote.AgyActiveInstance, String) -> Unit,
    claudeActions: ClaudeDrawerActions? = null
) {
    val isSubagent = depth > 0
    val startPadding = if (isSubagent) (12 + (depth * 14)).dp else 6.dp
    val isAppDark = com.example.gemini.theme.isAppInDarkTheme()
    val textPrimary = if (isAppDark) Color(0xFFEDEDED) else Color(0xFF1A1A1A)
    val selectedBg = if (isAppDark) Color(0xFF2C2C2E) else Color(0xFFF0F0F2)
    val statusDotColor = when {
        isActivelyRunning -> com.example.gemini.theme.AppColors.success
        isScheduledOrBackground -> com.example.gemini.theme.AppColors.warning
        else -> null
    }

    var menuExpanded by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    val isClaudeConv = conv.agent == com.example.gemini.domain.model.AgentKind.CLAUDE
    val isAgentConv = conv.agent != com.example.gemini.domain.model.AgentKind.AGY
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = com.example.gemini.ui.components.LocalSnackbarHostState.current

    if (showRename && claudeActions != null) {
        var newTitle by remember { mutableStateOf(conv.title) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename chat", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(value = newTitle, onValueChange = { newTitle = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    claudeActions.rename(conv.id, newTitle)
                    showRename = false
                }, enabled = newTitle.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = startPadding,
                end = 6.dp,
                top = 1.dp,
                bottom = 1.dp
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isSelected) selectedBg else Color.Transparent)
                .combinedClickable(
                    onClick = {
                        Log.d("CHAT_OPEN_DEBUG", "📂 [Drawer Row] User clicked: id=${conv.id}, title='${conv.title}'")
                        onSelectConversation(conv.id)
                    },
                    onLongClick = {
                        menuExpanded = true
                    }
                )
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSubagent) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.CallSplit,
                    contentDescription = "Subagent",
                    tint = if (isSelected) textPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            if (isPinned) {
                Icon(
                    imageVector = Icons.Outlined.PushPin,
                    contentDescription = "Pinned",
                    tint = Color(0xFF2F6FED),
                    modifier = Modifier
                        .size(15.dp)
                        .padding(end = 4.dp)
                )
            }

            Text(
                text = displayName,
                fontSize = 16.sp,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = textPrimary,
                modifier = Modifier.weight(1f)
            )

            if (subagents.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSubagentsExpanded) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(
                        alpha = 0.7f
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (isSubagentsExpanded) MaterialTheme.colorScheme.outline.copy(alpha = 0.3f) else Color.Transparent
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onToggleSubagents?.invoke() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${subagents.size}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSubagentsExpanded) textPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Icon(
                            imageVector = if (isSubagentsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isSubagentsExpanded) "Collapse subagents" else "Expand subagents",
                            tint = if (isSubagentsExpanded) textPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
            }

            if (activeInst != null) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = com.example.gemini.theme.AppColors.success.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, com.example.gemini.theme.AppColors.success.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            onTerminateInstance(activeInst, conv.title)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(com.example.gemini.theme.AppColors.success, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "RUNNING",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = com.example.gemini.theme.AppColors.success
                        )
                    }
                }
            } else if (statusDotColor != null) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(statusDotColor, CircleShape)
                )
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            shape = RoundedCornerShape(18.dp),
            containerColor = Color.White,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, Color(0x0F000000)),
            modifier = Modifier.widthIn(min = 210.dp)
        ) {
            DropdownMenuItem(
                text = { Text(if (isPinned) "Unpin" else "Pin to Top", fontSize = 13.5.sp, color = Color(0xFF1A1A1A)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.PushPin,
                        contentDescription = null,
                        tint = if (isPinned) Color(0xFF2F6FED) else Color(0xFF6E6E73),
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    menuExpanded = false
                    onTogglePin()
                }
            )
            if (claudeActions != null) {
                DropdownMenuItem(
                    text = { Text("Rename", fontSize = 13.5.sp, color = Color(0xFF1A1A1A)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = null,
                            tint = Color(0xFF6E6E73),
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        showRename = true
                    }
                )
            }
            HorizontalDivider(
                color = Color(0x0F000000),
                modifier = Modifier.padding(vertical = 4.dp)
            )
            DropdownMenuItem(
                text = { Text("Export Markdown (.md)", fontSize = 13.5.sp, color = Color(0xFF1A1A1A)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Description,
                        contentDescription = null,
                        tint = GeminiBlue,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    menuExpanded = false
                    com.example.gemini.ui.components.AppToastHelper.showToast("Exporting Markdown...", com.example.gemini.ui.components.ChatToastType.INFO)
                    scope.launch(Dispatchers.IO) {
                        ConversationExportHelper.exportConversation(
                            context = context,
                            conversationId = conv.id,
                            title = conv.title,
                            activeMessages = if (isAgentConv) claudeActions?.loadMessages?.invoke(conv.id) else null,
                            format = ConversationExportHelper.ExportFormat.MARKDOWN,
                            snackbarHostState = snackbarHostState
                        )
                    }
                }
            )
            DropdownMenuItem(
                text = { Text("Share", fontSize = 13.5.sp, color = Color(0xFF1A1A1A)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = null,
                        tint = Color(0xFF6E6E73),
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    menuExpanded = false
                    com.example.gemini.ui.components.AppToastHelper.showToast("Preparing .antigem share...", com.example.gemini.ui.components.ChatToastType.INFO)
                    scope.launch(Dispatchers.IO) {
                        ConversationShareHelper.shareConversation(
                            context = context,
                            conversationId = conv.id,
                            title = conv.title,
                            activeMessages = if (isAgentConv) claudeActions?.loadMessages?.invoke(conv.id) else null,
                            snackbarHostState = snackbarHostState
                        )
                    }
                }
            )
            HorizontalDivider(
                color = Color(0x0F000000),
                modifier = Modifier.padding(vertical = 4.dp)
            )
            if (claudeActions != null) {
                DropdownMenuItem(
                    text = { Text(if (conv.archived) "Unarchive" else "Archive", fontSize = 13.5.sp, color = Color(0xFF1A1A1A)) },
                    leadingIcon = {
                        Icon(
                            imageVector = if (conv.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                            contentDescription = null,
                            tint = Color(0xFF6E6E73),
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        claudeActions.archive(conv.id, !conv.archived)
                        AppToastHelper.showToast(if (conv.archived) "Chat unarchived" else "Chat archived", ChatToastType.SUCCESS)
                    }
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        "Delete Conversation",
                        fontSize = 13.5.sp,
                        color = Color(0xFFDC2626)
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = Color(0xFFDC2626),
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    menuExpanded = false
                    onRequestDelete(conv)
                }
            )
        }
        }
    }

@Composable
fun SearchChatsDialog(
    conversations: List<Conversation>,
    currentConversationId: String?,
    onSelectConversation: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val textPrimary = Color(0xFF1A1A1A)
    var query by remember { mutableStateOf("") }
    var selectedTimeFilter by remember { mutableStateOf("All") }
    var selectedWorkspaceFilter by remember { mutableStateOf("All") }

    val workspaces = remember(conversations) {
        val list = mutableListOf("All")
        conversations.forEach { conv ->
            if (conv.workspaceUri.isNotBlank()) {
                val clean = conv.workspaceUri.removePrefix("file://").trimEnd('/')
                val name = java.io.File(clean).name.ifBlank { "Workspace" }
                if (!list.contains(name)) list.add(name)
            }
        }
        list
    }

    val results = remember(conversations, query, selectedTimeFilter, selectedWorkspaceFilter) {
        val now = System.currentTimeMillis()
        conversations.filter { conv ->
            val matchesTime = when (selectedTimeFilter) {
                "Today" -> (now - conv.updatedAt) < 24 * 3600_000L
                "Past 7d" -> (now - conv.updatedAt) < 7 * 24 * 3600_000L
                "Past 30d" -> (now - conv.updatedAt) < 30L * 24 * 3600_000L
                else -> true
            }

            val matchesWorkspace = when (selectedWorkspaceFilter) {
                "All" -> true
                else -> {
                    val clean = conv.workspaceUri.removePrefix("file://").trimEnd('/')
                    val name = java.io.File(clean).name.ifBlank { "Workspace" }
                    name.equals(selectedWorkspaceFilter, ignoreCase = true)
                }
            }

            val matchesQuery = if (query.isBlank()) true else {
                conv.title.contains(query, ignoreCase = true) ||
                        (conv.subagentRole?.contains(query, ignoreCase = true) == true) ||
                        (conv.summary?.contains(query, ignoreCase = true) == true) ||
                        conv.modelId.contains(query, ignoreCase = true)
            }

            matchesTime && matchesWorkspace && matchesQuery
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.82f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Search Conversations",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${conversations.size} total chats indexed",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Search input bar
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(
                        1.5.dp,
                        if (query.isNotBlank()) textPrimary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(
                            alpha = 0.3f
                        )
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = if (query.isNotBlank()) textPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = 0.6f
                            ),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            ),
                            cursorBrush = SolidColor(textPrimary),
                            modifier = Modifier.weight(1f),
                            decorationBox = { innerTextField ->
                                if (query.isEmpty()) {
                                    Text(
                                        text = "Search by title, topic, or model...",
                                        fontSize = 13.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                                innerTextField()
                            }
                        )
                        if (query.isNotEmpty()) {
                            IconButton(
                                onClick = { query = "" },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Filter chips (Time & Workspace)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Time filters
                    listOf("All", "Today", "Past 7d", "Past 30d").forEach { timeLabel ->
                        val isSelected = selectedTimeFilter == timeLabel
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) textPrimary.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(
                                alpha = 0.35f
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) textPrimary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant.copy(
                                    alpha = 0.2f
                                )
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedTimeFilter = timeLabel }
                        ) {
                            Text(
                                text = timeLabel,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) textPrimary else MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = 0.8f
                                ),
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                            )
                        }
                    }

                    if (workspaces.size > 1) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(
                            modifier = Modifier.size(width = 1.dp, height = 16.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                        )
                        Spacer(modifier = Modifier.width(4.dp))

                        workspaces.forEach { wsName ->
                            val isSelected = selectedWorkspaceFilter == wsName
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) textPrimary.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(
                                    alpha = 0.35f
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) textPrimary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant.copy(
                                        alpha = 0.2f
                                    )
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { selectedWorkspaceFilter = wsName }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (wsName != "All") {
                                        Icon(
                                            imageVector = Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = if (isSelected) textPrimary else MaterialTheme.colorScheme.onSurface.copy(
                                                alpha = 0.6f
                                            ),
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                    Text(
                                        text = wsName,
                                        fontSize = 11.5.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) textPrimary else MaterialTheme.colorScheme.onSurface.copy(
                                            alpha = 0.8f
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Results count
                Text(
                    text = "${results.size} match${if (results.size != 1) "es" else ""}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Results list
                if (results.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ChatBubbleOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                text = "No conversations found",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (query.isNotBlank()) "No chats match \"$query\". Try different keywords or reset filters." else "No chats in this category.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(results, key = { it.id }) { conv ->
                            val isSelected = conv.id == currentConversationId
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(
                                    alpha = 0.35f
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) textPrimary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outlineVariant.copy(
                                        alpha = 0.15f
                                    )
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        Log.d(
                                            "CHAT_OPEN_DEBUG",
                                             "[Drawer Dialog] User clicked: id=${conv.id}, title='${conv.title}'"
                                        )
                                        onSelectConversation(conv.id)
                                        onDismiss()
                                    }
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = conv.title,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isSelected) textPrimary else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        val rel = formatRelativeTime(conv.updatedAt)
                                        if (rel.isNotBlank()) {
                                            Text(
                                                text = rel,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                            )
                                        }
                                    }

                                    if (!conv.summary.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = conv.summary,
                                            fontSize = 11.5.sp,
                                            lineHeight = 15.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (conv.workspaceUri.isNotBlank()) {
                                            val clean = conv.workspaceUri.removePrefix("file://").trimEnd('/')
                                            val name = java.io.File(clean).name.ifBlank { "Workspace" }
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Folder,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(11.dp),
                                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                                    )
                                                    Text(
                                                        text = name,
                                                        fontSize = 10.5.sp,
                                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                                    )
                                                }
                                            }
                                        }

                                        if (conv.modelId.isNotBlank()) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                            ) {
                                                Text(
                                                    text = conv.modelId.substringAfterLast('/'),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        if (conv.stepCount > 0) {
                                            Text(
                                                text = "${conv.stepCount} turns",
                                                fontSize = 10.5.sp,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
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

/** Claude-specific chat actions for the sidebar (Claude chats are loaded and renamed through the bridge). */
class ClaudeDrawerActions(
    val loadMessages: suspend (String) -> List<com.example.gemini.domain.model.ChatMessage>,
    val rename: (String, String) -> Unit,
    /** Stops the chat's process (when idle) so its next message starts one with the current provider settings; the callback gets a message. */
    val restart: (String, (String) -> Unit) -> Unit,
    /** Archives (true) or unarchives (false) a chat of any agent. */
    val archive: (String, Boolean) -> Unit = { _, _ -> }
)


/**
 * The Jarvis tab's "New Jarvis chat" button. The chat opens like any new chat (pick the agent above the empty chat, the
 * model in the input bar); it only adds Jarvis' tools, guide and command strip.
 */
@Composable
private fun JarvisNewChatRow(
    @Suppress("UNUSED_PARAMETER") agents: List<com.example.gemini.domain.model.AgentKind>,
    onNew: (com.example.gemini.domain.model.AgentKind?) -> Unit
) {
    val accent = com.example.gemini.ui.jarvis.Cyan
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.09f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable { onNew(null) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        com.example.gemini.ui.jarvis.JarvisBotIcon(26.dp, accent, animated = true)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("New Jarvis chat", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Any agent and model. Give it a big job: it plans, runs teams of agents and keeps going",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 2
            )
        }
        Icon(Icons.Filled.Add, contentDescription = "New Jarvis chat", tint = accent)
    }
}

@Composable
fun SidebarNavigationRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    textColor: Color,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    SoftElevatedBox(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(14.dp),
        backgroundColor = Color.White,
        elevation = 2.dp,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 14.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color(0xFF1A1A1A),
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = label,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (trailingContent != null) {
                trailingContent()
            }
        }
    }
}
