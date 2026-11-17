package com.rever.reader

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.widget.*
import android.view.View
import android.view.ViewGroup
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID

object PronunciationDialog {
    fun show(activity: Activity, library: File, book: File?, changed: () -> Unit) {
        if (book == null) {
            Toast.makeText(activity, "Abra um PDF para configurar suas pronúncias.", Toast.LENGTH_LONG).show()
            return
        }
        val store = Pronunciations(library)
        val lists = store.read().optJSONObject("lists") ?: JSONObject()
        val categories = CategoryStore(activity.filesDir, library)
        val membership = categories.membership(book)
        val inherited = categories.all().filter { it.id in membership }.map { it.name.lowercase(java.util.Locale.ROOT) }.toSet()
        fun values(array: JSONArray?): List<String> = (0 until (array?.length() ?: 0)).map { array!!.getString(it) }
        val ids = lists.keys().asSequence().filter {
            val value = lists.getJSONObject(it)
            !value.optBoolean("deleted") && (book.name in values(value.optJSONArray("books")) ||
                values(value.optJSONArray("categories")).any { name -> name.lowercase(java.util.Locale.ROOT) in inherited })
        }.sorted().toList()
        if (ids.isEmpty()) { edit(activity, library, book, null, changed); return }
        val labels = arrayOf("Nova lista para este PDF") + ids.map { lists.getJSONObject(it).getString("name") }
        AlertDialog.Builder(activity).setTitle("Pronúncias deste PDF").setItems(labels) { _, selected ->
            edit(activity, library, book, ids.getOrNull(selected - 1), changed)
        }.setNegativeButton("Fechar", null).show()
    }
    private fun edit(activity: Activity, library: File, book: File, id: String?, changed: () -> Unit) {
        val store = Pronunciations(library)
        val old = store.read().optJSONObject("lists")?.optJSONObject(id ?: "") ?: JSONObject()
        val form = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(35, 10, 35, 10) }
        fun label(text: String) { form.addView(TextView(activity).apply { this.text = text; setTextColor(Color.DKGRAY) }) }
        fun field(text: String, hint: String): EditText = EditText(activity).apply {
            setText(text); this.hint = hint; setTextColor(Color.DKGRAY); setHintTextColor(Color.GRAY); form.addView(this)
        }
        val title = try { JSONObject(File(book, "book.json").readText()).optString("title", "PDF") }
            catch (_: Exception) { "PDF" }
        label("Pronúncias do PDF: $title")
        val name = field(old.optString("name", "Pronúncias de $title"), "Nome da lista")
        fun values(array: JSONArray?): Set<String> = (0 until (array?.length() ?: 0)).map { array!!.getString(it) }.toSet()
        val categories = CategoryStore(activity.filesDir, library).all()
        val checks = categories.map { category -> category.name to CheckBox(activity).apply {
            text = category.name; setTextColor(Color.DKGRAY)
            isChecked = category.name in values(old.optJSONArray("categories"))
        } }
        label("Uma palavra ou expressão por linha: palavra = pronúncia")
        val entries = old.optJSONArray("rules") ?: JSONArray()
        val rules = field((0 until entries.length()).joinToString("\n") {
            val rule = entries.getJSONObject(it); rule.getString("word") + " = " + rule.getString("say")
        }, "hardware = rád uér").apply { minLines = 5; inputType = 131073 }
        label("Aplicar esta mesma lista também às categorias (opcional):")
        checks.forEach { form.addView(it.second) }
        label("Ao compartilhar, futuras edições afetam todos os PDFs dessas categorias.")
        val dialog = AlertDialog.Builder(activity).setTitle("Pronúncia")
            .setView(ScrollView(activity).apply { addView(form) })
            .setPositiveButton("Salvar", null).setNegativeButton("Cancelar", null)
            .setNeutralButton("Excluir lista", null).create()
        fun save(deleted: Boolean) {
            if (name.text.toString().trim().isEmpty()) { name.error = "Informe um nome"; return }
            val parsed = JSONArray()
            for (line in rules.text.toString().lines().filter { it.isNotBlank() }) {
                val parts = line.split("=", limit = 2)
                if (parts.size != 2 || parts.any { it.isBlank() }) { rules.error = "Use palavra = pronúncia"; return }
                parsed.put(JSONObject().put("word", parts[0].trim()).put("say", parts[1].trim()))
            }
            val assigned = values(old.optJSONArray("categories")).toMutableSet()
            assigned.removeAll(checks.map { it.first }.toSet())
            assigned.addAll(checks.filter { it.second.isChecked }.map { it.first })
            val books = values(old.optJSONArray("books")).toMutableSet()
            books.add(book.name)
            val record = JSONObject().put("name", name.text.toString().trim())
                .put("categories", JSONArray(assigned.sorted())).put("books", JSONArray(books.sorted()))
                .put("rules", parsed).put("deleted", deleted)
                .put("updated_at", maxOf(System.currentTimeMillis(), old.optLong("updated_at") + 1))
            store.merge(JSONObject().put("lists", JSONObject().put(id ?: UUID.randomUUID().toString(), record)))
            changed(); dialog.dismiss()
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { save(false) }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
                isEnabled = id != null; setOnClickListener { save(true) }
            }
        }
        dialog.show()
    }
}
