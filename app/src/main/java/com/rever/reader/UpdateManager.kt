package com.rever.reader

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ReverRelease(
    val version: String, val publishedAt: String, val notes: String,
    val apkUrl: String, val sha256: String?, val size: Long
) {
    fun toJson(): String = JSONObject().put("version", version)
        .put("published_at", publishedAt).put("notes", notes)
        .put("apk_url", apkUrl).put("sha256", sha256).put("size", size).toString()

    companion object {
        fun fromJson(data: JSONObject) = ReverRelease(
            data.getString("version"), data.optString("published_at"),
            data.optString("notes"), data.getString("apk_url"),
            data.optString("sha256").takeIf { it.isNotBlank() }, data.optLong("size"))
    }
}

class UpdateManager(private val context: Context) {
    private val repository = "MichelSavadim/Rever-for-Android"
    val repositoryUrl = "https://github.com/$repository"

    fun latest(): ReverRelease? = fetchRelease("https://api.github.com/repos/$repository/releases/latest")

    fun current(version: String): ReverRelease? =
        fetchRelease("https://api.github.com/repos/$repository/releases/tags/v$version")
            ?: fetchRelease("https://api.github.com/repos/$repository/releases/tags/$version")

    private fun fetchRelease(address: String): ReverRelease? {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Rever-Android")
        }
        try {
            if (connection.responseCode == 404) return null
            check(connection.responseCode == 200) { "GitHub respondeu ${connection.responseCode}." }
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val version = release.getString("tag_name").removePrefix("v")
            require(version.matches(Regex("\\d+\\.\\d+\\.\\d+"))) { "Versão inválida no GitHub." }
            val assets = release.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) &&
                    it.optString("state") == "uploaded" } ?: return null
            val url = apk.getString("browser_download_url")
            require(URL(url).protocol == "https" && URL(url).host == "github.com") {
                "Endereço do APK inválido."
            }
            val digest = apk.optString("digest").removePrefix("sha256:")
                .takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
            return ReverRelease(version,
                if (release.isNull("published_at")) "" else release.optString("published_at"),
                if (release.isNull("body")) "" else release.optString("body"),
                url, digest, apk.optLong("size"))
        } finally { connection.disconnect() }
    }

    fun isNewer(remote: String, installed: String): Boolean {
        val a = remote.removePrefix("v").split('.').mapNotNull(String::toIntOrNull)
        val b = installed.removePrefix("v").split('.').mapNotNull(String::toIntOrNull)
        if (a.size != 3 || b.size != 3) return false
        for (index in 0..2) if (a[index] != b[index]) return a[index] > b[index]
        return false
    }

    fun download(release: ReverRelease, cancelled: () -> Boolean,
                 progress: (Long, Long) -> Unit): File {
        val url = URL(release.apkUrl)
        require(url.protocol == "https" && url.host == "github.com")
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File(folder, "Rever-${release.version}-partial.apk")
        val destination = File(folder, "Rever-${release.version}.apk")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Rever-Android")
        }
        try {
            check(connection.responseCode == 200) { "Download respondeu ${connection.responseCode}." }
            val finalUrl = connection.url
            require(finalUrl.protocol == "https" &&
                (finalUrl.host == "github.com" || finalUrl.host.endsWith(".githubusercontent.com"))) {
                "O download saiu dos servidores do GitHub."
            }
            val expected = connection.contentLengthLong.takeIf { it > 0 } ?: release.size
            require(expected in 1..250_000_000) { "Tamanho do APK inválido." }
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw InterruptedException("Download cancelado.")
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        require(received <= 250_000_000) { "APK excede o tamanho permitido." }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        progress(received, expected)
                    }
                }
            }
            require(received == expected) { "Download incompleto." }
            val checksum = digest.digest().joinToString("") { "%02x".format(it) }
            require(release.sha256 == null || checksum.equals(release.sha256, ignoreCase = true)) {
                "O checksum do APK não confere."
            }
            verifyPackage(partial, release.version)
            if (destination.exists()) destination.delete()
            check(partial.renameTo(destination)) { "Não foi possível salvar o APK." }
            return destination
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    private fun verifyPackage(apk: File, expectedVersion: String) {
        val manager = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = manager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw IllegalArgumentException("O arquivo baixado não é um APK válido.")
        val installed = manager.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName) { "O APK pertence a outro aplicativo." }
        require(archive.versionName == expectedVersion &&
            archive.longVersionCode > installed.longVersionCode) { "Versão do APK incompatível." }
        fun signatures(info: android.content.pm.PackageInfo): Set<String> =
            (info.signingInfo?.apkContentsSigners
                ?: throw IllegalArgumentException("A assinatura do APK não pôde ser lida.")).map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }.toSet()
        require(signatures(archive) == signatures(installed)) {
            "A assinatura do APK é diferente da instalação atual."
        }
    }
}
