package com.rever.reader

import android.content.Context
import android.graphics.RectF
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class GlyphBox(val start: Int, val end: Int, val box: RectF) {
    fun toJson(): JSONArray = JSONArray().put(start).put(end)
        .put(box.left).put(box.top).put(box.right).put(box.bottom)

    companion object {
        fun fromJson(data: JSONArray) = GlyphBox(data.getInt(0), data.getInt(1),
            RectF(data.getDouble(2).toFloat(), data.getDouble(3).toFloat(),
                data.getDouble(4).toFloat(), data.getDouble(5).toFloat()))
    }
}

data class Paragraph(
    val page: Int, val index: Int, val text: String, val box: RectF,
    val rawText: String = text, val glyphs: List<GlyphBox> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("page", page)
        .put("index", index)
        .put("text", text)
        .put("bbox", JSONArray().put(box.left).put(box.top).put(box.right).put(box.bottom))
        .put("raw_text", rawText)
        .put("glyphs", JSONArray().apply { glyphs.forEach { put(it.toJson()) } })

    fun matchBoxes(query: String): List<RectF> {
        val term = query.trim().replace(Regex("\\s+"), " ")
        if (term.isEmpty() || glyphs.isEmpty()) return emptyList()
        val normalized = StringBuilder()
        val sourceOffsets = mutableListOf<Int>()
        for ((offset, char) in rawText.withIndex()) {
            if (char.isWhitespace()) {
                if (normalized.isNotEmpty() && normalized.last() != ' ') {
                    normalized.append(' ')
                    sourceOffsets.add(offset)
                }
            } else {
                normalized.append(char)
                sourceOffsets.add(offset)
            }
        }
        val boxes = mutableListOf<RectF>()
        var from = 0
        while (from < normalized.length) {
            val found = normalized.indexOf(term, from, ignoreCase = true)
            if (found < 0) break
            val start = sourceOffsets[found]
            val end = sourceOffsets[found + term.length - 1] + 1
            val matching = glyphs.filter { it.start < end && it.end > start }
                .sortedWith(compareBy<GlyphBox> { it.box.top }.thenBy { it.box.left })
            val occurrenceBoxes = mutableListOf<RectF>()
            for (glyph in matching) {
                val last = occurrenceBoxes.lastOrNull()
                if (last != null && kotlin.math.abs(last.top - glyph.box.top) <
                    maxOf(last.height(), glyph.box.height()) * .45f) last.union(glyph.box)
                else occurrenceBoxes.add(RectF(glyph.box))
            }
            boxes.addAll(occurrenceBoxes)
            from = found + term.length
        }
        return boxes
    }

    companion object {
        fun fromJson(data: JSONObject): Paragraph {
            val bbox = data.getJSONArray("bbox")
            val dataGlyphs = data.optJSONArray("glyphs")
            return Paragraph(data.getInt("page"), data.getInt("index"), data.getString("text"),
                RectF(bbox.getDouble(0).toFloat(), bbox.getDouble(1).toFloat(),
                    bbox.getDouble(2).toFloat(), bbox.getDouble(3).toFloat()),
                data.optString("raw_text", data.getString("text")),
                if (dataGlyphs == null) emptyList() else (0 until dataGlyphs.length())
                    .map { GlyphBox.fromJson(dataGlyphs.getJSONArray(it)) })
        }
    }
}

/** Uses PDFBox text positions and breaks a paragraph when a line ends in a full stop. */
object ParagraphExtractor {
    const val VERSION = 4

