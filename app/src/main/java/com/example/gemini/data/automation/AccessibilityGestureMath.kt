package com.example.gemini.data.automation

import java.util.Locale
import java.util.regex.Pattern

/**
 * Pure-Kotlin geometry, text-extraction, and notification parsing helpers shared by
 * [AutomationAccessibilityService] and [AndroidAutomation].
 */
object AccessibilityGestureMath {

    data class Point2D(val x: Float, val y: Float)

    data class PinchVectors(
        val finger1Start: Point2D,
        val finger1End: Point2D,
        val finger2Start: Point2D,
        val finger2End: Point2D
    )

    data class RawTextBlock(
        val text: String,
        val top: Int,
        val left: Int,
        val isPassword: Boolean = false,
        val isHeading: Boolean = false,
        val isEditable: Boolean = false
    )

    private val OTP_PATTERN: Pattern = Pattern.compile("\\b(\\d{4,8})\\b")

    /**
     * Clamps a screen coordinate into a safe inset margin inside the display bounds so Android's
     * gesture dispatcher never rejects strokes touching negative or out-of-bounds pixels.
     */
    fun clampPoint(x: Float, y: Float, screenWidth: Int, screenHeight: Int, marginPx: Float = 4f): Point2D {
        val maxX = (screenWidth - marginPx).coerceAtLeast(marginPx)
        val maxY = (screenHeight - marginPx).coerceAtLeast(marginPx)
        return Point2D(
            x = x.coerceIn(marginPx, maxX),
            y = y.coerceIn(marginPx, maxY)
        )
    }

    /**
     * Computes two symmetric finger paths along a slight diagonal around ([centerX], [centerY])
     * for pinch-to-zoom gestures.
     *
     * @param zoomIn true to spread fingers apart (zoom in), false to pinch fingers together (zoom out).
     * @param scale normalized pinch span factor in [0.15f, 0.85f] of the shorter screen edge.
     */
    fun computePinchVectors(
        centerX: Float,
        centerY: Float,
        screenWidth: Int,
        screenHeight: Int,
        zoomIn: Boolean,
        scale: Float = 0.45f
    ): PinchVectors {
        val minDim = minOf(screenWidth, screenHeight).coerceAtLeast(200).toFloat()
        val clampedScale = scale.coerceIn(0.15f, 0.85f)
        val innerRadius = (minDim * 0.06f).coerceAtLeast(28f)
        val outerRadius = (minDim * clampedScale * 0.5f).coerceAtLeast(innerRadius + 40f)

        val startRadius = if (zoomIn) innerRadius else outerRadius
        val endRadius = if (zoomIn) outerRadius else innerRadius

        // 45-degree diagonal avoids horizontal/vertical system edge gesture zones
        val diag = 0.70710677f
        val safeCenter = clampPoint(centerX, centerY, screenWidth, screenHeight, marginPx = 24f)

        val f1Start = clampPoint(
            safeCenter.x - startRadius * diag,
            safeCenter.y - startRadius * diag,
            screenWidth,
            screenHeight
        )
        val f1End = clampPoint(
            safeCenter.x - endRadius * diag,
            safeCenter.y - endRadius * diag,
            screenWidth,
            screenHeight
        )
        val f2Start = clampPoint(
            safeCenter.x + startRadius * diag,
            safeCenter.y + startRadius * diag,
            screenWidth,
            screenHeight
        )
        val f2End = clampPoint(
            safeCenter.x + endRadius * diag,
            safeCenter.y + endRadius * diag,
            screenWidth,
            screenHeight
        )

        return PinchVectors(
            finger1Start = f1Start,
            finger1End = f1End,
            finger2Start = f2Start,
            finger2End = f2End
        )
    }

    /**
     * Extracts a 4-8 digit verification/OTP code from notification text when OTP keywords are present.
     */
    fun extractOtpCode(notificationText: String): String? {
        val lower = notificationText.lowercase(Locale.ROOT)
        val hasKeyword = lower.contains("otp") ||
            lower.contains("code") ||
            lower.contains("verification") ||
            lower.contains("verify") ||
            lower.contains("passcode") ||
            lower.contains("pin")
        if (!hasKeyword) return null
        val matcher = OTP_PATTERN.matcher(notificationText)
        return if (matcher.find()) matcher.group(1) else null
    }

    /**
     * Orders visible screen text blocks top-to-bottom (with row tolerance) and left-to-right,
     * masks password fields, and deduplicates consecutive/repeated entries while preserving order.
     */
    fun organizeReadableBlocks(blocks: List<RawTextBlock>, rowTolerancePx: Int = 24): List<String> {
        val sorted = blocks
            .mapNotNull { block ->
                val cleaned = if (block.isPassword) {
                    if (block.text.isNotBlank()) "••••" else ""
                } else {
                    block.text.replace(Regex("\\s+"), " ").trim()
                }
                if (cleaned.isEmpty()) null else block.copy(text = cleaned)
            }
            .sortedWith(
                compareBy<RawTextBlock> { it.top / rowTolerancePx.coerceAtLeast(1) }
                    .thenBy { it.left }
            )

        val seen = LinkedHashSet<String>()
        val result = mutableListOf<String>()
        for (block in sorted) {
            val normalizedKey = block.text.lowercase(Locale.ROOT)
            if (seen.add(normalizedKey)) {
                val formatted = when {
                    block.isHeading -> "# ${block.text}"
                    block.isEditable -> "[input] ${block.text}"
                    else -> block.text
                }
                result.add(formatted)
            }
        }
        return result
    }

