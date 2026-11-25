package com.example.gemini.data.automation.agent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Calculates lightweight, stable screen signatures to detect state transitions,
 * scrolling progress, end-of-list conditions, and verify UI mutations.
 */
object ScreenSignatureCalculator {

    fun computeSignature(root: AccessibilityNodeInfo?): ScreenSignature {
        if (root == null) {
            return ScreenSignature(
                packageName = "",
                className = "",
                elementCount = 0,
                contentHash = 0L,
                primaryTexts = emptyList()
            )
        }

        val pkg = root.packageName?.toString() ?: ""
        val cls = root.className?.toString() ?: ""
        val texts = mutableListOf<String>()
        var count = 0
        var hash = 1125899906842597L // FNV-like seed

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        val tempRect = Rect()

        while (queue.isNotEmpty() && count < 300) {
            val node = queue.removeFirst()
            count++

            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val viewId = node.viewIdResourceName?.toString() ?: ""
            val isClickable = node.isClickable
            val isEditable = node.isEditable

            node.getBoundsInScreen(tempRect)

            if (text.isNotEmpty() && texts.size < 15) {
                texts.add(text)
            } else if (desc.isNotEmpty() && texts.size < 15) {
                texts.add(desc)
            }

            // Mix into hash
            hash = 31 * hash + text.hashCode()
            hash = 31 * hash + desc.hashCode()
            hash = 31 * hash + viewId.hashCode()
            hash = 31 * hash + (if (isClickable) 1 else 0)
            hash = 31 * hash + (if (isEditable) 2 else 0)
            hash = 31 * hash + tempRect.centerX()
            hash = 31 * hash + tempRect.centerY()

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    queue.add(child)
                }
            }
        }

        return ScreenSignature(
            packageName = pkg,
            className = cls,
            elementCount = count,
            contentHash = hash,
            primaryTexts = texts
        )
    }

    /**
     * Computes a unified ScreenSignature across multiple windows (e.g. main Activity +
     * PopupWindow / AlertDialog overlay) so menu popups reliably count as screen state transitions.
     */
    fun computeCombinedSignature(roots: List<AccessibilityNodeInfo>): ScreenSignature {
        if (roots.isEmpty()) return computeSignature(null)
        if (roots.size == 1) return computeSignature(roots[0])

        val primary = roots[0]
        var totalElements = 0
        var combinedHash = 1125899906842597L
        val combinedTexts = mutableListOf<String>()

        for (root in roots) {
            val sig = computeSignature(root)
            totalElements += sig.elementCount
            combinedHash = 31 * combinedHash + sig.packageName.hashCode() + sig.contentHash
            for (t in sig.primaryTexts) {
                if (combinedTexts.size < 18 && !combinedTexts.contains(t)) {
                    combinedTexts.add(t)
                }
            }
        }
        return ScreenSignature(
            packageName = primary.packageName?.toString() ?: "",
            className = primary.className?.toString() ?: "",
            elementCount = totalElements,
            contentHash = combinedHash,
            primaryTexts = combinedTexts
        )
    }

    /**
     * Generates a stable key for an individual interactive element (e.g. for bulk tracking).
     */
    fun computeElementKey(node: AccessibilityNodeInfo): String {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""
        return "${id}_${text}_${desc}_(${rect.left},${rect.top})"
    }
}
