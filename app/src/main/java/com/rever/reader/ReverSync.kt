package com.rever.reader

import android.net.Uri
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** Exchanges the data types enabled on both devices. */
class ReverSync(private val endpoint: String, private val token: String) {
    data class Options(val pdfs: Boolean, val states: Boolean, val stats: Boolean,
        val exceptions: Boolean, val pronunciation: Boolean = true)
    data class Result(val downloaded: Int, val uploaded: Int, val states: Int,
        val updatedLocally: Set<String>, val deletedLocally: Set<String>)

    companion object {
        private const val DELETED_FILE = "sync_deleted.json"

        fun recordDeletion(library: File, id: String) {
            val file = File(library, DELETED_FILE)
            val values = try { JSONObject(file.readText()) } catch (_: Exception) { JSONObject() }
            values.put(id, maxOf(System.currentTimeMillis(), values.optLong(id) + 1))
            file.writeText(values.toString(), Charsets.UTF_8)
        }

        fun clearDeletion(library: File, id: String) {
            val file = File(library, DELETED_FILE)
            val values = try { JSONObject(file.readText()) } catch (_: Exception) { return }
            if (values.has(id)) {
                values.remove(id)
                file.writeText(values.toString(), Charsets.UTF_8)
            }
        }
    }

    init {
        require(endpoint.startsWith("https://") && endpoint.removePrefix("https://").isNotBlank()) {
            "Use o endereço HTTPS do Tailscale Serve."
        }
        require(token.isNotBlank()) { "Informe o token exibido no PC." }
    }

