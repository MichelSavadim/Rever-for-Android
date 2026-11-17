package com.rever.reader

import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

class Pronunciations(private val root: File) {
    private val file = File(root, "pronunciation.json")
    fun read(): JSONObject = try { JSONObject(file.readText()) } catch (_: Exception) {
        JSONObject().put("lists", JSONObject())
    }
    @Synchronized fun merge(incoming: JSONObject): JSONObject {
        val data = read()
        val lists = data.optJSONObject("lists") ?: JSONObject().also { data.put("lists", it) }
        val other = incoming.optJSONObject("lists") ?: JSONObject()
        for (id in other.keys()) {
            val value = other.optJSONObject(id) ?: continue
            if (value.optLong("updated_at") > (lists.optJSONObject(id)?.optLong("updated_at") ?: 0L))
                lists.put(id, value)
        }
        val temp = File(root, "pronunciation.json.tmp")
        temp.writeText(data.toString())
        check(temp.renameTo(file)) { "Não foi possível salvar as pronúncias." }
        return data
    }
    fun rules(folder: File): Map<String, String> {
        val categories = CategoryStore(root.parentFile!!, root)
        val ids = categories.membership(folder)
        val names = categories.all().filter { it.id in ids }.map { it.name.lowercase(java.util.Locale.ROOT) }.toSet()
        val lists = read().optJSONObject("lists") ?: JSONObject()
        val result = sortedMapOf<String, String>()
        fun values(array: JSONArray?): List<String> = (0 until (array?.length() ?: 0)).map { array!!.getString(it) }
        for (id in lists.keys().asSequence().toList().sorted()) {
            val value = lists.getJSONObject(id)
            if (value.optBoolean("deleted")) continue
            if (folder.name !in values(value.optJSONArray("books")) &&
                values(value.optJSONArray("categories")).none { it.lowercase(java.util.Locale.ROOT) in names }) continue
            val rules = value.optJSONArray("rules") ?: continue
            for (n in 0 until rules.length()) {
                val rule = rules.getJSONObject(n)
                val word = rule.optString("word").trim()
                val say = rule.optString("say").trim()
                if (word.isNotEmpty() && say.isNotEmpty()) result[word.lowercase(java.util.Locale.ROOT)] = say
            }
        }
        return result
    }
    fun apply(folder: File, text: String): String {
        val rules = rules(folder)
        if (rules.isEmpty()) return text
        val alternatives = rules.keys.sortedWith(compareByDescending<String> { it.length }.thenBy { it })
            .joinToString("|") { Regex.escape(it) }
        return Regex("(?<![\\p{L}\\p{N}_])(?:$alternatives)(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
            .replace(text) { rules[it.value.lowercase(java.util.Locale.ROOT)] ?: it.value }
    }
    fun revision(folder: File): String = MessageDigest.getInstance("SHA-256")
        .digest(rules(folder).entries.joinToString("\n") { "${it.key}=${it.value}" }.toByteArray())
        .take(6).joinToString("") { "%02x".format(it) }
}
