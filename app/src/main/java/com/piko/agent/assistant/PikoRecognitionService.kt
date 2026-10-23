package com.piko.agent.assistant

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.*

/** Delegates to a real installed recognizer, excluding Piko to prevent recursion. */
class PikoRecognitionService : RecognitionService() {
    private var recognizer: SpeechRecognizer? = null
    private val sessions = com.piko.agent.voice.VoiceSessionGate()
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        val token = sessions.begin()
        recognizer?.destroy()
        val local = !recognizerIntent.getBooleanExtra("piko.forceExternal", false) && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        val external = packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .firstOrNull { it.serviceInfo.packageName != packageName }?.serviceInfo
        if (!local && external == null) { sessions.cancel(); runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }; return }
        val speech = if (local) SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            else SpeechRecognizer.createSpeechRecognizer(this, ComponentName(external!!.packageName, external.name))
        recognizer = speech
        fun deliver(block: () -> Unit) {
            if (!sessions.isCurrent(token)) return
            runCatching(block).onFailure {
                sessions.cancel(); recognizer?.destroy(); recognizer = null
            }
        }
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { deliver { listener.readyForSpeech(params ?: Bundle()) } }
            override fun onBeginningOfSpeech() { deliver { listener.beginningOfSpeech() } }
            override fun onRmsChanged(rmsdB: Float) { deliver { listener.rmsChanged(rmsdB) } }
            override fun onBufferReceived(buffer: ByteArray?) { deliver { buffer?.let { listener.bufferReceived(it) } } }
            override fun onEndOfSpeech() { deliver { listener.endOfSpeech() } }
            override fun onError(error: Int) { deliver { listener.error(error) }; sessions.move(token, com.piko.agent.voice.VoiceSessionGate.State.ERROR) }
            override fun onResults(results: Bundle?) { deliver { listener.results(results ?: Bundle()) }; sessions.move(token, com.piko.agent.voice.VoiceSessionGate.State.COMPLETE) }
            override fun onPartialResults(partialResults: Bundle?) { deliver { listener.partialResults(partialResults ?: Bundle()) } }
            override fun onEvent(eventType: Int, params: Bundle?) { }
        })
        runCatching { speech.startListening(recognizerIntent) }.onFailure {
            deliver { listener.error(SpeechRecognizer.ERROR_CLIENT) }; sessions.cancel(); speech.destroy(); recognizer = null
        }
    }
    override fun onStopListening(listener: Callback) { recognizer?.stopListening() }
    override fun onCancel(listener: Callback) { sessions.cancel(); recognizer?.cancel(); recognizer?.destroy(); recognizer = null }
    override fun onDestroy() { sessions.cancel(); recognizer?.destroy(); recognizer = null; super.onDestroy() }
}
