package com.piko.agent.voice

import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.piko.agent.model.PikoSettings
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

/** Process-owned TTS with explicit mute, audio focus and immediate interruption. */
class SpeechOutput(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    val status = MutableStateFlow("Initializing speech")
    val voices = MutableStateFlow<List<String>>(emptyList())
    private var ready = false
    private var initialized = false
    @Volatile private var utteranceId: String? = null
    private var muted = false
    private var pending: Pair<String, PikoSettings>? = null
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener({ change -> if (change < 0) stop() }, main).build()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { result ->
        main.post {
            initialized = true
            ready = result == TextToSpeech.SUCCESS
            status.value = if (ready) "Ready" else "Speech output unavailable"
            if (ready) {
                voices.value = ttsVoices()
                pending?.let { (text, settings) -> pending = null; speak(text, settings) }
            } else pending = null
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { if (id == utteranceId) status.value = "Speaking" }
            override fun onDone(id: String?) { if (id == utteranceId) release() }
            @Deprecated("Android callback") override fun onError(id: String?) { if (id == utteranceId) release("Speech output failed") }
            override fun onStop(id: String?, interrupted: Boolean) { if (id == utteranceId) release() }
        })
    }
    private fun ttsVoices(): List<String> = runCatching { tts.voices.orEmpty().map { it.name }.sorted() }.getOrDefault(emptyList())
    fun mute(value: Boolean) { muted = value; if (value) stop() }
    fun speak(text: String, settings: PikoSettings) {
        if (!settings.spokenResponses || muted) return
        if (initialized && !ready) { status.value = "Speech output unavailable"; return }
        if (!ready) { pending = text to settings; return }
        stop()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            status.value = "Audio focus unavailable"; return
        }
        val language = tts.setLanguage(Locale.forLanguageTag(settings.recognitionLanguage))
        if (language < 0) { release("Speech language unavailable"); return }
        settings.speechVoice?.let { name -> tts.voices?.firstOrNull { it.name == name }?.let { tts.voice = it } }
        tts.setSpeechRate(settings.speechRate)
        tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        utteranceId = java.util.UUID.randomUUID().toString()
        if (tts.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.ERROR) release("Speech output failed")
    }
    fun stop() { utteranceId = null; pending = null; tts.stop(); release() }
    private fun release(message: String = "Ready") { audio.abandonAudioFocusRequest(focus); status.value = message }
    fun close() { stop(); ready = false; tts.shutdown() }
}
