package com.rever.reader

import org.json.JSONArray
import java.io.File

object NarrationFiles {
    private fun voiceFolder(voice: String): String = voice.replace(Regex("[^A-Za-z0-9._-]"), "_")

    fun paragraphs(bookFolder: File): List<Paragraph> {
        val json = JSONArray(File(bookFolder, "paragraphs.json").readText(Charsets.UTF_8))
        return (0 until json.length()).map { Paragraph.fromJson(json.getJSONObject(it)) }
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
