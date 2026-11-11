package com.example.gemini.ui.customize

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.R
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
    onOpenPlugins: (() -> Unit)? = null,
    personalityValue: String = "Default",
    memoryValue: String = "",
    pluginsValue: String = "5 added",
    emailValue: String = "Set up",
    calendarValue: String = "Set up",
    contactsValue: String = "Set up",
    passwordsValue: String = "0 items",
    locationValue: String = "Set up"
) {
    var activeSubPage by remember { mutableStateOf<CustomizeSubPage?>(null) }

    if (activeSubPage != null) {
        when (activeSubPage) {
            CustomizeSubPage.PERSONALITY -> {
                if (onOpenPersonality != null) {
                    LaunchedEffect(Unit) {
                        activeSubPage = null
                        onOpenPersonality()
                    }
                } else {
                    CustomizePlaceholderScreen(
                        title = "Personality",
                        description = "Choose how Aura speaks, behaves, and what it knows about your preferences.",
                        iconRes = R.drawable.ic_customize_personality,
                        onBack = { activeSubPage = null }
                    )
                }
            }
            CustomizeSubPage.MEMORY -> {
                if (onOpenMemory != null) {
                    LaunchedEffect(Unit) {
                        activeSubPage = null
                        onOpenMemory()
                    }
                } else {
                    CustomizePlaceholderScreen(
                        title = "Memory",
                        description = "Review and manage instructions and notes Aura has remembered across your conversations.",
                        iconRes = R.drawable.ic_customize_memory,
                        onBack = { activeSubPage = null }
                    )
                }
            }
            CustomizeSubPage.PLUGINS -> {
                if (onOpenPlugins != null) {
                    LaunchedEffect(Unit) {
                        activeSubPage = null
                        onOpenPlugins()
                    }
                } else {
                    CustomizePlaceholderScreen(
                        title = "Plugins",
                        description = "Manage MCP integrations, tools, and developer skills installed in your workspace.",
                        iconRes = R.drawable.ic_customize_plugins,
                        onBack = { activeSubPage = null }
                    )
                }
            }
            CustomizeSubPage.EMAIL -> {
                CustomizePlaceholderScreen(
                    title = "Email",
                    description = "Connect your email accounts so Aura can summarize messages, drafts, and notifications.",
                    iconRes = R.drawable.ic_customize_email,
                    onBack = { activeSubPage = null }
                )
            }
            CustomizeSubPage.CALENDAR -> {
                CustomizePlaceholderScreen(
                    title = "Calendar",
                    description = "Sync your schedule so Aura can help plan meetings, check availability, and manage events.",
                    iconRes = R.drawable.ic_customize_calendar,
                    onBack = { activeSubPage = null }
                )
            }
            CustomizeSubPage.CONTACTS -> {
                CustomizePlaceholderScreen(
                    title = "Contacts",
                    description = "Link your address book so Aura can look up people and assist you with communications.",
                    iconRes = R.drawable.ic_customize_contacts,
                    onBack = { activeSubPage = null }
                )
            }
            CustomizeSubPage.PASSWORDS -> {
                CustomizePlaceholderScreen(
                    title = "Passwords",
                    description = "Encrypted password vault secured with hardware-backed Android Keystore and biometric lock.",
                    iconRes = R.drawable.ic_customize_passwords,
                    onBack = { activeSubPage = null }
                )
            }
            CustomizeSubPage.LOCATION -> {
                CustomizePlaceholderScreen(
                    title = "Location",
                    description = "Allow Aura to tailor weather, navigation, and local search answers to your current location.",
                    iconRes = R.drawable.ic_customize_location,
                    onBack = { activeSubPage = null }
                )
            }
            null -> {}
        }
        return
    }

    BackHandler(onBack = onBack)
    val isDark = isAppInDarkTheme()
    val bgColor = MaterialTheme.colorScheme.background
    val cardBg = if (isDark) Color(0xFF1C1C1E) else Color.White
    val textColor = if (isDark) Color(0xFFEDEDED) else Color(0xFF1A1A1A)
    val subtitleColor = if (isDark) Color(0xFFA1A1AA) else Color(0xFF8A8A8E)
    val dividerColor = if (isDark) Color(0xFF2C2C2E) else Color(0xFFECECEE)
    val topBarBg = if (isDark) Color(0xFF1C1C1E) else Color.White

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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Group 1: Personality, Memory, Plugins
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = cardBg,
                shadowElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    CustomizeRow(
                        iconRes = R.drawable.ic_customize_personality,
                        title = "Personality",
                        value = personalityValue,
                        isAccentValue = false,
                        textColor = textColor,
                        subtitleColor = subtitleColor,
                        onClick = {
                            if (onOpenPersonality != null) onOpenPersonality()
                            else activeSubPage = CustomizeSubPage.PERSONALITY
                        }
                    )
                    HorizontalDivider(
                        color = dividerColor,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(start = 56.dp, end = 16.dp)
                    )
                    CustomizeRow(
                        iconRes = R.drawable.ic_customize_memory,
                        title = "Memory",
                        value = memoryValue,
                        isAccentValue = false,
                        textColor = textColor,
                        subtitleColor = subtitleColor,
                        onClick = {
                            if (onOpenMemory != null) onOpenMemory()
                            else activeSubPage = CustomizeSubPage.MEMORY
                        }
                    )
                    HorizontalDivider(
                        color = dividerColor,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(start = 56.dp, end = 16.dp)
                    )
                    CustomizeRow(
                        iconRes = R.drawable.ic_customize_plugins,
                        title = "Plugins",
                        value = pluginsValue,
                        isAccentValue = false,
                        textColor = textColor,
                        subtitleColor = subtitleColor,
                        onClick = {
                            if (onOpenPlugins != null) onOpenPlugins()
                            else activeSubPage = CustomizeSubPage.PLUGINS
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Group 2: Email, Calendar, Contacts, Passwords
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = cardBg,
                shadowElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    CustomizeRow(
                        iconRes = R.drawable.ic_customize_email,
                        title = "Email",
                        value = emailValue,
                        isAccentValue = emailValue.equals("Set up", ignoreCase = true),
                        textColor = textColor,
                        subtitleColor = subtitleColor,
                        onClick = { activeSubPage = CustomizeSubPage.EMAIL }
                    )
                    HorizontalDivider(
                        color = dividerColor,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(start = 56.dp, end = 16.dp)
                    )
                    CustomizeRow(
                        iconRes = R.drawable.ic_customize_calendar,
                        title = "Calendar",
                        value = calendarValue,
                        isAccentValue = calendarValue.equals("Set up", ignoreCase = true),
                        textColor = textColor,
                        subtitleColor = subtitleColor,
                        onClick = { activeSubPage = CustomizeSubPage.CALENDAR }
                    )
                    HorizontalDivider(
                        color = dividerColor,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(start = 56.dp, end = 16.dp)
                    )
                    CustomizeRow(
                        iconRes = R.drawable.ic_customize_contacts,
                        title = "Contacts",
                        value = contactsValue,
                        isAccentValue = contactsValue.equals("Set up", ignoreCase = true),
                        textColor = textColor,
                        subtitleColor = subtitleColor,
                        onClick = { activeSubPage = CustomizeSubPage.CONTACTS }
                    )
                    HorizontalDivider(
                        color = dividerColor,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(start = 56.dp, end = 16.dp)
                    )
                    CustomizeRow(
                        iconRes = R.drawable.ic_customize_passwords,
                        title = "Passwords",
                        value = passwordsValue,
                        isAccentValue = false,
                        textColor = textColor,
                        subtitleColor = subtitleColor,
                        onClick = { activeSubPage = CustomizeSubPage.PASSWORDS }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Group 3: Location
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = cardBg,
                shadowElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    CustomizeRow(
                        iconRes = R.drawable.ic_customize_location,
                        title = "Location",
                        value = locationValue,
                        isAccentValue = locationValue.equals("Set up", ignoreCase = true),
                        textColor = textColor,
                        subtitleColor = subtitleColor,
                        onClick = { activeSubPage = CustomizeSubPage.LOCATION }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CustomizeRow(
    @DrawableRes iconRes: Int,
    title: String,
    value: String,
    isAccentValue: Boolean,
    textColor: Color,
    subtitleColor: Color,
    onClick: () -> Unit
) {
    val accentColor = Color(0xFF2F6FED)

    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = title,
                tint = Color.Unspecified,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = textColor,
                modifier = Modifier.weight(1f)
            )

            if (value.isNotBlank()) {
                Text(
                    text = value,
                    fontSize = 14.sp,
                    color = if (isAccentValue) accentColor else subtitleColor
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
