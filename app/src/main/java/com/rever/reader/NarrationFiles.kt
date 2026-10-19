package com.rever.reader

import android.graphics.RectF
import android.util.JsonReader
import java.io.File

object NarrationFiles {
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

    fun temporary(bookFolder: File, voice: String, paragraph: Paragraph): File =
        File(bookFolder, "temp_audio/${voiceFolder(voice)}/v${ParagraphExtractor.VERSION}/p%04d-%04d.mp3".format(paragraph.page, paragraph.index))

    fun permanent(bookFolder: File, voice: String, paragraph: Paragraph): File =
        File(bookFolder, "audio/${voiceFolder(voice)}/v${ParagraphExtractor.VERSION}/p%04d-%04d.mp3".format(paragraph.page, paragraph.index))

    fun playable(bookFolder: File, voice: String, paragraph: Paragraph): File? =
        listOf(permanent(bookFolder, voice, paragraph), temporary(bookFolder, voice, paragraph))
            .firstOrNull { it.isFile && it.length() > 0 }

    fun hasSpeech(text: String): Boolean = Regex("[A-Za-zÀ-ÖØ-öø-ÿ0-9]").containsMatchIn(text)
}
