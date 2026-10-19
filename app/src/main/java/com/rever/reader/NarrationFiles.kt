package com.rever.reader

import android.graphics.RectF
import android.util.JsonReader
import org.json.JSONArray
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object NarrationFiles {
    private const val REPEAT_LIMIT = 15
    private const val IGNORED_FILE = "ignored_paragraphs.json"
    private fun voiceFolder(voice: String): String = voice.replace(Regex("[^A-Za-z0-9._-]"), "_")

    fun paragraphs(bookFolder: File): List<Paragraph> {
        val index = File(bookFolder, "paragraphs_index.json")
        val source = if (index.isFile) index else File(bookFolder, "paragraphs.json")
        val result = ArrayList<Paragraph>()
        JsonReader(source.reader(Charsets.UTF_8)).use { reader ->
            reader.beginArray()
            while (reader.hasNext()) {
                var page = 0
                var number = 0
                var text = ""
                var box = RectF()
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "page" -> page = reader.nextInt()
                        "index" -> number = reader.nextInt()
                        "text" -> text = reader.nextString()
                        "bbox" -> {
                            reader.beginArray()
                            box = RectF(reader.nextDouble().toFloat(), reader.nextDouble().toFloat(),
                                reader.nextDouble().toFloat(), reader.nextDouble().toFloat())
                            reader.endArray()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                result.add(Paragraph(page, number, text, box))
            }
            reader.endArray()
        }
        return result
    }

    /** Exact text matches only: a paragraph is skipped automatically after 15 occurrences. */
    fun repeatedTexts(paragraphs: List<Paragraph>): Set<String> {
        val counts = HashMap<String, Int>()
        paragraphs.forEach { paragraph ->
            if (hasSpeech(paragraph.text)) counts[paragraph.text] = (counts[paragraph.text] ?: 0) + 1
        }
        return counts.filterValues { it > REPEAT_LIMIT }.keys
    }

    fun rebuildIgnored(bookFolder: File, paragraphs: List<Paragraph>): Set<String> {
        val ignored = repeatedTexts(paragraphs)
        val file = File(bookFolder, IGNORED_FILE)
        val partial = File(bookFolder, "$IGNORED_FILE.tmp")
        try {
            partial.writeText(JSONArray(ignored.sorted()).toString(), Charsets.UTF_8)
            Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { partial.delete() }
        return ignored
    }

    fun ignored(bookFolder: File, paragraphs: List<Paragraph>): Set<String> {
        val file = File(bookFolder, IGNORED_FILE)
        if (file.isFile) try {
            val data = JSONArray(file.readText(Charsets.UTF_8))
            return (0 until data.length()).map { data.getString(it) }.toSet()
        } catch (_: Exception) { /* Rebuild a damaged list. */ }
        return rebuildIgnored(bookFolder, paragraphs)
    }

    fun automaticSpeech(paragraph: Paragraph, ignored: Set<String>): Boolean =
        hasSpeech(paragraph.text) && paragraph.text !in ignored

    fun temporary(bookFolder: File, voice: String, paragraph: Paragraph): File =
        File(bookFolder, "temp_audio/${voiceFolder(voice)}/v${ParagraphExtractor.VERSION}/p%04d-%04d.mp3".format(paragraph.page, paragraph.index))

    fun permanent(bookFolder: File, voice: String, paragraph: Paragraph): File =
        File(bookFolder, "audio/${voiceFolder(voice)}/v${ParagraphExtractor.VERSION}/p%04d-%04d.mp3".format(paragraph.page, paragraph.index))

    fun playable(bookFolder: File, voice: String, paragraph: Paragraph): File? =
        listOf(permanent(bookFolder, voice, paragraph), temporary(bookFolder, voice, paragraph))
            .firstOrNull { it.isFile && it.length() > 0 }

    fun hasSpeech(text: String): Boolean = Regex("[A-Za-zÀ-ÖØ-öø-ÿ0-9]").containsMatchIn(text)
}
