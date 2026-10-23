package com.piko.agent.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.piko.agent.VoiceInputActivity

class PikoSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = PikoSession(this)
}

class PikoSession(context: Context) : VoiceInteractionSession(context) {
    override fun onCreate() { super.onCreate(); setUiEnabled(false) }
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // AssistStructure/screenshots delivered by the platform are deliberately ignored.
        // Screen sharing requires separate, visible consent in VoiceInputActivity.
        runCatching { startAssistantActivity(Intent(context, VoiceInputActivity::class.java)) }
        hide()
    }
}
