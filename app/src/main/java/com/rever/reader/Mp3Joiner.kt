package com.rever.reader

import java.io.File
import java.io.FileOutputStream

/** Joins paragraph MP3 frames without transcoding. */
object Mp3Joiner {
    fun join(parts: List<File>, output: File, cancelled: () -> Boolean = { false }) {
        require(parts.isNotEmpty()) { "Não há áudio no intervalo selecionado." }
        output.parentFile?.mkdirs()
        val partial = File(output.parentFile, "${output.name}.part")
        try {
            FileOutputStream(partial).use { result ->
                for (part in parts) {
                    if (cancelled()) throw ExportCancelled()
                    part.inputStream().use { input ->
                        val prefix = ByteArray(2)
                        require(input.read(prefix) == 2 && (prefix[0].toInt() and 0xff) == 0xff &&
                            (prefix[1].toInt() and 0xe0) == 0xe0) {
                            "Trecho MP3 inválido: ${part.name}"
                        }
                        result.write(prefix)
                        input.copyTo(result)
                    }
                }
                result.fd.sync()
            }
            if (cancelled()) throw ExportCancelled()
            check(partial.renameTo(output)) { "Não foi possível publicar o MP3 completo." }
        } finally {
            partial.delete()
        }
    }
}

class ExportCancelled : Exception()
