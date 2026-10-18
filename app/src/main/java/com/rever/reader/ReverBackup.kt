package com.rever.reader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ReverBackup(private val context: Context) {
    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
    private val restoreMarker = File(context.noBackupFilesDir, "restore-state")
    private val rollback = File(context.noBackupFilesDir, "restore-old")

    fun lastBackupAt(): Long = prefs.getLong("last_backup_at", 0L)
    fun frequencyHours(): Long = prefs.getLong("frequency_hours", 12L)

    fun setFrequency(hours: Long) {
        prefs.edit().putLong("frequency_hours", hours).apply()
        schedule(forceUpdate = true)
    }

    fun schedule(forceUpdate: Boolean = false) {
        val work = WorkManager.getInstance(context)
        val hours = frequencyHours()
        if (hours <= 0) work.cancelUniqueWork("rever-auto-backup")
        else work.enqueueUniquePeriodicWork("rever-auto-backup",
            if (forceUpdate) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AutoBackupWorker>(hours, TimeUnit.HOURS)
                .setInitialDelay(hours, TimeUnit.HOURS).build())
    }

    /** Includes the complete working library, temporary audio, PDF caches, and reading history. */
    fun writeTo(output: OutputStream): Unit = synchronized(ReverDataLock.lock) {
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(JSONObject().put("format", "rever-backup").put("schema", 1)
                .put("created_at", System.currentTimeMillis()).toString().toByteArray())
            zip.closeEntry()
            for ((prefix, root) in listOf("files" to context.filesDir, "cache" to context.cacheDir)) {
                root.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") &&
                    !it.name.endsWith(".part") && !it.name.endsWith(".incoming") }.forEach { file ->
                    val relative = file.relativeTo(root).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry("$prefix/$relative"))
                    file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        prefs.edit().putLong("last_backup_at", System.currentTimeMillis()).commit()
        Unit
    }

    fun writeAutomatic(): Uri {
        val name = "Rever-backup-${SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())}.zip"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Rever/Backups")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
            "Não foi possível criar o backup na pasta Downloads/Rever/Backups."
        }
        try {
            context.contentResolver.openOutputStream(uri, "w")!!.use(::writeTo)
            context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
            return uri
        } catch (error: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    }

    /** Validate/extract first; only then replace the existing working data. */
    fun restoreFrom(input: InputStream): Unit = synchronized(ReverDataLock.lock) {
        recoverIfNeeded()
        val staging = File(context.noBackupFilesDir, "restore-stage")
        staging.deleteRecursively()
        val stageFiles = File(staging, "files").apply { mkdirs() }
        val stageCache = File(staging, "cache").apply { mkdirs() }
        var validManifest = false
        var entries = 0
        var uncompressed = 0L
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    require(entries <= 100_000) { "Backup contém arquivos demais." }
                    val name = entry.name
                    require(!name.startsWith('/') && !name.contains('\\') &&
                        name.split('/').none { it == ".." || it == "." }) { "Caminho inválido no backup." }
                    if (name == "manifest.json") {
                        val bytes = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            require(bytes.size() + n <= 65_536) { "Manifesto do backup inválido." }
                            bytes.write(buffer, 0, n)
                        }
                        val manifest = JSONObject(bytes.toString(Charsets.UTF_8.name()))
                        require(manifest.optString("format") == "rever-backup" && manifest.optInt("schema") == 1) {
                            "Formato de backup incompatível."
                        }
                        validManifest = true
                    } else {
                        val root = when {
                            name.startsWith("files/") -> stageFiles
                            name.startsWith("cache/") -> stageCache
                            else -> throw IllegalArgumentException("Arquivo inesperado no backup.")
                        }
                        val relative = name.substringAfter('/')
                        if (relative.isNotEmpty()) {
                            val target = File(root, relative)
                            require(target.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
                                "Caminho inválido no backup."
                            }
                            if (entry.isDirectory) target.mkdirs() else {
                                target.parentFile?.mkdirs()
                                target.outputStream().buffered().use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        val n = zip.read(buffer)
                                        if (n < 0) break
                                        uncompressed += n
                                        require(uncompressed < 50L * 1024 * 1024 * 1024) { "Backup grande demais." }
                                        if (uncompressed % (16L * 1024 * 1024) < n)
                                            require(StatFs(context.filesDir.path).availableBytes > 64L * 1024 * 1024) {
                                                "Espaço insuficiente para restaurar o backup."
                                            }
                                        output.write(buffer, 0, n)
                                    }
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(validManifest) { "Formato de backup incompatível." }
            File(stageFiles, "library").mkdirs()
            rollback.deleteRecursively()
            rollback.mkdirs()
            val oldFiles = File(rollback, "files").apply { mkdirs() }
            val oldCache = File(rollback, "cache").apply { mkdirs() }
            try {
                restoreMarker.writeText("moving-old")
                moveChildren(context.filesDir, oldFiles)
                moveChildren(context.cacheDir, oldCache)
                restoreMarker.writeText("installing-new")
                moveChildren(stageFiles, context.filesDir)
                moveChildren(stageCache, context.cacheDir)
                restoreMarker.writeText("done")
                rollback.deleteRecursively()
                restoreMarker.delete()
            } catch (error: Exception) {
                recoverIfNeeded()
                throw error
            }
        } finally { staging.deleteRecursively() }
    }

    /** Repair a restore interrupted by process death before the replacement was committed. */
    fun recoverIfNeeded(): Unit = synchronized(ReverDataLock.lock) {
        if (!restoreMarker.isFile) return@synchronized
        val phase = restoreMarker.readText()
        if (phase == "installing-new") {
            context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
            context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        }
        if (phase != "done") {
            moveChildren(File(rollback, "files"), context.filesDir)
            moveChildren(File(rollback, "cache"), context.cacheDir)
        }
        rollback.deleteRecursively()
        restoreMarker.delete()
        Unit
    }

    private fun moveChildren(from: File, to: File) {
        from.listFiles()?.forEach { child ->
            check(child.renameTo(File(to, child.name))) { "Falha ao mover ${child.name}." }
        }
    }
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        LibraryLocation(applicationContext).sync()
        ReverBackup(applicationContext).writeAutomatic()
        Result.success()
    } catch (_: Exception) { Result.retry() }
}