    fun extractToFiles(context: Context, pdfFile: File, folder: File): List<Paragraph> {
        PDFBoxResourceLoader.init(context.applicationContext)
        folder.mkdirs()
        val full = File(folder, "paragraphs.json")
        val index = File(folder, "paragraphs_index.json")
        val fullTemp = File(folder, "paragraphs.json.tmp")
        val indexTemp = File(folder, "paragraphs_index.json.tmp")
        val compact = mutableListOf<Paragraph>()
        try {
            fullTemp.bufferedWriter(Charsets.UTF_8).use { fullWriter ->
                indexTemp.bufferedWriter(Charsets.UTF_8).use { indexWriter ->
                    fullWriter.write("[")
                    indexWriter.write("[")
                    var first = true
                    val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)
                    PDDocument.load(pdfFile, memory).use { document ->
                        require(!document.isEncrypted) { "PDFs protegidos por senha ainda não são compatíveis." }
                        for (pageNumber in 1..document.numberOfPages) {
                            val stripper = PositionedStripper(pageNumber)
                            stripper.sortByPosition = true
                            stripper.startPage = pageNumber
                            stripper.endPage = pageNumber
                            stripper.getText(document)
                            for (paragraph in mergeBlocks(stripper.finish())) {
                                if (!first) {
                                    fullWriter.write(",")
                                    indexWriter.write(",")
                                }
                                first = false
                                fullWriter.write(paragraph.toJson().toString())
                                val light = Paragraph(paragraph.page, paragraph.index,
                                    paragraph.text, paragraph.box)
                                compact.add(light)
                                indexWriter.write(light.toJson().toString())
                            }
                        }
                    }
                    fullWriter.write("]")
                    indexWriter.write("]")
                }
            }
            require(compact.isNotEmpty()) { "O PDF não contém texto extraível. OCR será adicionado no futuro." }
            check(fullTemp.renameTo(full) && indexTemp.renameTo(index)) {
                "Não foi possível salvar os parágrafos."
            }
            NarrationFiles.rebuildIgnored(folder, compact)
            return compact
        } finally {
            fullTemp.delete()
            indexTemp.delete()
        }
    }

    /** PDFBox often splits one visual text block into several short paragraphs. */
    private fun mergeBlocks(parts: List<Paragraph>): List<Paragraph> {
        val merged = mutableListOf<Paragraph>()
        for (part in parts) {
            val previous = merged.lastOrNull()
            val a = previous?.box
            val b = part.box
            val overlap = if (a == null) 0f else (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f)
            val sameColumn = a != null && overlap > minOf(a.width(), b.width()) * .45f &&
                kotlin.math.abs(a.left - b.left) < 42f
            val close = a != null && b.top - a.bottom < 4f && b.top - a.bottom > -24f
            if (previous != null && !previous.text.trimEnd().endsWith('.') && sameColumn && close) {
                val box = RectF(a!!).apply { union(b) }
                val shift = previous.rawText.length + 1
                merged[merged.lastIndex] = previous.copy(
                    text = previous.text + " " + part.text,
                    box = box,
                    rawText = previous.rawText + " " + part.rawText,
                    glyphs = previous.glyphs + part.glyphs.map {
                        it.copy(start = it.start + shift, end = it.end + shift)
                    })
            } else {
                merged.add(part.copy(index = merged.size + 1))
            }
        }
        return merged
    }

    private class PositionedStripper(private val pageNumber: Int) : PDFTextStripper() {
        private val paragraphs = mutableListOf<Paragraph>()
        private val content = StringBuilder()
        private var bounds: RectF? = null
        private val glyphs = mutableListOf<GlyphBox>()

        override fun writeString(text: String, positions: MutableList<TextPosition>) {
            val base = content.length
            content.append(text)
            var cursor = 0
            for (position in positions) {
                val x = position.xDirAdj
                val y = position.yDirAdj
                val right = x + position.widthDirAdj
                val bottom = y + position.heightDir
                val current = bounds
                // PDFBox's yDirAdj follows the text baseline. Include the glyphs above it.
                val top = y - position.heightDir
                if (current == null) bounds = RectF(x, top, right, bottom)
                else current.union(x, top, right, bottom)
                val unicode = position.unicode
                if (unicode.isNotEmpty()) {
                    val at = text.indexOf(unicode, cursor)
                    if (at >= 0) {
                        glyphs.add(GlyphBox(base + at, base + at + unicode.length,
                            RectF(x, top, right, bottom)))
                        cursor = at + unicode.length
                    }
                }
            }
        }

        override fun writeWordSeparator() {
            if (content.isNotEmpty() && content.last() != ' ') content.append(' ')
        }

        override fun writeLineSeparator() {
            if (content.isEmpty()) return
            if (content.trimEnd().endsWith('.')) flush()
            else if (content.last() != ' ') content.append(' ')
        }

        override fun writeParagraphEnd() {
            flush()
        }

        fun finish(): List<Paragraph> {
            flush()
            return paragraphs
        }

        private fun flush() {
            val raw = content.toString()
            val text = raw.trim().replace(Regex("\\s+"), " ")
            val box = bounds
            if (text.isNotEmpty() && box != null) {
                paragraphs.add(Paragraph(pageNumber, paragraphs.size + 1, text, RectF(box),
                    raw, glyphs.toList()))
            }
            content.setLength(0)
            bounds = null
            glyphs.clear()
        }
    }
}
