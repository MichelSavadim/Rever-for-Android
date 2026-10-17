package com.rever.reader

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Serializes access to the device's installed, offline TTS voices. Call from a worker thread. */
object AndroidTts {
    const val DEFAULT_LANGUAGE = "pt-BR"
    private var engine: TextToSpeech? = null

    data class InstalledVoice(val id: String, val label: String, val language: String)

    @Synchronized
    private fun getEngine(context: Context): TextToSpeech {
        engine?.let { return it }
        val ready = CountDownLatch(1)
        val status = AtomicReference<Int>()
        val tts = TextToSpeech(context.applicationContext) {
            status.set(it)
            ready.countDown()
        }
        if (!ready.await(15, TimeUnit.SECONDS) || status.get() != TextToSpeech.SUCCESS) {
            tts.shutdown()
            throw IllegalStateException("O serviço de voz do Android não iniciou. Verifique se há um mecanismo TTS instalado.")
        }
        engine = tts
        return tts
    }

    private fun offline(voice: Voice): Boolean = !voice.isNetworkConnectionRequired &&
        !voice.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)

    @Synchronized
    fun voices(context: Context): List<InstalledVoice> = getEngine(context).voices.orEmpty()
        .filter(::offline)
        .sortedWith(compareBy<Voice>({ it.locale.language != "pt" }, { it.locale.toLanguageTag() }, { it.name }))
        .map { InstalledVoice(it.name, "${it.name} (${it.locale.toLanguageTag()})", it.locale.toLanguageTag()) }

    @Synchronized
    fun preview(context: Context, voiceId: String) {
        val tts = getEngine(context)
        val voice = tts.voices.orEmpty().firstOrNull { it.name == voiceId && offline(it) }
            ?: throw IllegalStateException("Esta voz offline não está mais disponível no Android.")
        tts.stop()
        check(tts.setVoice(voice) == TextToSpeech.SUCCESS) { "Não foi possível selecionar a voz ${voice.name}." }
        check(tts.speak("Olá! Esta é uma amostra da voz para a leitura dos seus PDFs.",
            TextToSpeech.QUEUE_FLUSH, Bundle(), UUID.randomUUID().toString()) == TextToSpeech.SUCCESS) {
            "Não foi possível reproduzir a amostra da voz."
        }
    }

    @Synchronized
    fun generate(context: Context, paragraph: Paragraph, output: File, voiceId: String) {
        val tts = getEngine(context)
        val available = tts.voices.orEmpty().filter(::offline)
        val selected = available.firstOrNull { it.name == voiceId }
            ?: available.firstOrNull { it.locale.toLanguageTag().equals(voiceId, true) }
            ?: available.firstOrNull { it.locale.language == "pt" && it.locale.country == "BR" }
            ?: throw IllegalStateException("Nenhuma voz offline em português brasileiro está instalada. Instale uma voz nas configurações de síntese de voz do Android.")
        check(tts.setVoice(selected) == TextToSpeech.SUCCESS) {
            "Não foi possível selecionar a voz ${selected.name}."
        }
        tts.stop()
        require(paragraph.text.length <= TextToSpeech.getMaxSpeechInputLength()) {
            "O parágrafo excede o limite de texto aceito pela voz do Android."
        }
        output.parentFile?.mkdirs()
        val partial = File(output.parentFile, "${output.name}.${UUID.randomUUID()}.wav")
        val utterance = UUID.randomUUID().toString()
        val finished = CountDownLatch(1)
        val failure = AtomicReference<String?>()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) { if (utteranceId == utterance) finished.countDown() }
            override fun onError(utteranceId: String) {
                if (utteranceId == utterance) {
                    failure.set("A voz do Android falhou ao gerar o áudio.")
                    finished.countDown()
                }
            }
            override fun onError(utteranceId: String, errorCode: Int) {
                if (utteranceId == utterance) {
                    failure.set("A voz do Android falhou ao gerar o áudio (código $errorCode).")
                    finished.countDown()
                }
            }
        })
        try {
            check(tts.synthesizeToFile(paragraph.text, Bundle(), partial, utterance) == TextToSpeech.SUCCESS) {
                "A voz do Android recusou a geração do áudio."
            }
            check(finished.await(90, TimeUnit.SECONDS)) { "A voz do Android demorou demais para gerar o áudio." }
            failure.get()?.let { throw IllegalStateException(it) }
            check(partial.isFile && partial.length() > 44L) { "A voz do Android criou um arquivo de áudio vazio." }
            WavToMp3.convert(partial, output)
        } finally {
            partial.delete()
        }
    }
}
