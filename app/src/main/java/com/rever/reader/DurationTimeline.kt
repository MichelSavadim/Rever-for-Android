package com.rever.reader

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToLong

/** Book-wide narration estimate. Known paragraph durations replace the initial text estimate. */
class DurationTimeline private constructor(
    val file: File,
    private val data: JSONObject,
    private val base: LongArray,
    private val actual: MutableMap<Int, Long>,
    private val voice: String,
    private val paragraphs: List<Paragraph>
) {
    private var durations = LongArray(base.size)
    private var prefix = LongArray(base.size + 1)

    val isExact: Boolean get() = base.indices.all { base[it] == 0L || actual.containsKey(it) }

    init { recalculate() }

    fun totalMs(speed: Float): Long = (prefix.last().toDouble() / speed.coerceAtLeast(.1f)).roundToLong()

    fun positionMs(index: Int, offsetMs: Long, speed: Float): Long {
        if (index !in durations.indices) return 0
        val mediaPosition = prefix[index] + offsetMs.coerceIn(0L, durations[index])
        return (mediaPosition.toDouble() / speed.coerceAtLeast(.1f)).roundToLong()
    }

    fun recordDuration(index: Int, durationMs: Long): Boolean {
        if (index !in base.indices || base[index] == 0L || durationMs <= 0L) return false
        if (actual[index] == durationMs) return false
        actual[index] = durationMs
        recalculate()
        return true
    }

    fun snapshot(): String {
        val voices = data.optJSONObject("voices") ?: JSONObject().also { data.put("voices", it) }
        val values = JSONObject()
        actual.forEach { (index, duration) -> values.put(index.toString(), duration) }
        voices.put(voice, values)
        data.put("paragraph_count", base.size)
        data.put("estimated_total_ms", prefix.last())
        return data.toString()
    }

    private fun recalculate() {
        val ratios = actual.mapNotNull { (index, duration) ->
            val estimate = base.getOrNull(index) ?: return@mapNotNull null
            if (estimate < 5_000L || paragraphs[index].text.contains(".....")) null
            else duration.toDouble() / estimate
        }.sorted()
        val knownBase = actual.keys.sumOf { base.getOrElse(it) { 0L } }
        val factor = if (ratios.isEmpty()) 1.0 else {
            val middle = ratios.size / 2
            val median = if (ratios.size % 2 == 0) (ratios[middle - 1] + ratios[middle]) / 2 else ratios[middle]
            val observed = median.coerceIn(.65, 1.5)
            1 + (observed - 1) * knownBase / (knownBase + 120_000.0)
        }
        durations = LongArray(base.size) { index ->
            if (base[index] == 0L) 0L else actual[index] ?: (base[index] * factor).roundToLong().coerceAtLeast(650L)
        }
        prefix = LongArray(base.size + 1)
        for (index in durations.indices) prefix[index + 1] = prefix[index] + durations[index]
    }

    companion object {
        private const val VERSION = 4
        private val word = Regex("[\\p{L}\\p{N}]+")

        fun load(folder: File, paragraphs: List<Paragraph>, voice: String,
                 ignored: Set<String>): DurationTimeline {
            val file = File(folder, "timing.json")
            val saved = try { if (file.isFile) JSONObject(file.readText(Charsets.UTF_8)) else JSONObject() }
                catch (_: Exception) { JSONObject() }
            val compatible =
                saved.optInt("paragraph_version") == ParagraphExtractor.VERSION &&
                saved.optJSONArray("base_ms")?.length() == paragraphs.size
            val valid = compatible && saved.optInt("version") == VERSION
            val base = if (valid) {
                val stored = saved.getJSONArray("base_ms")
                LongArray(paragraphs.size) { stored.optLong(it).coerceAtLeast(0L) }
            } else LongArray(paragraphs.size) { index ->
                if (paragraphs[index].text in ignored) 0L else estimate(paragraphs[index].text)
            }
            val data = if (valid) saved else JSONObject()
                .put("version", VERSION)
                .put("paragraph_version", ParagraphExtractor.VERSION)
                .put("base_ms", JSONArray().apply { base.forEach { put(it) } })
                .put("voices", if (compatible) saved.optJSONObject("voices") ?: JSONObject() else JSONObject())
            val values = data.optJSONObject("voices")?.optJSONObject(voice)
            val actual = mutableMapOf<Int, Long>()
            values?.keys()?.forEach { key ->
                val index = key.toIntOrNull()
                val duration = values.optLong(key)
                if (index != null && index in base.indices && duration > 0L) actual[index] = duration
            }
            return DurationTimeline(file, data, base, actual, voice, paragraphs)
        }

        private fun estimate(text: String): Long {
            if (!NarrationFiles.hasSpeech(text)) return 0L
            val normalized = text.replace(Regex("\\.{3,}"), ",")
                .replace(Regex("\\s+,"), ",")
                .trim().replace(Regex("\\s+"), " ")
            val words = word.findAll(normalized).map { it.value }.toList()
            val longWords = words.sumOf { (it.length - 7).coerceAtLeast(0) }
            val pauses = normalized.count { it == ',' || it == ';' || it == ':' } * 130L +
                normalized.count { it == '.' || it == '!' || it == '?' } * 260L
            return (words.size * 350L + longWords * 20L + pauses).coerceAtLeast(650L)
        }
    }
}
