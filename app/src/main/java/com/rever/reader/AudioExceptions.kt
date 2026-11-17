package com.rever.reader

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Each affected paragraph is stored separately so the X button removes only that item. */
class AudioExceptions(private val folder: File) {
    private val file = File(folder, "audio_exceptions.json")
    val excluded = mutableSetOf<String>()
    val allowed = mutableSetOf<String>()
    var updatedAt = 0L
        private set

    init {
        try {
            val data = JSONObject(file.readText(Charsets.UTF_8))
            updatedAt = data.optLong("updated_at", file.lastModified())
            for ((name, target) in listOf("excluded" to excluded, "allowed" to allowed)) {
                val values = data.optJSONArray(name) ?: continue
                for (index in 0 until values.length()) target.add(values.getString(index))
            }
        } catch (_: Exception) { /* No exceptions saved yet. */ }
    }

    private fun key(paragraph: Paragraph): String = "${paragraph.page}:${paragraph.index}"

    fun contains(paragraph: Paragraph, automaticTexts: Set<String> = emptySet()): Boolean {
        val id = key(paragraph)
        return id in excluded || paragraph.text in automaticTexts && id !in allowed
    }

    private fun samePosition(a: Paragraph, b: Paragraph): Boolean {
        val ax = (a.box.left + a.box.right) / 2f
        val bx = (b.box.left + b.box.right) / 2f
        return !a.box.isEmpty && !b.box.isEmpty &&
            kotlin.math.abs(ax - bx) <= 18f && kotlin.math.abs(a.box.top - b.box.top) <= 9f
    }

    fun add(selected: Paragraph, paragraphs: List<Paragraph>, byText: Boolean,
            byPosition: Boolean): Int {
        val before = excluded.size
        for (item in paragraphs) {
            if (item.page == selected.page && item.index == selected.index ||
                byText && item.text.contains(selected.text) ||
                byPosition && samePosition(selected, item)) {
                excluded.add(key(item))
                allowed.remove(key(item))
            }
        }
        save()
        return excluded.size - before
    }

    fun remove(paragraph: Paragraph) {
        excluded.remove(key(paragraph))
        allowed.add(key(paragraph))
        save()
    }

    fun remap(old: List<Paragraph>, updated: List<Paragraph>) {
        val previous = old.associateBy(::key)
        fun mapped(values: Set<String>): Set<String> = values.mapNotNull { id ->
            val source = previous[id] ?: return@mapNotNull null
            val anchor = source.text.trim().replace(Regex("\\s+"), " ").lowercase()
            updated.firstOrNull { item -> item.page == source.page &&
                item.text.replace(Regex("\\s+"), " ").lowercase().contains(anchor) }
                ?.let(::key)
        }.toSet()
        val newExcluded = mapped(excluded)
        val newAllowed = mapped(allowed)
        excluded.clear(); excluded.addAll(newExcluded)
        allowed.clear(); allowed.addAll(newAllowed)
        save(touch = false)
    }

    fun snapshot(paragraphs: List<Paragraph>): JSONObject {
        val entries = JSONArray()
        for (item in paragraphs) {
            val id = key(item)
            if (id in excluded || id in allowed) entries.put(JSONObject()
                .put("page", item.page).put("text", item.text)
                .put("top", item.box.top.toDouble()).put("left", item.box.left.toDouble())
                .put("excluded", id in excluded))
        }
        return JSONObject().put("updated_at", updatedAt).put("entries", entries)
    }

    fun applySnapshot(snapshot: JSONObject, paragraphs: List<Paragraph>) {
        val incoming = snapshot.optJSONArray("entries") ?: return
        val nextExcluded = excluded.toMutableSet()
        val nextAllowed = allowed.toMutableSet()
        for (number in 0 until incoming.length()) {
            val item = incoming.optJSONObject(number) ?: continue
            val page = item.optInt("page")
            val anchor = item.optString("text").trim().replace(Regex("\\s+"), " ").lowercase()
            if (anchor.isEmpty()) continue
            val candidates = paragraphs.filter { paragraph ->
                if (paragraph.page != page) false else {
                    val text = paragraph.text.replace(Regex("\\s+"), " ").lowercase()
                    text.contains(anchor) || anchor.contains(text)
                }
            }
            val matched = candidates.minByOrNull { paragraph ->
                kotlin.math.abs(paragraph.box.top - item.optDouble("top", 0.0)) +
                    kotlin.math.abs(paragraph.box.left - item.optDouble("left", 0.0))
            } ?: continue
            val id = key(matched)
            if (item.optBoolean("excluded")) {
                nextExcluded.add(id)
                nextAllowed.remove(id)
            } else {
                nextAllowed.add(id)
                nextExcluded.remove(id)
            }
        }
        excluded.clear(); excluded.addAll(nextExcluded)
        allowed.clear(); allowed.addAll(nextAllowed)
        updatedAt = snapshot.optLong("updated_at")
        save(touch = false)
    }

    fun promoteForSync() = save()

    private fun save(touch: Boolean = true) {
        if (touch) updatedAt = maxOf(System.currentTimeMillis(), updatedAt + 1)
        val data = JSONObject()
            .put("excluded", JSONArray(excluded.sorted()))
            .put("allowed", JSONArray(allowed.sorted()))
            .put("updated_at", updatedAt)
        val temporary = File(folder, "audio_exceptions.json.tmp")
        try {
            temporary.writeText(data.toString(), Charsets.UTF_8)
            check(temporary.renameTo(file)) { "Não foi possível salvar as exceções de áudio." }
        } finally { temporary.delete() }
    }
}