    data class SwipeMotionPlan(
        val start: Point2D,
        val engageEnd: Point2D,
        val engageDurationMs: Long,
        val flingEnd: Point2D,
        val flingDurationMs: Long
    )

    /**
     * Computes clamped start/end points for directional screen scrolls safely inside status-bar and
     * system gesture-navigation edge zones so Android 14-16 / OEM gesture filters do not cancel them.
     */
    fun computeSafeScrollPoints(
        direction: String,
        distance: String,
        screenWidth: Int,
        screenHeight: Int
    ): Pair<SwipeMotionPlan, Long> {
        val w = screenWidth.coerceAtLeast(240).toFloat()
        val h = screenHeight.coerceAtLeast(320).toFloat()
        val safeTop = (h * 0.14f).coerceAtLeast(96f)
        val safeBottom = (h * 0.82f).coerceAtMost(h - 110f).coerceAtLeast(safeTop + 120f)
        val safeSpanY = (safeBottom - safeTop).coerceAtLeast(120f)
        val deltaFrac = when (distance.lowercase(Locale.ROOT)) {
            "short", "small" -> 0.42f
            "long", "large" -> 0.85f
            else -> 0.65f
        }
        val totalDur = when (distance.lowercase(Locale.ROOT)) {
            "short", "small" -> 280L
            "long", "large" -> 360L
            else -> 310L
        }
        val dir = direction.lowercase(Locale.ROOT)
        val (sX, sY, eX, eY) = when (dir) {
            "up" -> listOf(w * 0.50f, safeTop + safeSpanY * 0.16f, w * 0.50f, (safeTop + safeSpanY * (0.16f + deltaFrac)).coerceAtMost(safeBottom))
            "left" -> listOf(w * 0.82f, h * 0.50f, w * 0.18f, h * 0.50f)
            "right" -> listOf(w * 0.18f, h * 0.50f, w * 0.82f, h * 0.50f)
            else -> listOf(w * 0.50f, safeBottom - safeSpanY * 0.12f, w * 0.50f, (safeBottom - safeSpanY * (0.12f + deltaFrac)).coerceAtLeast(safeTop))
        }
        val startPt = clampPoint(sX, sY, screenWidth, screenHeight, marginPx = 28f)
        val endPt = clampPoint(eX, eY, screenWidth, screenHeight, marginPx = 28f)
        val engagePt = clampPoint(
            startPt.x + (endPt.x - startPt.x) * 0.18f,
            startPt.y + (endPt.y - startPt.y) * 0.18f,
            screenWidth,
            screenHeight,
            marginPx = 28f
        )
        val engageMs = (totalDur * 0.30f).toLong().coerceIn(50L, 120L)
        val flingMs = (totalDur - engageMs).coerceAtLeast(110L)
        return SwipeMotionPlan(startPt, engagePt, engageMs, endPt, flingMs) to totalDur
    }

    /**
     * Computes a two-stage touch-slop + fast-release fling plan for vertical short-video ViewPager2 /
     * RecyclerView feeds (YouTube Shorts, Instagram Reels, TikTok), staying clear of right-side icons.
     */
    fun computeReelSwipeMotion(
        upward: Boolean,
        attempt: Int,
        screenWidth: Int,
        screenHeight: Int
    ): SwipeMotionPlan {
        val w = screenWidth.coerceAtLeast(240).toFloat()
        val h = screenHeight.coerceAtLeast(320).toFloat()
        val cx = if (attempt <= 1) w * 0.44f else w * 0.38f
        val safeTop = (h * 0.16f).coerceAtLeast(110f)
        val safeBottom = (h * 0.78f).coerceAtMost(h - 130f).coerceAtLeast(safeTop + 160f)

        val startY: Float
        val engageY: Float
        val endY: Float
        val engageMs: Long
        val flingMs: Long
        if (upward) {
            if (attempt <= 1) {
                startY = safeBottom
                engageY = safeBottom - (safeBottom - safeTop) * 0.16f
                endY = safeTop + (safeBottom - safeTop) * 0.06f
                engageMs = 55L
                flingMs = 165L
            } else {
                startY = safeBottom + 20f
                engageY = safeBottom - (safeBottom - safeTop) * 0.18f
                endY = safeTop
                engageMs = 50L
                flingMs = 140L
            }
        } else {
            if (attempt <= 1) {
                startY = safeTop + (safeBottom - safeTop) * 0.08f
                engageY = safeTop + (safeBottom - safeTop) * 0.24f
                endY = safeBottom
                engageMs = 55L
                flingMs = 175L
            } else {
                startY = safeTop
                engageY = safeTop + (safeBottom - safeTop) * 0.22f
                endY = safeBottom
                engageMs = 50L
                flingMs = 150L
            }
        }
        return SwipeMotionPlan(
            start = clampPoint(cx, startY, screenWidth, screenHeight, marginPx = 24f),
            engageEnd = clampPoint(cx, engageY, screenWidth, screenHeight, marginPx = 24f),
            engageDurationMs = engageMs,
            flingEnd = clampPoint(cx, endY, screenWidth, screenHeight, marginPx = 24f),
            flingDurationMs = flingMs
        )
    }
}
