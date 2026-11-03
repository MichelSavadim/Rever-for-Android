package com.rever.reader

import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** Exchanges only PDFs and reading positions; derived data stays on each device. */
class ReverSync(private val endpoint: String, private val token: String) {
    data class Result(val downloaded: Int, val uploaded: Int, val states: Int,
        val updatedLocally: Set<String>)

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

    private fun json(path: String): JSONObject {
        val request = connection(path)
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

    private fun sendPdf(id: String, pdf: File, title: String) {
        val request = connection("/books/$id/pdf", "PUT")
        request.doOutput = true
        request.setFixedLengthStreamingMode(pdf.length())
        request.setRequestProperty("Content-Type", "application/pdf")
        request.setRequestProperty("X-Rever-Title", URLEncoder.encode(title, "UTF-8"))
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

    fun run(library: File, importPdf: (Uri, String) -> Unit): Result {
        val manifest = json("/manifest")
        val remote = mutableMapOf<String, JSONObject>()
        val books = manifest.getJSONArray("books")
        for (i in 0 until books.length()) {
            val item = books.getJSONObject(i)
            remote[item.getString("id")] = item
        }
        var downloaded = 0
        var uploaded = 0
        var states = 0
        val updatedLocally = mutableSetOf<String>()
        for (item in remote.values) {
            val id = item.getString("id")
            if (!id.matches(Regex("[0-9a-f]{20}"))) continue
            val folder = File(library, id)
            if (!File(folder, "document.pdf").isFile) {
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
                    downloaded++
                } finally { request.disconnect(); temp.delete() }
            }
        }
        for (folder in library.listFiles().orEmpty()) {
            if (!folder.isDirectory || !folder.name.matches(Regex("[0-9a-f]{20}"))) continue
            val pdf = File(folder, "document.pdf")
            if (!pdf.isFile) continue
            val id = folder.name
            if (!remote.containsKey(id)) {
                val title = try { JSONObject(File(folder, "book.json").readText()).optString("title", "PDF") }
                    catch (_: Exception) { "PDF" }
                sendPdf(id, pdf, title)
                uploaded++
            }
            val stateFile = File(folder, "reader_state.json")
            val remoteState = remote[id]?.optJSONObject("state")
                ?: if (uploaded > 0) json("/books/$id/state") else JSONObject()
            val remoteTime = remoteState.optLong("updated_at", 0L)
            val localTime = timestamp(stateFile)
            if (localTime > remoteTime && stateFile.isFile) {
                val local = JSONObject(stateFile.readText())
                local.put("updated_at", localTime)
                val accepted = send("/books/$id/state", local.toString().toByteArray(Charsets.UTF_8))
                if (accepted.optLong("updated_at") > localTime) {
                    saveState(stateFile, accepted)
                    updatedLocally.add(id)
                }
                states++
            } else if (remoteTime > localTime) {
                saveState(stateFile, remoteState)
                updatedLocally.add(id)
                states++
            }
        }
        return Result(downloaded, uploaded, states, updatedLocally)
    }
}
