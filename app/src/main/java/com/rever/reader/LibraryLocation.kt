package com.rever.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File

internal object ReverDataLock { val lock = Any() }

/** A SAF folder is authoritative; private files are a working copy needed by PdfRenderer and TTS. */
class LibraryLocation(private val context: Context) {
    private val prefs = context.getSharedPreferences("library_location", Context.MODE_PRIVATE)
    private val localRoot = context.filesDir

    fun selectedUri(): Uri? = prefs.getString("tree_uri", null)?.let(Uri::parse)

    fun label(): String {
        val uri = selectedUri() ?: return localRoot.absolutePath
        val id = uri.lastPathSegment?.substringAfter(':') ?: ""
        return if (id.isBlank()) "Pasta selecionada/Rever/dados" else "$id/Rever/dados"
    }

    fun primaryDeviceBytes(): Long {
        val uri = selectedUri() ?: return 0L
        if (uri.authority != "com.android.externalstorage.documents" ||
            !uri.lastPathSegment.orEmpty().startsWith("primary:")) return 0L
        fun size(item: DocumentFile): Long = if (item.isFile) item.length().coerceAtLeast(0L)
            else item.listFiles().sumOf(::size)
        return dataDirectory()?.let(::size) ?: 0L
    }

    /** Migrate first, then make the selected tree the persistent source. */
    fun select(uri: Uri) = synchronized(ReverDataLock.lock) {
        context.contentResolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val root = requireNotNull(DocumentFile.fromTreeUri(context, uri)) { "Pasta inválida." }
        require(root.canWrite()) { "O Rever precisa de acesso de escrita nessa pasta." }
        val rever = root.findFile("Rever") ?: root.createDirectory("Rever")
        requireNotNull(rever) { "Não foi possível criar a pasta Rever." }
        val data = rever.findFile("dados") ?: rever.createDirectory("dados")
        requireNotNull(data) { "Não foi possível criar a pasta de dados." }
        val local = localFiles()
        val index = JSONObject()
        for ((path, file) in local) {
            val document = upload(data, path, file)
            index.put(path, indexEntry(file, document))
        }
        prefs.edit().putString("tree_uri", uri.toString()).putString("sync_index", index.toString()).commit()
    }

    fun sync() = synchronized(ReverDataLock.lock) {
        val data = dataDirectory() ?: return@synchronized
        val previous = try { JSONObject(prefs.getString("sync_index", "{}") ?: "{}") }
            catch (_: Exception) { JSONObject() }
        val local = localFiles()
        val remote = linkedMapOf<String, DocumentFile>()
        collectRemote(data, "", remote)
        val allPaths = (local.keys + remote.keys).toSortedSet()
        val next = JSONObject()
        for (path in allPaths) {
            val file = local[path]
            val document = remote[path]
            val old = previous.optJSONObject(path)
            when {
                file != null && document == null -> {
                    if (old != null) check(file.delete()) { "Falha ao remover $path da cópia de trabalho." } else {
                        next.put(path, indexEntry(file, upload(data, path, file)))
                    }
                }
                file == null && document != null -> {
                    if (old != null) check(document.delete()) { "Falha ao remover $path da pasta selecionada." } else {
                        download(document, path)
                        next.put(path, indexEntry(File(localRoot, path), document))
                    }
                }
                file != null && document != null -> {
                    val finalDocument = if (old == null || signature(file) != old.optString("local")) {
                        upload(data, path, file)
                    } else if (remoteSignature(document) != old.optString("remote")) {
                        download(document, path)
                        document
                    } else document
                    next.put(path, indexEntry(File(localRoot, path), finalDocument))
                }
            }
        }
        prefs.edit().putString("sync_index", next.toString()).commit()
    }

    /** Used after restoring a backup so removed books do not reappear on the next sync. */
    fun replaceRemote() = synchronized(ReverDataLock.lock) {
        val data = dataDirectory() ?: return@synchronized
        data.listFiles().forEach { check(it.delete()) { "Falha ao remover ${it.name} da pasta selecionada." } }
        val files = localFiles()
        val index = JSONObject()
        for ((path, file) in files) index.put(path, indexEntry(file, upload(data, path, file)))
        prefs.edit().putString("sync_index", index.toString()).commit()
    }

    private fun dataDirectory(): DocumentFile? {
        val uri = selectedUri() ?: return null
        val root = DocumentFile.fromTreeUri(context, uri) ?: return null
        return root.findFile("Rever")?.findFile("dados")
            ?: throw IllegalStateException("Pasta de armazenamento do Rever indisponível.")
    }

    private fun localFiles(): Map<String, File> = localRoot.walkTopDown()
        .filter { it.isFile }
        .associateBy { it.relativeTo(localRoot).invariantSeparatorsPath }

    private fun signature(file: File): String = "${file.length()}:${file.lastModified()}"
    private fun remoteSignature(file: DocumentFile): String = "${file.length()}:${file.lastModified()}"
    private fun indexEntry(file: File, document: DocumentFile): JSONObject = JSONObject()
        .put("local", signature(file)).put("remote", remoteSignature(document))

    private fun collectRemote(directory: DocumentFile, prefix: String, result: MutableMap<String, DocumentFile>) {
        for (item in directory.listFiles()) {
            val name = item.name ?: continue
            if (name == "." || name == ".." || '/' in name) continue
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            if (item.isDirectory) collectRemote(item, path, result)
            else if (item.isFile) result[path] = item
        }
    }

    private fun parentFor(root: DocumentFile, path: String): DocumentFile {
        var directory = root
        for (part in path.split('/').dropLast(1)) {
            directory = directory.findFile(part) ?: directory.createDirectory(part)
                ?: throw IllegalStateException("Não foi possível criar $part na pasta selecionada.")
        }
        return directory
    }

    private fun upload(root: DocumentFile, path: String, file: File): DocumentFile {
        val parent = parentFor(root, path)
        val name = path.substringAfterLast('/')
        val document = parent.findFile(name) ?: parent.createFile("application/octet-stream", name)
        requireNotNull(document) { "Não foi possível criar $name na pasta selecionada." }
        context.contentResolver.openOutputStream(document.uri, "wt")!!.use { output ->
            file.inputStream().buffered().use { it.copyTo(output) }
        }
        return document
    }

    private fun download(document: DocumentFile, path: String) {
        val target = File(localRoot, path)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".incoming")
        try {
            context.contentResolver.openInputStream(document.uri)!!.use { input ->
                temp.outputStream().buffered().use { input.copyTo(it) }
            }
            check(temp.renameTo(target)) { "Falha ao carregar $path da pasta selecionada." }
        } finally { temp.delete() }
    }
}
