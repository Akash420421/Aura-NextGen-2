package com.example.gemini

import android.app.ActivityManager
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Installs a lightweight, dependency-free global uncaught exception handler that
 * records any fatal crash trace to app storage & Downloads/AuraAI_last_crash.txt
 * and opens [CrashActivity] in a dedicated `:crash` process instead of closing silently.
 */
object CrashLogger {

    private const val TAG = "AuraCrashLogger"
    private const val DOWNLOAD_CRASH_FILE = "AuraAI_last_crash.txt"

    @Volatile
    private var installed = false

    @Volatile
    private var handlingCrash = false

    fun isCrashProcess(context: Context): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { Application.getProcessName() }.getOrNull()
        } else {
            val pid = Process.myPid()
            runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
            }.getOrNull()
        }
        return name?.endsWith(":crash") == true
    }

    @Synchronized
    fun install(application: Application) {
        if (installed) return
        installed = true
        val appCtx = application.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (handlingCrash) {
                previousHandler?.uncaughtException(thread, throwable)
                return@setDefaultUncaughtExceptionHandler
            }
            handlingCrash = true
            try {
                val report = buildReport(thread.name, throwable)
                Log.e(TAG, report)
                writeCrashReport(appCtx, report)

                val intent = Intent(appCtx, CrashActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                    putExtra(CrashActivity.EXTRA_TRACE, report.take(16_000))
                }
                runCatching { appCtx.startActivity(intent) }
                runCatching { Thread.sleep(200) }
            } catch (_: Throwable) {
            } finally {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    fun recordNonFatal(context: Context, where: String, throwable: Throwable) {
        val msg = "Non-fatal startup error in $where:\n${Log.getStackTraceString(throwable)}"
        Log.e(TAG, msg, throwable)
        runCatching {
            val f = File(context.applicationContext.filesDir, "last_nonfatal.txt")
            f.appendText("${nowStamp()} $msg\n---\n")
        }
    }

    private fun nowStamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private fun buildReport(threadName: String, throwable: Throwable): String = buildString {
        appendLine("Aura AI Crash Report")
        appendLine("Time: ${nowStamp()}")
        appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) [${BuildConfig.FLAVOR}/${BuildConfig.BUILD_TYPE}]")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Thread: $threadName")
        appendLine()
        appendLine(Log.getStackTraceString(throwable))
    }

    private fun writeCrashReport(context: Context, report: String) {
        runCatching {
            File(context.filesDir, "last_crash.txt").writeText(report)
        }
        val wroteDirect = runCatching {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloads != null && (downloads.exists() || downloads.mkdirs())) {
                File(downloads, DOWNLOAD_CRASH_FILE).writeText(report)
                true
            } else {
                false
            }
        }.getOrDefault(false)

        if (!wroteDirect && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, DOWNLOAD_CRASH_FILE)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(report.toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }
    }
}
