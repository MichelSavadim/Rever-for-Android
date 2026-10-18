package com.rever.reader

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.util.BitSet

/** Reading history travels with the library in a Rever backup. */
class ReadingStats(context: Context) {
    private companion object { val fileLock = Any() }
    private val file = AtomicFile(File(context.filesDir, "reading_stats.json"))

    private fun read(): JSONObject = try {
        JSONObject(file.openRead().bufferedReader().use { it.readText() })
    } catch (_: Exception) { JSONObject() }

    private fun update(change: (JSONObject) -> Unit) = synchronized(fileLock) {
        val data = read()
        change(data)
        val stream = file.startWrite()
        try {
            stream.write(data.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    @Synchronized fun markOpened(id: String) = update { data ->
        val books = data.optJSONObject("books") ?: JSONObject().also { data.put("books", it) }
        val book = books.optJSONObject(id) ?: JSONObject().also { books.put(id, it) }
        book.put("started", true)
    }

    @Synchronized fun markPage(id: String, page: Int, total: Int) = update { data ->
        if (page !in 1..total) return@update
        val books = data.optJSONObject("books") ?: JSONObject().also { data.put("books", it) }
        val book = books.optJSONObject(id) ?: JSONObject().also { books.put(id, it) }
        val bits = try { BitSet.valueOf(Base64.decode(book.optString("pages"), Base64.DEFAULT)) }
            catch (_: Exception) { BitSet() }
        bits.set(page - 1)
        book.put("pages", Base64.encodeToString(bits.toByteArray(), Base64.NO_WRAP))
        book.put("started", true)
        if (bits.cardinality() >= total) book.put("completed", true)
    }

    @Synchronized fun markCompleted(id: String) = update { data ->
        val books = data.optJSONObject("books") ?: JSONObject().also { data.put("books", it) }
        val book = books.optJSONObject(id) ?: JSONObject().also { books.put(id, it) }
        book.put("completed", true)
        book.put("started", true)
    }

    @Synchronized fun addDuration(id: String, key: String, millis: Long) {
        if (millis <= 0 || key !in setOf("reading_ms", "narration_ms")) return
        update { data ->
            val books = data.optJSONObject("books") ?: JSONObject().also { data.put("books", it) }
            val book = books.optJSONObject(id) ?: JSONObject().also { books.put(id, it) }
            book.put(key, book.optLong(key) + millis)
        }
    }

    @Synchronized fun removeBook(id: String) = update { it.optJSONObject("books")?.remove(id) }

    fun snapshot(existingIds: Set<String>): Snapshot = synchronized(fileLock) {
        val data = read()
        val books = data.optJSONObject("books") ?: JSONObject()
        var started = 0
        var completed = 0
        var pages = 0
        var readingMs = 0L
        var narrationMs = 0L
        for (id in existingIds) {
            val book = books.optJSONObject(id) ?: continue
            if (book.optBoolean("started")) started++
            if (book.optBoolean("completed")) completed++
            readingMs += book.optLong("reading_ms")
            narrationMs += book.optLong("narration_ms")
            pages += try { BitSet.valueOf(Base64.decode(book.optString("pages"), Base64.DEFAULT)).cardinality() }
                catch (_: Exception) { 0 }
        }
        Snapshot(readingMs, narrationMs, started, completed, pages)
    }

    data class Snapshot(val readingMs: Long, val narrationMs: Long,
        val started: Int, val completed: Int, val pagesRead: Int)
}
