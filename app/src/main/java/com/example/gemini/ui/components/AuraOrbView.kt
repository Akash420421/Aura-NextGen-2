package com.example.gemini.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.gemini.data.jarvis.JarvisController.Phase

class AuraOrbBridge(
    private val onOrbClick: () -> Unit
) {
    @JavascriptInterface
    fun onOrbTapped() {
        onOrbClick()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AuraOrbView(
    modifier: Modifier = Modifier,
    phase: Phase = Phase.IDLE,
    livePhase: com.example.gemini.data.live.AuraLiveVoiceManager.Phase? = null,
    isPaused: Boolean = false,
    onOrbTapped: () -> Unit = {}
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun resolveMode(): String {
        return if (livePhase != null) {
            when (livePhase) {
                com.example.gemini.data.live.AuraLiveVoiceManager.Phase.LISTENING -> "listening"
                com.example.gemini.data.live.AuraLiveVoiceManager.Phase.THINKING -> "thinking"
                com.example.gemini.data.live.AuraLiveVoiceManager.Phase.WORKING -> "working"
                com.example.gemini.data.live.AuraLiveVoiceManager.Phase.SPEAKING -> "speaking"
                else -> "idle"
            }
        } else {
            when (phase) {
                Phase.LISTENING -> "listening"
                Phase.THINKING -> "thinking"
                Phase.SPEAKING -> "speaking"
                else -> "idle"
            }
        }
    }

    // Sync active/pause state to WebGL orb to eliminate GPU overdraw & battery drain when not visible
    LaunchedEffect(isPaused, webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        if (isPaused) {
            wv.evaluateJavascript("if (window.pauseOrb) window.pauseOrb();", null)
            wv.onPause()
            wv.pauseTimers()
        } else {
            wv.onResume()
            wv.resumeTimers()
            wv.evaluateJavascript("if (window.resumeOrb) window.resumeOrb();", null)
        }
    }

    DisposableEffect(webViewRef) {
        onDispose {
            webViewRef?.evaluateJavascript("if (window.pauseOrb) window.pauseOrb();", null)
            webViewRef?.onPause()
            webViewRef?.pauseTimers()
        }
    }

    // Sync phase to WebGL orb
    LaunchedEffect(phase, livePhase, webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        val mode = resolveMode()
        wv.evaluateJavascript(
            "if (window.setAuraMode) { window.setAuraMode('$mode'); } else if (window.JarvisOrb && window.JarvisOrb.setState) { window.JarvisOrb.setState('$mode'); }",
            null
        )
    }

    Box(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onOrbTapped
        )
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(Color.TRANSPARENT)
                    setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        @Suppress("DEPRECATION")
                        allowFileAccessFromFileURLs = true
                        @Suppress("DEPRECATION")
                        allowUniversalAccessFromFileURLs = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        cacheMode = WebSettings.LOAD_NO_CACHE
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            val mode = resolveMode()
                            view?.evaluateJavascript(
                                "if (window.setAuraMode) { window.setAuraMode('$mode'); } else if (window.JarvisOrb && window.JarvisOrb.setState) { window.JarvisOrb.setState('$mode'); }",
                                null
                            )
                        }
                    }
                    addJavascriptInterface(
                        AuraOrbBridge {
                            post { onOrbTapped() }
                        },
                        "AndroidBridge"
                    )
                    setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                        false
                    }
                    loadUrl("file:///android_asset/orb/orb.html")
                    webViewRef = this
                }
            },
            update = { wv ->
                webViewRef = wv
            }
        )
    }
}
