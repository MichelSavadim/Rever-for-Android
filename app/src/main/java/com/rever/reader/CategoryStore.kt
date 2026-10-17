package com.rever.reader

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class BookCategory(val id: String, val name: String)

/** Category order is global; memberships live with each PDF in book.json. */
class CategoryStore(private val filesDir: File, private val library: File) {
    private val file get() = File(filesDir, "categories.json")

    fun all(): List<BookCategory> {
        if (!file.isFile) return emptyList()
        val data = JSONArray(file.readText(Charsets.UTF_8))
        return (0 until data.length()).map { index ->
            val item = data.getJSONObject(index)
            BookCategory(item.getString("id"), item.getString("name"))
        }
    }

    fun add(name: String): BookCategory {
        val categories = all().toMutableList()
        val clean = validatedName(name, categories)
        val category = BookCategory(UUID.randomUUID().toString(), clean)
        categories.add(category)
        save(categories)
        return category
    }

    fun rename(id: String, name: String) {
        val categories = all().toMutableList()
        val index = categories.indexOfFirst { it.id == id }
        require(index >= 0) { "Categoria não encontrada." }
        categories[index] = categories[index].copy(
            name = validatedName(name, categories.filterNot { it.id == id }))
        save(categories)
    }

    fun move(id: String, direction: Int) {
        val categories = all().toMutableList()
        val index = categories.indexOfFirst { it.id == id }
        val destination = index + direction
        if (index < 0 || destination !in categories.indices) return
        val category = categories.removeAt(index)
        categories.add(destination, category)
        save(categories)
    }

    fun delete(id: String) {
        val categories = all().filterNot { it.id == id }
        save(categories)
        library.listFiles()?.filter { it.isDirectory }?.forEach { folder ->
            val bookFile = File(folder, "book.json")
            if (bookFile.isFile) {
                val selected = membership(folder)
                if (id in selected) assign(folder, selected - id)
            }
        }
    }

    fun membership(folder: File): Set<String> {
        val data = JSONObject(File(folder, "book.json").readText(Charsets.UTF_8))
        val array = data.optJSONArray("categories") ?: return emptySet()
        return (0 until array.length()).map { array.getString(it) }.toSet()
    }

    fun assign(folder: File, ids: Set<String>) {
        val valid = all().map { it.id }.toSet()
        val bookFile = File(folder, "book.json")
        val data = JSONObject(bookFile.readText(Charsets.UTF_8))
        data.put("categories", JSONArray().apply { ids.filter { it in valid }.forEach(::put) })
        writeAtomic(bookFile, data.toString())
    }

    private fun validatedName(name: String, others: List<BookCategory>): String {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Digite um nome para a categoria." }
        require(clean.length <= 60) { "O nome pode ter até 60 caracteres." }
        require(others.none { it.name.equals(clean, ignoreCase = true) }) {
            "Já existe uma categoria com esse nome."
        }
        return clean
    }

    private fun save(categories: List<BookCategory>) = writeAtomic(file, JSONArray().apply {
        categories.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
    }.toString())

    private fun writeAtomic(destination: File, text: String) {
        val temp = File(destination.parentFile, destination.name + ".tmp")
        try {
            temp.writeText(text, Charsets.UTF_8)
            check(temp.renameTo(destination)) { "Não foi possível salvar ${destination.name}." }
        } finally {
            temp.delete()
        }
    }
}