    private fun connection(path: String, method: String = "GET"): HttpURLConnection =
        (URL(endpoint.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("X-Rever-Token", token)
            connectTimeout = 10_000
            readTimeout = 60_000
            useCaches = false
        }

    private fun json(path: String, playingBookId: String? = null): JSONObject {
        val request = connection(path)
        if (playingBookId != null) request.setRequestProperty("X-Rever-Playing-Book", playingBookId)
        try {
            require(request.responseCode == 200) { "Servidor: HTTP ${request.responseCode}" }
            return JSONObject(request.inputStream.bufferedReader().use { it.readText() })
        } finally { request.disconnect() }
    }

    private fun send(path: String, bytes: ByteArray, title: String? = null): JSONObject {
        val request = connection(path, "PUT")
        request.doOutput = true
        request.setFixedLengthStreamingMode(bytes.size)
        if (title == null) request.setRequestProperty("Content-Type", "application/json")
        else {
            request.setRequestProperty("Content-Type", "application/pdf")
            request.setRequestProperty("X-Rever-Title", URLEncoder.encode(title, "UTF-8"))
        }
        try {
            request.outputStream.use { it.write(bytes) }
            val response = request.responseCode
            val stream = if (response == 200) request.inputStream else request.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            require(response == 200) { "Servidor: HTTP $response — $body" }
            return JSONObject(body)
        } finally { request.disconnect() }
    }

    private fun sendPdf(id: String, pdf: File, title: String, addedAt: Long) {
        val request = connection("/books/$id/pdf", "PUT")
        request.doOutput = true
        request.setFixedLengthStreamingMode(pdf.length())
        request.setRequestProperty("Content-Type", "application/pdf")
        request.setRequestProperty("X-Rever-Title", URLEncoder.encode(title, "UTF-8"))
        request.setRequestProperty("X-Rever-Added-At", addedAt.toString())
        try {
            request.outputStream.use { output -> pdf.inputStream().use { it.copyTo(output) } }
            val code = request.responseCode
            require(code == 200) { "Falha ao enviar PDF: HTTP $code" }
        } finally { request.disconnect() }
    }

    private fun timestamp(file: File): Long {
        if (!file.isFile) return 0L
        return try { JSONObject(file.readText()).optLong("updated_at", file.lastModified()) }
            catch (_: Exception) { 0L }
    }

    private fun saveState(file: File, data: JSONObject) {
        val temp = File(file.parentFile, file.name + ".sync-tmp")
        try {
            temp.writeText(data.toString(), Charsets.UTF_8)
            check(temp.renameTo(file)) { "Não foi possível salvar o ponto de leitura." }
        } finally { temp.delete() }
    }

    fun run(library: File, importPdf: (Uri, String) -> Unit,
            publish: Boolean = false, playingBookId: String? = null,
            options: Options = Options(true, true, true, true), statistics: ReadingStats? = null): Result {
        val manifest = json("/manifest", playingBookId)
        val features = manifest.optJSONObject("features")
        val pdfSync = options.pdfs && (features?.optBoolean("pdfs", true) ?: true)
        val stateSync = options.states && (features?.optBoolean("states", true) ?: true)
        val statsSync = options.stats && features?.optBoolean("stats", false) == true
        val exceptionSync = options.exceptions && features?.optBoolean("exceptions", false) == true
        val pcPlayingBookId = manifest.optString("playing_book_id")
        val remote = mutableMapOf<String, JSONObject>()
        val books = manifest.getJSONArray("books")
        for (i in 0 until books.length()) {
            val item = books.getJSONObject(i)
            remote[item.getString("id")] = item
        }
        val deletedFile = File(library, DELETED_FILE)
        val localDeleted = try { JSONObject(deletedFile.readText()) } catch (_: Exception) { JSONObject() }
        val remoteDeleted = manifest.optJSONObject("deleted") ?: JSONObject()
        val hidden = manifest.optJSONArray("hidden")
        val hiddenIds = (0 until (hidden?.length() ?: 0)).map { hidden!!.getString(it) }.toSet()
        val deletedLocally = mutableSetOf<String>()
        val deferredDeletions = mutableSetOf<String>()
        if (pdfSync && publish) for (id in localDeleted.keys().asSequence().toList()) {
            if (!id.matches(Regex("[0-9a-f]{20}"))) continue
            val deletedAt = localDeleted.optLong(id)
            if (remote[id]?.optLong("added_at", 0L) ?: 0L > deletedAt) {
                localDeleted.remove(id)
                continue
            }
            if (deletedAt > remoteDeleted.optLong(id)) {
                send("/books/$id/delete", JSONObject().put("updated_at", deletedAt)
                    .toString().toByteArray(Charsets.UTF_8))
                remote.remove(id)
            }
        }
        if (pdfSync) for (id in remoteDeleted.keys()) {
            if (!id.matches(Regex("[0-9a-f]{20}"))) continue
            val deletedAt = remoteDeleted.optLong(id)
            val folder = File(library, id)
            val addedAt = try { JSONObject(File(folder, "book.json").readText()).optLong("added_at") }
                catch (_: Exception) { 0L }
            if (folder.isDirectory && addedAt <= deletedAt) {
                if (playingBookId == id) deferredDeletions.add(id)
                else {
                    check(folder.deleteRecursively()) { "Não foi possível apagar o PDF removido no PC." }
                    deletedLocally.add(id)
                }
            }
            if (addedAt <= deletedAt && deletedAt > localDeleted.optLong(id))
                localDeleted.put(id, deletedAt)
            remote.remove(id)
        }
        if (pdfSync && localDeleted.length() > 0) deletedFile.writeText(localDeleted.toString(), Charsets.UTF_8)
        var downloaded = 0
        var uploaded = 0
        var states = 0
        val updatedLocally = mutableSetOf<String>()
        for (item in remote.values) {
            val id = item.getString("id")
            if (!id.matches(Regex("[0-9a-f]{20}"))) continue
            val folder = File(library, id)
            if (localDeleted.optLong(id) >= item.optLong("added_at") && !folder.isDirectory)
                continue
            if (pdfSync && !File(folder, "document.pdf").isFile) {
                val request = connection("/books/$id/pdf")
                val temp = File.createTempFile("rever-sync-", ".pdf", library.parentFile)
                try {
                    require(request.responseCode == 200) { "Falha ao baixar PDF: HTTP ${request.responseCode}" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    request.inputStream.use { input -> temp.outputStream().use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    } }
                    check(digest.digest().joinToString("") { "%02x".format(it) }.take(20) == id) {
                        "O SHA-256 do PDF recebido não confere."
                    }
                    importPdf(Uri.fromFile(temp), item.optString("title", "PDF") + ".pdf")
                    clearDeletion(library, id)
                    downloaded++
                } finally { request.disconnect(); temp.delete() }
            }
        }
        for (folder in library.listFiles().orEmpty()) {
            if (!folder.isDirectory || !folder.name.matches(Regex("[0-9a-f]{20}"))) continue
            val pdf = File(folder, "document.pdf")
            if (!pdf.isFile) continue
            val id = folder.name
            if (id in deferredDeletions) continue
            if (id in hiddenIds) continue
            if (pdfSync && publish && !remote.containsKey(id)) {
                val title = try { JSONObject(File(folder, "book.json").readText()).optString("title", "PDF") }
                    catch (_: Exception) { "PDF" }
                val addedAt = try { JSONObject(File(folder, "book.json").readText()).optLong("added_at") }
                    catch (_: Exception) { 0L }
                sendPdf(id, pdf, title, addedAt)
                remote[id] = JSONObject().put("id", id)
                uploaded++
            }
            if (exceptionSync && remote.containsKey(id)) {
                val exceptions = AudioExceptions(folder)
                val localTime = exceptions.updatedAt
                val remoteTime = remote[id]?.optLong("exceptions_updated_at", 0L) ?: 0L
                if (remoteTime != localTime) {
                    val index = File(folder, "paragraphs_index.json")
                    val source = if (index.isFile) index else File(folder, "paragraphs.json")
                    if (source.isFile) {
                        val data = JSONArray(source.readText(Charsets.UTF_8))
                        val paragraphs = (0 until data.length()).map { Paragraph.fromJson(data.getJSONObject(it)) }
                        if (remoteTime > localTime) {
                            exceptions.applySnapshot(json("/books/$id/exceptions"), paragraphs)
                            exceptions.promoteForSync()
                            updatedLocally.add(id)
                        }
                        val accepted = send("/books/$id/exceptions", exceptions.snapshot(paragraphs)
                            .toString().toByteArray(Charsets.UTF_8))
                        if (accepted.optLong("updated_at") > exceptions.updatedAt) {
                            exceptions.applySnapshot(accepted, paragraphs)
                            updatedLocally.add(id)
                        }
                    }
                }
            }
            if (!stateSync || !remote.containsKey(id)) continue
            val stateFile = File(folder, "reader_state.json")
            val remoteState = remote[id]?.optJSONObject("state")
                ?: if (uploaded > 0) json("/books/$id/state") else JSONObject()
            val remoteTime = remoteState.optLong("updated_at", 0L)
            val localTime = timestamp(stateFile)
            val pcPlaying = pcPlayingBookId == id
            val androidPlaying = playingBookId == id
            if (publish && (localTime > remoteTime || androidPlaying && !pcPlaying) &&
                stateFile.isFile &&
                (!pcPlaying || androidPlaying)) {
                val local = JSONObject(stateFile.readText())
                local.put("updated_at", localTime)
                val request = connection("/books/$id/state", "PUT")
                request.setRequestProperty("X-Rever-Playing", if (androidPlaying) "1" else "0")
                request.doOutput = true
                val bytes = local.toString().toByteArray(Charsets.UTF_8)
                request.setFixedLengthStreamingMode(bytes.size)
                request.setRequestProperty("Content-Type", "application/json")
                val accepted = try {
                    request.outputStream.use { it.write(bytes) }
                    check(request.responseCode == 200) { "Servidor: HTTP ${request.responseCode}" }
                    JSONObject(request.inputStream.bufferedReader().use { it.readText() })
                } finally { request.disconnect() }
                if (accepted.optLong("updated_at") > localTime) {
                    saveState(stateFile, accepted)
                    updatedLocally.add(id)
                }
                states++
            } else if (remoteTime > localTime || pcPlaying && !androidPlaying && remoteTime > 0) {
                saveState(stateFile, remoteState)
                updatedLocally.add(id)
                states++
            }
        }
        if (statsSync && statistics != null) {
            val merged = statistics.exchange(json("/stats"))
            statistics.exchange(send("/stats", merged.toString().toByteArray(Charsets.UTF_8)))
        }
        if (options.pronunciation && features?.optBoolean("pronunciation", false) == true) {
            val store = Pronunciations(library)
            val merged = store.merge(json("/pronunciation"))
            store.merge(send("/pronunciation", merged.toString().toByteArray(Charsets.UTF_8)))
        }
        return Result(downloaded, uploaded, states, updatedLocally, deletedLocally)
    }
}
