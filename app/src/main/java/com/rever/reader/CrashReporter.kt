package com.rever.reader

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class ReverApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}

object CrashReporter {
    private const val PREFS = "crash_reports"
    private const val TAG = "ReverCrash"
    // Keep a little heap available to write an OutOfMemoryError report.
    private var emergencyMemory: ByteArray? = ByteArray(64 * 1024)

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

    fun markScreen(context: Context, screen: String, bookId: String? = null, page: Int = 0) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("screen", "name=$screen book=${bookId ?: "none"} page=$page at_ms=${System.currentTimeMillis()}")
            .commit()
    }

    fun markSearch(context: Context, bookId: String, page: Int, queryLength: Int,
                   indexBytes: Long, stage: String, results: Int = -1) {
        // Store only the query length, never the words searched or book title.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("search", "book=$bookId page=$page query_chars=$queryLength " +
                "index_bytes=$indexBytes stage=$stage results=$results at_ms=${System.currentTimeMillis()}")
            .commit()
    }

    fun recordHandled(context: Context, operation: String, error: Throwable) {
        try { write(context, "handled", "operation=$operation", error) }
        catch (failure: Throwable) { Log.e(TAG, "Falha ao salvar erro tratado", failure) }
    }

    fun install(application: Application) {
        try { collectPreviousExits(application) }
        catch (error: Exception) { Log.e(TAG, "Falha ao consultar encerramentos anteriores", error) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                emergencyMemory = null
                write(application, "java", "thread=${thread.name} id=${thread.id} " +
                    "state=${thread.state}\nexception=${error.javaClass.name}: ${error.message.orEmpty()}", error)
            } catch (failure: Throwable) {
                Log.e(TAG, "Falha ao salvar relatório", failure)
                try {
                    File(folder(application), "crash-last-resort.log").appendText(
                        "pid=${Process.myPid()} thread=${thread.name} error=${error.javaClass.name} " +
                            "message=${error.message.orEmpty()}\n", Charsets.UTF_8)
                } catch (_: Throwable) { /* The default handler still receives the crash. */ }
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
            val javaReport = folder(context).listFiles()
                ?.filter { it.isFile && it.name.endsWith("-java.log") &&
                    abs(it.lastModified() - exit.timestamp) < 60_000 }
                ?.firstOrNull { report ->
                    try { report.bufferedReader().use { reader ->
                        reader.lineSequence().take(10).any { it == "pid=${exit.pid}" }
                    } }
                    catch (_: Exception) { false }
                }
            val detail = "exit_reason=${reasonName(exit.reason)} (${exit.reason})\n" +
                "exit_timestamp_ms=${exit.timestamp}\nexit_process=${exit.processName}\n" +
                "exit_pid=${exit.pid}\nexit_status=${exit.status}\n" +
                "exit_importance=${exit.importance}\nexit_description=${exit.description.orEmpty()}\n" +
                "exit_pss_kb=${exit.pss}\nexit_rss_kb=${exit.rss}\n" +
                "java_report=${javaReport?.name ?: "unavailable"}\n"
            val report = write(context, "exit-${exit.timestamp}-${exit.pid}", detail)
            if (javaReport != null) {
                report.appendText("\n--- Captured Java exception ---\n" +
                    javaReport.readText(Charsets.UTF_8).take(256_000), Charsets.UTF_8)
            }
            try {
                val traceStream = exit.traceInputStream
                traceStream?.use { input ->
                    val extension = if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) "pb" else "trace"
                    val trace = File(folder(context), "${report.nameWithoutExtension}.$extension")
                    trace.outputStream().use { output ->
                        input.copyTo(output)
                    }
                    report.appendText("\nsystem_trace_file=${trace.name} bytes=${trace.length()}\n", Charsets.UTF_8)
                }
                if (traceStream == null) {
                    report.appendText("\nsystem_trace=unavailable_on_this_device\n", Charsets.UTF_8)
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

    private fun write(context: Context, kind: String, detail: String, error: Throwable? = null): File {
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(now))
        val runtime = Runtime.getRuntime()
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val report = File(folder(context), "crash-$stamp-$kind.log")
        report.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("Rever Android ${packageInfo.versionName} build=${packageInfo.longVersionCode}")
            writer.appendLine("report_timestamp_ms=$now")
            writer.appendLine("report_process_pid=${Process.myPid()} (memory below belongs to this process)")
            writer.appendLine("pid=${Process.myPid()}")
            writer.appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
            writer.appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} abi=${Build.SUPPORTED_ABIS.firstOrNull()}")
            writer.appendLine("heap_used_bytes=${runtime.totalMemory() - runtime.freeMemory()}")
            writer.appendLine("heap_free_bytes=${runtime.freeMemory()}")
            writer.appendLine("heap_max_bytes=${runtime.maxMemory()}")
            writer.appendLine("native_heap_used_bytes=${Debug.getNativeHeapAllocatedSize()}")
            writer.appendLine("files_free_bytes=${context.filesDir.usableSpace}")
            writer.appendLine("last_screen=${prefs.getString("screen", "none")}")
            writer.appendLine("last_search=${prefs.getString("search", "none")}")
            writer.appendLine("last_narration=${prefs.getString("narration", "none")}")
            writer.appendLine()
            writer.appendLine(detail)
            if (error != null) {
                writer.appendLine("--- Java stack trace (including causes and suppressed exceptions) ---")
                val printer = PrintWriter(writer)
                error.printStackTrace(printer)
                printer.flush()
                if (printer.checkError()) throw java.io.IOException("Falha ao escrever a pilha Java")
            }
        }
        return report
    }
}
