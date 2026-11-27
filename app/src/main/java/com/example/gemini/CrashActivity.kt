package com.example.gemini

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Dependency-free platform-View crash dialog hosted in `:crash` so any uncaught exception
 * shows an actionable, copyable stack trace instead of closing silently.
 */
class CrashActivity : Activity() {

    companion object {
        const val EXTRA_TRACE = "extra_crash_trace"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val trace = intent.getStringExtra(EXTRA_TRACE)?.takeIf { it.isNotBlank() }
            ?: runCatching { File(filesDir, "last_crash.txt").readText() }.getOrNull()
            ?: "Unknown crash (no stack trace available)."

        val dp = { v: Int ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111318"))
            setPadding(dp(18), dp(42), dp(18), dp(22))
        }

        val title = TextView(this).apply {
            text = "Aura AI stopped unexpectedly"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTypeface(Typeface.DEFAULT_BOLD)
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "Crash log saved to Downloads/AuraAI_last_crash.txt. You can copy this trace to fix the issue:"
            setTextColor(Color.parseColor("#B0B7C3"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(12))
        }
        root.addView(subtitle)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(Color.parseColor("#1A1D24"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val traceView = TextView(this).apply {
            text = trace
            setTextColor(Color.parseColor("#F3F4F6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scroll.addView(traceView)
        root.addView(scroll)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
        }

        val copyBtn = Button(this).apply {
            text = "Copy Error"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("Aura AI Crash", trace))
                Toast.makeText(this@CrashActivity, "Crash trace copied", Toast.LENGTH_SHORT).show()
            }
        }

        val restartBtn = Button(this).apply {
            text = "Restart"
            setOnClickListener {
                val launch = packageManager.getLaunchIntentForPackage(packageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(launch)
                }
                finish()
            }
        }

        btnRow.addView(copyBtn)
        btnRow.addView(restartBtn)
        root.addView(btnRow)

        setContentView(root)
    }
}
