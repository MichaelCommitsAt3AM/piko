package com.piko.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.piko.agent.agent.AgentRuntime
import com.piko.agent.service.AgentService
import com.piko.agent.ui.PikoViewModel
import com.piko.agent.ui.theme.PikoTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Visible, push-to-talk input. No background microphone or hotword listener. */
class VoiceInputActivity : ComponentActivity() {
    private val viewModel: PikoViewModel by viewModels()
    private var recognizer: SpeechRecognizer? = null
    private var taskId: String? = null
    private var session = UUID.randomUUID().toString()
    private var submitted = false
    private val voiceGate = com.piko.agent.voice.VoiceSessionGate()
    private val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PikoApplication.from(this).speechOutput.stop()
        taskId = if (savedInstanceState != null) savedInstanceState.getString("taskId")
            else AgentRuntime.state().takeIf { it.isActive }?.taskId
        session = savedInstanceState?.getString("session") ?: session
        taskId?.let { AgentService.beginInput(this, it, session) }
        setContent {
            PikoTheme {
                var transcript by rememberSaveable { mutableStateOf("") }
                var status by remember { mutableStateOf("Tap the microphone or type an instruction.") }
                var listening by remember { mutableStateOf(false) }
                var sending by remember { mutableStateOf(false) }
                var screenQuestion by rememberSaveable { mutableStateOf(intent.getBooleanExtra("screenQuestion", false)) }
                var consent by rememberSaveable { mutableStateOf(false) }
                val settings by viewModel.settings.collectAsState()
                val scope = rememberCoroutineScope()
                fun listen(forceSystem: Boolean = false) {
                    timeoutHandler.removeCallbacksAndMessages(null)
                    val token = voiceGate.begin()
                    recognizer?.destroy()
                    val local = !forceSystem && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
                    if (!local && !SpeechRecognizer.isRecognitionAvailable(this)) {
                        status = "Speech recognition is unavailable. You can type below."
                    } else {
                        val speech = if (local) SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                            else SpeechRecognizer.createSpeechRecognizer(this)
                        recognizer = speech
                        speech.setRecognitionListener(object : RecognitionListener {
                            override fun onReadyForSpeech(params: Bundle?) { if (voiceGate.move(token, com.piko.agent.voice.VoiceSessionGate.State.LISTENING)) { listening = true; status = "Listening…" } }
                            override fun onBeginningOfSpeech() { }
                            override fun onRmsChanged(rmsdB: Float) { }
                            override fun onBufferReceived(buffer: ByteArray?) { }
                            override fun onEndOfSpeech() { if (voiceGate.move(token, com.piko.agent.voice.VoiceSessionGate.State.TRANSCRIBING)) { listening = false; status = "Transcribing…" } }
                            override fun onError(error: Int) {
                                if (!voiceGate.move(token, com.piko.agent.voice.VoiceSessionGate.State.ERROR)) return
                                timeoutHandler.removeCallbacksAndMessages(null)
                                if (local && error in setOf(
                                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                                    )) {
                                    listen(forceSystem = true)
                                    return
                                }
                                listening = false
                                status = when (error) {
                                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard. Try again or type below."
                                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access is required for voice input."
                                    else -> "Speech recognition failed ($error). Try again or type below."
                                }
                            }
                            override fun onResults(results: Bundle?) {
                                if (!voiceGate.move(token, com.piko.agent.voice.VoiceSessionGate.State.COMPLETE)) return
                                timeoutHandler.removeCallbacksAndMessages(null)
                                listening = false
                                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                                if (!text.isNullOrBlank()) { transcript = text.take(2000); status = "Review your instruction, then send it." }
                                else status = "No speech recognized. Try again or type below."
                            }
                            override fun onPartialResults(partialResults: Bundle?) {
                                if (!voiceGate.isCurrent(token)) return
                                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { transcript = it.take(2000) }
                            }
                            override fun onEvent(eventType: Int, params: Bundle?) { }
                        })
                        timeoutHandler.postDelayed({
                            if (voiceGate.isCurrent(token)) { voiceGate.cancel(); speech.cancel(); listening = false; status = "Voice input timed out. Tap Microphone to try again." }
                        }, 45_000)
                        status = "Starting microphone…"
                        listening = true
                        runCatching {
                            speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra("piko.forceExternal", forceSystem)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, settings.recognitionLanguage)
                            })
                        }.onFailure { listening = false; status = "Could not start the microphone. You can type below." }
                    }
                }
                val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
                    if (allowed) listen() else status = "Microphone permission was denied. You can still type an instruction."
                }
                fun startListening() {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                }
                LaunchedEffect(Unit) { if (intent.getBooleanExtra("useVoice", true)) startListening() }
                LaunchedEffect(Unit) { viewModel.messages.collect { status = it; sending = false } }
                Surface(shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(if (taskId != null) "Update your task" else "Talk to Piko", style = MaterialTheme.typography.titleLarge)
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = transcript, onValueChange = { transcript = it.take(2000) },
                            label = { Text("Instruction") }, minLines = 3, maxLines = 5,
                            modifier = Modifier.fillMaxWidth(), enabled = !sending,
                        )
                        if (taskId == null) {
                            Row { Checkbox(screenQuestion, { screenQuestion = it; consent = false }); Text("Ask about the screen (read-only)") }
                            if (screenQuestion) {
                                Text("Return to the target app before opening this panel. Fresh permitted screen text${if (settings.sendScreenshots) " and supported screenshots" else ""} will be sent to ${settings.selectedProvider?.name ?: "the selected provider"}. Screenshots are not saved.")
                                Row { Checkbox(consent, { consent = it }); Text("I authorize sharing this screen for this question.") }
                            }
                        }
                        Text(
                            "Piko uses on-device speech when available; otherwise your system speech service may process audio online. The task waits while this panel is open.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { if (listening) { voiceGate.cancel(); recognizer?.cancel(); listening = false; status = "Microphone stopped." } else startListening() }, enabled = !sending) {
                                Text(if (listening) "Stop mic" else "Microphone")
                            }
                            TextButton(onClick = { finish() }, enabled = !sending) { Text("Cancel") }
                            Button(
                                enabled = transcript.isNotBlank() && !sending && (!screenQuestion || consent),
                                onClick = {
                                    voiceGate.cancel()
                                    recognizer?.cancel()
                                    listening = false
                                    val target = taskId
                                    if (target != null) {
                                        if (AgentRuntime.state().taskId != target || !AgentRuntime.state().isActive) {
                                            status = "That task has ended. Close this panel to start a new task."
                                        } else {
                                            submitted = true
                                            AgentService.endInput(this@VoiceInputActivity, target, session, transcript)
                                            finish()
                                        }
                                    } else {
                                        val previous = AgentRuntime.state().taskId
                                        if (viewModel.startTask(transcript, screenQuestion)) {
                                            sending = true
                                            scope.launch {
                                                val started = withTimeoutOrNull(10_000) { AgentRuntime.state.first { it.taskId != previous } }
                                                if (started != null && started.isActive) {
                                                    delay(500)
                                                    if (!screenQuestion && started.screenHandoffRequired) startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                                    finish()
                                                } else if (started?.state == com.piko.agent.core.AgentState.COMPLETED) {
                                                    status = started.resultSummary ?: "Completed."
                                                    sending = false
                                                    transcript = ""
                                                } else {
                                                    status = AgentRuntime.state().error ?: "The task did not start. Open Piko for details."
                                                    sending = false
                                                }
                                            }
                                        }
                                    }
                                },
                            ) { Text(if (taskId != null) "Send" else "Start") }
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("taskId", taskId)
        outState.putString("session", session)
        super.onSaveInstanceState(outState)
    }
    override fun onStop() { voiceGate.cancel(); timeoutHandler.removeCallbacksAndMessages(null); recognizer?.cancel(); super.onStop() }
    override fun onDestroy() {
        timeoutHandler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        if (!isChangingConfigurations && !submitted) taskId?.let { AgentService.endInput(this, it, session, null) }
        super.onDestroy()
    }
    companion object {
        fun open(context: Context, screenQuestion: Boolean = false) {
            context.startActivity(Intent(context, VoiceInputActivity::class.java).putExtra("screenQuestion", screenQuestion).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
