package com.rever.reader

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.util.BitSet

/** Cumulative times are stored per device so repeated syncs never count them twice. */
class ReadingStats(context: Context) {
    private companion object { val fileLock = Any() }
    private val file = AtomicFile(File(context.filesDir, "reading_stats.json"))

    private fun read(): JSONObject = try {
        JSONObject(file.openRead().bufferedReader().use { it.readText() })
    } catch (_: Exception) { JSONObject() }

    private fun write(data: JSONObject) {
        val stream = file.startWrite()
        try {
            stream.write(data.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun update(change: (JSONObject) -> Unit) = synchronized(fileLock) {
        val data = read()
        change(data)
        write(data)
    }

    private fun book(data: JSONObject, id: String): JSONObject {
        val books = data.optJSONObject("books") ?: JSONObject().also { data.put("books", it) }
        return books.optJSONObject(id) ?: JSONObject().also { books.put(id, it) }
    }

    private fun bits(value: String): BitSet = try {
        BitSet.valueOf(Base64.decode(value, Base64.DEFAULT))
    } catch (_: Exception) { BitSet() }

    @Synchronized fun markOpened(id: String) = update { book(it, id).put("started", true) }

    @Synchronized fun markPage(id: String, page: Int, total: Int) = update { data ->
        if (page !in 1..total) return@update
        val entry = book(data, id)
        val pages = bits(entry.optString("pages"))
        pages.set(page - 1)
        entry.put("pages", Base64.encodeToString(pages.toByteArray(), Base64.NO_WRAP))
        entry.put("started", true)
        if (pages.cardinality() >= total) entry.put("completed", true)
    }

    @Synchronized fun markCompleted(id: String) = update {
        book(it, id).put("completed", true).put("started", true)
    }

    @Synchronized fun addDuration(id: String, key: String, millis: Long) {
        if (millis <= 0 || key !in setOf("reading_ms", "narration_ms")) return
        update { data ->
            val entry = book(data, id)
            val devices = entry.optJSONObject("devices") ?: JSONObject().also { entry.put("devices", it) }
            val legacy = entry.optLong(key)
            if (legacy > 0) {
                val old = devices.optJSONObject("android-legacy") ?: JSONObject().also {
                    devices.put("android-legacy", it)
                }
                old.put(key, maxOf(old.optLong(key), legacy))
                entry.remove(key)
            }
            val android = devices.optJSONObject("android") ?: JSONObject().also { devices.put("android", it) }
            android.put(key, android.optLong(key) + millis)
        }
    }

    @Synchronized fun removeBook(id: String) = update { it.optJSONObject("books")?.remove(id) }

    private fun mergeBook(target: JSONObject, incoming: JSONObject) {
        target.put("started", target.optBoolean("started") || incoming.optBoolean("started"))
        target.put("completed", target.optBoolean("completed") || incoming.optBoolean("completed"))
        val pages = bits(target.optString("pages"))
        pages.or(bits(incoming.optString("pages")))
        target.put("pages", Base64.encodeToString(pages.toByteArray(), Base64.NO_WRAP))
        val devices = target.optJSONObject("devices") ?: JSONObject().also { target.put("devices", it) }
        for (source in arrayOf(target, incoming)) {
            val legacy = devices.optJSONObject("android-legacy") ?: JSONObject().also {
                devices.put("android-legacy", it)
            }
            for (key in arrayOf("reading_ms", "narration_ms"))
                legacy.put(key, maxOf(legacy.optLong(key), source.optLong(key)))
            val from = source.optJSONObject("devices") ?: continue
            for (device in from.keys()) {
                if (device.length > 80) continue
                val values = from.optJSONObject(device) ?: continue
                val dest = devices.optJSONObject(device) ?: JSONObject().also { devices.put(device, it) }
                for (key in arrayOf("reading_ms", "narration_ms"))
                    dest.put(key, maxOf(dest.optLong(key), values.optLong(key)))
            }
        }
        target.remove("reading_ms")
        target.remove("narration_ms")
    }

    fun exchange(remote: JSONObject): JSONObject = synchronized(fileLock) {
        val data = read()
        val incoming = remote.optJSONObject("books") ?: JSONObject()
        val books = data.optJSONObject("books") ?: JSONObject().also { data.put("books", it) }
        for (id in incoming.keys()) {
            if (!id.matches(Regex("[0-9a-f]{20}"))) continue
            val source = incoming.optJSONObject(id) ?: continue
            mergeBook(book(data, id), source)
        }
        for (id in books.keys()) books.optJSONObject(id)?.let { mergeBook(it, JSONObject()) }
        write(data)
        data
    }

    fun snapshot(existingIds: Set<String>): Snapshot = synchronized(fileLock) {
        val books = read().optJSONObject("books") ?: JSONObject()
        var started = 0
        var completed = 0
        var pages = 0
        var readingMs = 0L
        var narrationMs = 0L
        for (id in existingIds) {
            val entry = books.optJSONObject(id) ?: continue
            if (entry.optBoolean("started")) started++
            if (entry.optBoolean("completed")) completed++
            pages += bits(entry.optString("pages")).cardinality()
            readingMs += entry.optLong("reading_ms")
            narrationMs += entry.optLong("narration_ms")
            val devices = entry.optJSONObject("devices") ?: continue
            for (device in devices.keys()) {
                val values = devices.optJSONObject(device) ?: continue
                readingMs += values.optLong("reading_ms")
                narrationMs += values.optLong("narration_ms")
            }
        }
        Snapshot(readingMs, narrationMs, started, completed, pages)
    }

    data class Snapshot(val readingMs: Long, val narrationMs: Long,
        val started: Int, val completed: Int, val pagesRead: Int)
}
