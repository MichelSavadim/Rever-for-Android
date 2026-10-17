package com.rever.reader

import com.github.axet.lamejni.Lame
import java.io.File
import java.io.RandomAccessFile

/** Converts the PCM WAV produced by Android TTS into the app's MP3 paragraph format. */
object WavToMp3 {
    private fun RandomAccessFile.le16(): Int = readUnsignedByte() or (readUnsignedByte() shl 8)
    private fun RandomAccessFile.le32(): Long = (le16().toLong() or (le16().toLong() shl 16))

    fun convert(wav: File, mp3: File) {
        System.loadLibrary("lame")
        System.loadLibrary("lamejni")
        RandomAccessFile(wav, "r").use { input ->
            val head = ByteArray(4)
            input.readFully(head)
            require(String(head, Charsets.US_ASCII) == "RIFF") { "A voz gerou áudio fora do formato WAV." }
            input.le32()
            input.readFully(head)
            require(String(head, Charsets.US_ASCII) == "WAVE") { "A voz gerou áudio fora do formato WAV." }
            var channels = 0
            var sampleRate = 0
            var bits = 0
            var encoding = 0
            var dataStart = -1L
            var dataLength = 0L
            while (input.filePointer + 8 <= input.length()) {
                input.readFully(head)
                val kind = String(head, Charsets.US_ASCII)
                val size = input.le32()
                val start = input.filePointer
                require(size <= input.length() - start) { "Arquivo WAV incompleto." }
                if (kind == "fmt " && size >= 16) {
                    encoding = input.le16()
                    channels = input.le16()
                    sampleRate = input.le32().toInt()
                    input.le32()
                    input.le16()
                    bits = input.le16()
                } else if (kind == "data") {
                    dataStart = start
                    dataLength = size
                }
                input.seek(start + size + (size and 1L))
            }
            require(encoding == 1 && bits == 16 && channels in 1..2 && sampleRate in 8000..48000) {
                "A voz gerou um formato de áudio não compatível com MP3."
            }
            require(dataStart >= 0 && dataLength > 0) { "O áudio da voz está vazio." }
            input.seek(dataStart)
            val encoder = Lame()
            encoder.open(channels, sampleRate, 96, 4)
            val partial = File(mp3.parentFile, "${mp3.name}.part")
            try {
                RandomAccessFile(partial, "rw").use { output ->
                    val bytes = ByteArray(8192)
                    var remaining = dataLength
                    while (remaining > 0) {
                        val amount = minOf(bytes.size.toLong(), remaining).toInt() and -2
                        require(amount > 0) { "Amostra WAV incompleta." }
                        input.readFully(bytes, 0, amount)
                        val samples = ShortArray(amount / 2) { i ->
                            ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
                        }
                        output.write(encoder.encode(samples, 0, samples.size))
                        remaining -= amount
                    }
                    output.write(encoder.encode(null, 0, 0))
                    val header = encoder.close()
                    if (header.isNotEmpty()) {
                        output.seek(0)
                        output.write(header)
                    }
                    output.fd.sync()
                }
                require(partial.length() > 2) { "O codificador MP3 não gerou áudio." }
                check(partial.renameTo(mp3)) { "Não foi possível guardar o MP3 gerado." }
            } finally {
                partial.delete()
            }
        }
    }
}
