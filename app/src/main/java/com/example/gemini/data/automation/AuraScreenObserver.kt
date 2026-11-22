package com.example.gemini.data.automation

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Continuous, proactive screen monitor for Aura AI.
 * Immediately detects visual changes, window transitions, active dialogs,
 * and provides real-time state feedback for step-by-step guidance without waiting
 * for user queries.
 */
object AuraScreenObserver {

    private const val TAG = "AuraScreenObserver"

    data class ScreenState(
        val packageName: String = "",
        val className: String = "",
        val screenTitle: String = "",
        val visibleHeaders: List<String> = emptyList(),
        val lastUpdated: Long = System.currentTimeMillis()
    )

    data class GuidanceStep(
        val stepIndex: Int,
        val targetScreenKeyword: String,
        val instructionPrompt: String
    )

    private val _currentScreen = MutableStateFlow(ScreenState())
    val currentScreen: StateFlow<ScreenState> = _currentScreen.asStateFlow()

    private var activeGuidanceSteps: List<GuidanceStep> = emptyList()
    private var currentGuidanceStepIndex: Int = 0
    private var guidanceCallback: ((stepIndex: Int, message: String) -> Unit)? = null

    var isContinuousMonitoringEnabled: Boolean = true

    /**
     * Start a proactive step-by-step guidance session.
     * When the user moves to the next screen (e.g. from Chat -> Settings),
     * [onStepReached] is immediately triggered with spoken feedback.
     */
    fun startGuidanceSession(
        steps: List<GuidanceStep>,
        onStepReached: (stepIndex: Int, message: String) -> Unit
    ) {
        activeGuidanceSteps = steps
        currentGuidanceStepIndex = 0
        guidanceCallback = onStepReached
        Log.i(TAG, "Started proactive guidance session with ${steps.size} steps.")
    }

    fun stopGuidanceSession() {
        activeGuidanceSteps = emptyList()
        currentGuidanceStepIndex = 0
        guidanceCallback = null
        Log.i(TAG, "Stopped guidance session.")
    }

    private val observerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private var pendingDebounceJob: kotlinx.coroutines.Job? = null
    private var lastContentCheckTime = 0L

    /**
     * Debounced content change checker: off the main UI thread, avoiding micro-event lag.
     */
    fun scheduleContentCheck(pkg: String?) {
        if (!isContinuousMonitoringEnabled || activeGuidanceSteps.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastContentCheckTime < 400L) {
            pendingDebounceJob?.cancel()
            pendingDebounceJob = observerScope.launch {
                kotlinx.coroutines.delay(400L)
                val service = AutomationAccessibilityService.instance ?: return@launch
                val root = service.getActiveRootNode()
                processInternal(pkg ?: _currentScreen.value.packageName, _currentScreen.value.className, root)
            }
            return
        }
        lastContentCheckTime = now
        val service = AutomationAccessibilityService.instance ?: return
        observerScope.launch {
            val root = service.getActiveRootNode()
            processInternal(pkg ?: _currentScreen.value.packageName, _currentScreen.value.className, root)
        }
    }

    /**
     * Called by AutomationAccessibilityService on window state changes.
     */
    fun processAccessibilityEvent(event: AccessibilityEvent, rootNode: AccessibilityNodeInfo?) {
        if (!isContinuousMonitoringEnabled) return
        val pkg = event.packageName?.toString() ?: _currentScreen.value.packageName
        val cls = event.className?.toString() ?: _currentScreen.value.className
        processInternal(pkg, cls, rootNode)
    }

    private fun processInternal(pkg: String, cls: String, rootNode: AccessibilityNodeInfo?) {
        // Fast path: if no guidance is running, don't perform expensive deep hierarchy crawls
        if (activeGuidanceSteps.isEmpty()) {
            val previous = _currentScreen.value
            if (pkg != previous.packageName || cls != previous.className) {
                _currentScreen.value = ScreenState(
                    packageName = pkg,
                    className = cls,
                    screenTitle = previous.screenTitle,
                    visibleHeaders = previous.visibleHeaders,
                    lastUpdated = System.currentTimeMillis()
                )
            }
            return
        }

        // Extract visible title / headers from the active root
        val headers = mutableListOf<String>()
        var detectedTitle = ""

        if (rootNode != null) {
            extractKeyHeaders(rootNode, headers)
            detectedTitle = headers.firstOrNull() ?: ""
        }

        val previous = _currentScreen.value
        val hasPackageChanged = pkg != previous.packageName
        val hasTitleChanged = detectedTitle.isNotBlank() && detectedTitle != previous.screenTitle

        if (hasPackageChanged || hasTitleChanged || headers != previous.visibleHeaders) {
            val newState = ScreenState(
                packageName = pkg,
                className = cls,
                screenTitle = detectedTitle,
                visibleHeaders = headers,
                lastUpdated = System.currentTimeMillis()
            )
            _currentScreen.value = newState
            Log.d(TAG, "Screen update detected: pkg=$pkg, title='$detectedTitle', headers=$headers")

            // Check if active guidance step has been fulfilled
            checkGuidanceProgression(newState)
        }
    }

    private fun extractKeyHeaders(node: AccessibilityNodeInfo, outHeaders: MutableList<String>, depth: Int = 0) {
        if (depth > 6 || outHeaders.size >= 5) return

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""

        val isHeaderId = viewId.contains("title") || viewId.contains("toolbar") || viewId.contains("header") || viewId.contains("action_bar")
        if (text.isNotBlank() && (isHeaderId || (text.length in 3..40 && depth <= 3))) {
            if (!outHeaders.contains(text)) {
                outHeaders.add(text)
            }
        } else if (desc.isNotBlank() && isHeaderId) {
            if (!outHeaders.contains(desc)) {
                outHeaders.add(desc)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractKeyHeaders(child, outHeaders, depth + 1)
        }
    }

    private fun checkGuidanceProgression(state: ScreenState) {
        val steps = activeGuidanceSteps
        val callback = guidanceCallback ?: return
        if (currentGuidanceStepIndex !in steps.indices) return

        val currentStep = steps[currentGuidanceStepIndex]
        val targetKeyword = currentStep.targetScreenKeyword.lowercase(Locale.ROOT)

        val matches = state.packageName.lowercase(Locale.ROOT).contains(targetKeyword) ||
                state.screenTitle.lowercase(Locale.ROOT).contains(targetKeyword) ||
                state.visibleHeaders.any { it.lowercase(Locale.ROOT).contains(targetKeyword) }

        if (matches) {
            Log.i(TAG, "Guidance step $currentGuidanceStepIndex reached: '${currentStep.targetScreenKeyword}'")
            callback(currentStep.stepIndex, currentStep.instructionPrompt)
            currentGuidanceStepIndex++
            if (currentGuidanceStepIndex >= steps.size) {
                stopGuidanceSession()
            }
        }
    }
}
