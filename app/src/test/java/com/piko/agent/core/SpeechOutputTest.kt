package com.piko.agent.core

import android.content.Context
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.piko.agent.model.PikoSettings
import com.piko.agent.voice.SpeechOutput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeechOutputTest {
    @Test fun muteStopAndShutdownReleaseTheEngine() {
        val speech = SpeechOutput(ApplicationProvider.getApplicationContext<Context>())
        val shadow = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        shadow.onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        speech.mute(true)
        speech.speak("Should stay muted", PikoSettings(spokenResponses = true))
        assertNull(shadow.lastSpokenText)
        assertTrue(shadow.isStopped)
        speech.stop()
        speech.close()
        assertTrue(shadow.isShutdown)
    }
    @Test fun failedInitializationDoesNotQueueSpeechForever() {
        val speech = SpeechOutput(ApplicationProvider.getApplicationContext<Context>())
        val shadow = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        shadow.onInitListener.onInit(TextToSpeech.ERROR)
        shadowOf(Looper.getMainLooper()).idle()
        speech.speak("Unavailable", PikoSettings(spokenResponses = true))
        assertEquals("Speech output unavailable", speech.status.value)
        assertNull(shadow.lastSpokenText)
        speech.close()
    }
}
