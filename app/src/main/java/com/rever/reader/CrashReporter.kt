package com.rever.reader

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReverApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}

object CrashReporter {
    private const val PREFS = "crash_reports"
    private const val TAG = "ReverCrash"

    fun folder(context: Context): File = File(context.filesDir, "crash_reports").apply { mkdirs() }

    fun reports(context: Context): List<File> = folder(context).listFiles()
        ?.filter { it.isFile && it.extension in listOf("log", "pb", "trace") }
        ?.sortedByDescending { it.name } ?: emptyList()

    fun markNarration(context: Context, bookId: String, paragraph: Paragraph, stage: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("narration", "book=$bookId page=${paragraph.page} paragraph=${paragraph.index} " +
                "characters=${paragraph.text.length} stage=$stage")
            .commit()
    }

    fun markStage(context: Context, stage: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prior = prefs.getString("narration", "none").orEmpty().substringBeforeLast(" stage=")
        prefs.edit().putString("narration", "$prior stage=$stage").commit()
    }

    fun install(application: Application) {
        try { collectPreviousExits(application) }
        catch (error: Exception) { Log.e(TAG, "Falha ao consultar encerramentos anteriores", error) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val detail = Log.getStackTraceString(error).ifBlank { error.toString() }
                write(application, "java", "thread=${thread.name}\n$detail")
            } catch (failure: Throwable) {
                Log.e(TAG, "Falha ao salvar relatório", failure)
            } finally {
                previous?.uncaughtException(thread, error) ?: Process.killProcess(Process.myPid())
            }
        }
    }

    private fun collectPreviousExits(context: Context) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong("last_exit_timestamp", 0L)
        val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 20)
        var newest = last
        for (exit in exits.asReversed()) {
            if (exit.timestamp <= last) continue
            newest = maxOf(newest, exit.timestamp)
            if (exit.reason !in listOf(ApplicationExitInfo.REASON_CRASH,
                    ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
                    ApplicationExitInfo.REASON_LOW_MEMORY)) continue
            val detail = "reason=${reasonName(exit.reason)} (${exit.reason})\n" +
                "process=${exit.processName}\npid=${exit.pid}\n" +
                "description=${exit.description.orEmpty()}\n" +
                "pss_kb=${exit.pss}\nrss_kb=${exit.rss}\n"
            val report = write(context, "exit-${exit.timestamp}-${exit.pid}", detail)
            try {
                exit.traceInputStream?.use { input ->
                    val extension = if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) "pb" else "trace"
                    File(folder(context), "${report.nameWithoutExtension}.$extension").outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (error: Exception) { Log.e(TAG, "Falha ao salvar rastro do encerramento", error) }
        }
        if (newest > last) prefs.edit().putLong("last_exit_timestamp", newest).apply()
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "java_crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        else -> "other"
    }

    private fun write(context: Context, kind: String, detail: String): File {
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(now))
        val runtime = Runtime.getRuntime()
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val narration = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("narration", "none")
        val report = File(folder(context), "crash-$stamp-$kind.log")
        report.writeText(
            "Rever Android $version\n" +
                "timestamp_ms=$now\nandroid=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}\n" +
                "device=${Build.MANUFACTURER} ${Build.MODEL}\n" +
                "heap_used_bytes=${runtime.totalMemory() - runtime.freeMemory()}\n" +
                "heap_max_bytes=${runtime.maxMemory()}\n" +
                "last_narration=$narration\n\n$detail\n",
            Charsets.UTF_8
        )
        return report
    }
}
