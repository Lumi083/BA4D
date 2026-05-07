package com.miradesktop.ba4d

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BA4DApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        setupCrashHandler()
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrashLog(thread, throwable)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun saveCrashLog(thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val crashFile = File(getExternalFilesDir(null), "crash_$timestamp.txt")

        crashFile.bufferedWriter().use { writer ->
            writer.write("=== BA4D Crash Report ===\n")
            writer.write("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
            writer.write("Thread: ${thread.name}\n")
            writer.write("Package: $packageName\n\n")

            writer.write("=== Stack Trace ===\n")
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            writer.write(sw.toString())
        }
    }
}
