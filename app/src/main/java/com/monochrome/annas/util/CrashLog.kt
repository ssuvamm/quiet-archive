package com.monochrome.annas.util

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Optional, local-only crash log. Nothing is uploaded anywhere. */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last-crash.txt")

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (context.getSharedPreferences("annas", Context.MODE_PRIVATE).getBoolean("crash_reporting", false)) {
                try {
                    val report = buildString {
                        append("Quiet Archive crash\n")
                        append("Time: ${java.util.Date()}\n")
                        append("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})\n")
                        append("Thread: ${thread.name}\n\n")
                        append(error.stackTraceToString())
                    }
                    file(context).writeText(report)
                } catch (_: Exception) {}
            }
            try {
                previous?.uncaughtException(thread, error)
            } finally {
                if (previous == null) kotlin.system.exitProcess(1)
            }
        }
    }

    fun share(context: Context) {
        val report = file(context)
        if (!report.isFile) {
            Toast.makeText(context, "No crash log has been recorded", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", report)
            context.startActivity(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Quiet Archive crash report")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.let { Intent.createChooser(it, "Share crash report") }
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            Toast.makeText(context, "Could not share the crash log: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
