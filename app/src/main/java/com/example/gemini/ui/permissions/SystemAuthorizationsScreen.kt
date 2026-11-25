package com.example.gemini.ui.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.gemini.data.automation.AndroidAutomation
import com.example.gemini.data.automation.AutomationAccessibilityService
import com.example.gemini.theme.AppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemAuthorizationsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Permission states
    var hasMic by remember { mutableStateOf(hasPerm(context, Manifest.permission.RECORD_AUDIO)) }
    var hasLoc by remember { mutableStateOf(hasPerm(context, Manifest.permission.ACCESS_FINE_LOCATION) || hasPerm(context, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    var hasNotif by remember { mutableStateOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) hasPerm(context, Manifest.permission.POST_NOTIFICATIONS) else true) }
    val isA11yConnected by AutomationAccessibilityService.isConnected.collectAsState()
    var hasOverlay by remember { mutableStateOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true) }
    var hasA11ySetting by remember { mutableStateOf(AndroidAutomation.isServiceEnabledInSettings(context)) }
    val hasA11y = isA11yConnected || AutomationAccessibilityService.instance != null || hasA11ySetting
    var hasContacts by remember { mutableStateOf(hasPerm(context, Manifest.permission.READ_CONTACTS)) }
    var hasPhone by remember { mutableStateOf(hasPerm(context, Manifest.permission.CALL_PHONE)) }
    var hasCamera by remember { mutableStateOf(hasPerm(context, Manifest.permission.CAMERA)) }
    var hasBluetooth by remember { mutableStateOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) hasPerm(context, Manifest.permission.BLUETOOTH_CONNECT) else true) }
    var hasBattery by remember { mutableStateOf(isIgnoringBattery(context)) }
    var hasSettings by remember { mutableStateOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.System.canWrite(context) else true) }
    var hasNotifListener by remember { mutableStateOf(isNotifListenerEnabled(context)) }

    fun refreshAll() {
        hasMic = hasPerm(context, Manifest.permission.RECORD_AUDIO)
        hasLoc = hasPerm(context, Manifest.permission.ACCESS_FINE_LOCATION) || hasPerm(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) hasPerm(context, Manifest.permission.POST_NOTIFICATIONS) else true
        hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
        hasA11ySetting = AndroidAutomation.isServiceEnabledInSettings(context)
        hasContacts = hasPerm(context, Manifest.permission.READ_CONTACTS)
        hasPhone = hasPerm(context, Manifest.permission.CALL_PHONE)
        hasCamera = hasPerm(context, Manifest.permission.CAMERA)
        hasBluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) hasPerm(context, Manifest.permission.BLUETOOTH_CONNECT) else true
        hasBattery = isIgnoringBattery(context)
        hasSettings = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.System.canWrite(context) else true
        hasNotifListener = isNotifListenerEnabled(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshAll()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Launchers
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshAll() }
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshAll() }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshAll() }
    val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshAll() }
    val phoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshAll() }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshAll() }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshAll() }
    val allLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshAll() }

    val allGranted = hasMic && hasLoc && hasNotif && hasOverlay && hasA11y && hasContacts && hasPhone

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("System Authorizations", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Permissions & Privacy Control", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 32.dp)
        ) {
            // Hero Banner Card
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(AppColors.accentTeal.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Shield,
                                contentDescription = null,
                                tint = AppColors.accentTeal,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Aura AI • Autonomous Capabilities",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Grant authorizations so Aura can listen, speak duplex, automate screen tasks, and place calls seamlessly.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // Card 1: Microphone Access
            item {
                AuthCard(
                    title = "Microphone Access",
                    description = "Required for voice conversations, voice recognition, and real-time audio interaction with Aura.",
                    granted = hasMic,
                    icon = Icons.Outlined.Mic,
                    onAction = {
                        if (!hasMic) micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        else openAppDetails(context)
                    }
                )
            }

            // Card 2: Precise Location
            item {
                AuthCard(
                    title = "Precise Location",
                    description = "Required to detect your exact city/locality and provide accurate real-time weather and temperature reports on Home screen.",
                    granted = hasLoc,
                    icon = Icons.Outlined.LocationOn,
                    onAction = {
                        if (!hasLoc) locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        else openAppDetails(context)
                    }
                )
            }

            // Card 3: Notifications
            item {
                AuthCard(
                    title = "Notifications",
                    description = "Required for reminders, schedule alerts, and continuous background voice service notifications.",
                    granted = hasNotif,
                    icon = Icons.Outlined.Notifications,
                    onAction = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotif) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else openAppDetails(context)
                    }
                )
            }

            // Card 4: Display Over Other Apps
            item {
                AuthCard(
                    title = "Display Over Other Apps",
                    description = "Optional: Allows Aura floating glowing orb to appear anywhere on your phone for quick one-tap voice commands.",
                    granted = hasOverlay,
                    icon = Icons.Outlined.Layers,
                    onAction = {
                        requestOverlay(context)
                    }
                )
            }

            // Card 5: Accessibility Service
            item {
                AuthCard(
                    title = "Accessibility Service",
                    description = "Essential for autonomous AI actions: allows Aura to tap buttons, search apps, type messages, and scroll hands-free on your screen.",
                    granted = hasA11y,
                    icon = Icons.Outlined.AccessibilityNew,
                    onAction = {
                        openA11ySettings(context)
                    }
                )
            }

            // Card 6: Screen Share & Live Vision
            item {
                AuthCard(
                    title = "Screen Share & Live Vision",
                    description = "Allows Aura to view and explain what is on your screen hands-free when you say 'Screen dekho' or 'Screen share on karo'.",
                    granted = true, // MediaProjection is requested on-demand at runtime
                    manageLabel = "Configured ✓",
                    icon = Icons.Outlined.ScreenShare,
                    onAction = {
                        Toast.makeText(context, "Active on voice command 'Screen dekho'", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // Card 7: Notification Reader Access
            item {
                AuthCard(
                    title = "Notification Reader Access",
                    description = "Allows Aura to read incoming notifications and messages aloud when you ask 'Meri notification padho' or 'Koi message aaya hai kya'.",
                    granted = hasNotifListener,
                    icon = Icons.Outlined.MarkChatUnread,
                    onAction = {
                        openNotifListenerSettings(context)
                    }
                )
            }

            // Card 8: Contacts
            item {
                AuthCard(
                    title = "Contacts",
                    description = "Lets WhatsApp and calling features resolve saved contact names and nicknames.",
                    granted = hasContacts,
                    icon = Icons.Outlined.Contacts,
                    onAction = {
                        if (!hasContacts) contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
                        else openAppDetails(context)
                    }
                )
            }

            // Card 9: Phone calls
            item {
                AuthCard(
                    title = "Phone calls",
                    description = "Needed only when you ask Aura to place a normal phone call directly.",
                    granted = hasPhone,
                    icon = Icons.Outlined.Phone,
                    onAction = {
                        if (!hasPhone) phoneLauncher.launch(Manifest.permission.CALL_PHONE)
                        else openAppDetails(context)
                    }
                )
            }

            // Card 10: Camera and flashlight
            item {
                AuthCard(
                    title = "Camera and flashlight",
                    description = "Camera access is used for vision analysis and the flashlight control ('Torch on karo').",
                    granted = hasCamera,
                    icon = Icons.Outlined.FlashlightOn,
                    onAction = {
                        if (!hasCamera) cameraLauncher.launch(Manifest.permission.CAMERA)
                        else openAppDetails(context)
                    }
                )
            }

            // Card 11: Nearby Bluetooth devices
            item {
                AuthCard(
                    title = "Nearby Bluetooth devices",
                    description = "Optional: allows Aura to detect and route audio to a connected headset.",
                    granted = hasBluetooth,
                    icon = Icons.Outlined.Bluetooth,
                    onAction = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetooth) {
                            btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        } else openAppDetails(context)
                    }
                )
            }

            // Card 12: Background battery access
            item {
                AuthCard(
                    title = "Background battery access",
                    description = "Recommended on Vivo/Oppo devices so voice service and overlay stay active in the background.",
                    granted = hasBattery,
                    icon = Icons.Outlined.BatterySaver,
                    onAction = {
                        requestIgnoreBattery(context)
                    }
                )
            }

            // Card 13: Modify system settings
            item {
                AuthCard(
                    title = "Modify system settings",
                    description = "Optional: lets Aura adjust screen brightness and volume when you ask.",
                    granted = hasSettings,
                    icon = Icons.Outlined.BrightnessMedium,
                    onAction = {
                        requestWriteSettings(context)
                    }
                )
            }

            // Bottom Buttons
            item {
                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        val missing = mutableListOf<String>()
                        if (!hasMic) missing.add(Manifest.permission.RECORD_AUDIO)
                        if (!hasLoc) {
                            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
                            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotif) {
                            missing.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (!hasContacts) missing.add(Manifest.permission.READ_CONTACTS)
                        if (!hasPhone) missing.add(Manifest.permission.CALL_PHONE)
                        if (!hasCamera) missing.add(Manifest.permission.CAMERA)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetooth) {
                            missing.add(Manifest.permission.BLUETOOTH_CONNECT)
                        }

                        if (missing.isNotEmpty()) {
                            allLauncher.launch(missing.toTypedArray())
                        } else if (!hasOverlay) {
                            requestOverlay(context)
                        } else if (!hasA11y) {
                            openA11ySettings(context)
                        } else if (!hasBattery) {
                            requestIgnoreBattery(context)
                        } else {
                            Toast.makeText(context, "All key authorizations granted!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (allGranted) Color(0xFF10B981) else Color(0xFFEA580C)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        if (allGranted) Icons.Outlined.CheckCircle else Icons.Outlined.LockOpen,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (allGranted) "ALL ESSENTIAL PERMISSIONS GRANTED ✓" else "GRANT ALL PERMISSIONS",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 13.5.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { openAppDetails(context) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = AppColors.accentTeal
                    ),
                    border = BorderStroke(1.dp, AppColors.accentTeal)
                ) {
                    Text("OPEN SYSTEM APP SETTINGS", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun AuthCard(
    title: String,
    description: String,
    granted: Boolean,
    icon: ImageVector,
    manageLabel: String = "Manage",
    onAction: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (granted) Color(0xFF10B981).copy(alpha = 0.12f) else Color(0xFFF59E0B).copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (granted) Color(0xFF10B981) else Color(0xFFF59E0B),
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (granted) "Active & Granted ✓" else "Action Required • Tap Grant",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (granted) Color(0xFF10B981) else Color(0xFFF59E0B)
                    )
                }

                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (granted) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFEA580C)
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(
                        text = if (granted) manageLabel else "Grant",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (granted) MaterialTheme.colorScheme.onSurfaceVariant else Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }
    }
}

// Helpers
private fun hasPerm(context: Context, perm: String): Boolean =
    ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

private fun isIgnoringBattery(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return pm?.isIgnoringBatteryOptimizations(context.packageName) == true
}

private fun isA11yEnabled(context: Context): Boolean =
    AndroidAutomation.isServiceEnabledInSettings(context)

private fun isNotifListenerEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
    return flat.contains(context.packageName)
}

private fun requestOverlay(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(fallback)
        }
    }
}

private fun openA11ySettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
    } catch (_: Exception) {}
}

private fun requestIgnoreBattery(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(fallback)
    }
}

private fun requestWriteSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }
}

private fun openNotifListenerSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {}
}

private fun openAppDetails(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {}
}
