package com.rever.reader

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import java.io.File

/** Online Edge voices use the upstream edge-tts package, bundled with the APK. */
object EdgeTts {
    const val PREFIX = "edge:"
    const val DEFAULT_VOICE = "pt-BR-FranciscaNeural"
    private val startLock = Any()

    fun isSelected(voiceId: String): Boolean = voiceId.startsWith(PREFIX)

    private fun module(context: Context) = synchronized(startLock) {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        Python.getInstance().getModule("rever_edge")
    }

    fun voices(context: Context): List<AndroidTts.InstalledVoice> {
        val data = JSONArray(module(context).callAttr("list_voices_json").toString())
        return (0 until data.length()).map { index ->
            val item = data.getJSONObject(index)
            val id = item.getString("id")
            val language = item.getString("language")
            val name = id.removePrefix("$language-").removeSuffix("Neural")
            val gender = when (item.optString("gender")) {
                "Male" -> "masculina"
                "Female" -> "feminina"
                else -> "voz"
            }
            AndroidTts.InstalledVoice(id, "$name ($gender, $language) · online", language)
        }.sortedWith(compareBy({ it.language != "pt-BR" }, { it.language }, { it.label }))
    }

    fun generate(context: Context, paragraph: Paragraph, output: File, voiceId: String) {
        require(isSelected(voiceId)) { "Voz Edge TTS inválida." }
        CrashReporter.markStage(context, "edge_online_request")
        try {
            module(context).callAttr("save_mp3", paragraph.text, voiceId.removePrefix(PREFIX), output.path)
        } catch (error: Exception) {
            throw IllegalStateException(
                "Não foi possível gerar áudio com Edge TTS. Verifique a conexão com a internet. ${error.message.orEmpty()}",
                error
            )
        }
        CrashReporter.markStage(context, "mp3_ready")
    }
}

object NarrationEngine {
    fun generate(context: Context, paragraph: Paragraph, output: File, voiceId: String) {
        if (EdgeTts.isSelected(voiceId)) EdgeTts.generate(context, paragraph, output, voiceId)
        else AndroidTts.generate(context, paragraph, output, voiceId)
    }
}
