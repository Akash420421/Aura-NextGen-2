package com.example.gemini.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.UiModeManager
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
val LocalIsDarkTheme = staticCompositionLocalOf { false }

@Composable
fun isAppInDarkTheme(): Boolean = false

@Composable
fun isSystemInDarkThemeRobust(): Boolean = false

@Composable
fun GeminiTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    // Pure Light Theme enforced across the entire application
    val effectiveDark = false
    ThemeManager.setEffectiveDark(effectiveDark)
    val colorScheme = ThemeManager.schemeOf(ThemeManager.themeFor(effectiveDark))
    val themeColors = ThemeManager.colors
    SideEffect { ThemeTerminal.apply(themeColors) }

    val view = LocalView.current
    if (!view.isInEditMode) {
        val activity = view.context as? Activity
        if (activity != null && activity !is com.example.gemini.ui.bubble.FloatingChatActivity) {
            SideEffect {
                val window = activity.window
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = colorScheme.background.toArgb()
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(colorScheme.background.toArgb()))
                val insetsController = WindowCompat.getInsetsController(window, view)
                // Dark icons on status bar and navigation bar for consistent light theme
                insetsController.isAppearanceLightStatusBars = true
                insetsController.isAppearanceLightNavigationBars = true
            }
        }
    }

    CompositionLocalProvider(
        LocalIsDarkTheme provides false
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
