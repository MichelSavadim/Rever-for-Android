package com.rever.reader

import android.graphics.RectF
import android.util.JsonReader
import java.io.File

data class PdfSearchResult(val pages: List<Int>, val boxes: Map<Int, List<RectF>>)

/** Reads glyphs one paragraph at a time so a large book never occupies the heap twice. */
object PdfSearch {
    fun find(file: File, query: String, mayContinue: () -> Boolean): PdfSearchResult {
        val term = query.trim().replace(Regex("\\s+"), " ")
        if (term.isEmpty() || !file.isFile) return PdfSearchResult(emptyList(), emptyMap())
        val pages = mutableListOf<Int>()
        val boxes = mutableMapOf<Int, MutableList<RectF>>()
        JsonReader(file.reader(Charsets.UTF_8).buffered()).use { reader ->
            reader.beginArray()
            while (reader.hasNext()) {
                if (!mayContinue()) return PdfSearchResult(emptyList(), emptyMap())
                var page = 0
                var text = ""
                var rawText = ""
                val glyphs = mutableListOf<GlyphBox>()
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "page" -> page = reader.nextInt()
                        "text" -> text = reader.nextString()
                        "raw_text" -> rawText = reader.nextString()
                        "glyphs" -> {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                reader.beginArray()
                                val start = reader.nextInt()
                                val end = reader.nextInt()
                                val box = RectF(reader.nextDouble().toFloat(), reader.nextDouble().toFloat(),
                                    reader.nextDouble().toFloat(), reader.nextDouble().toFloat())
                                reader.endArray()
                                glyphs.add(GlyphBox(start, end, box))
                            }
                            reader.endArray()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                if (glyphs.isNotEmpty() && text.contains(term, ignoreCase = true)) {
                    val matches = Paragraph(page, 0, text, RectF(), rawText.ifEmpty { text }, glyphs)
                        .matchBoxes(term)
                    if (matches.isNotEmpty()) {
                        pages.add(page)
                        boxes.getOrPut(page) { mutableListOf() }.addAll(matches)
                    }
                }
            }
            reader.endArray()
        }
        return PdfSearchResult(pages, boxes)
    }
}
