package com.air5005.pagenest.speech.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager

/** Pauses speech before its output is redirected to the device speaker. */
class BecomingNoisyReceiver(private val onNoisy: () -> Unit) : BroadcastReceiver() {
    constructor(controller: SpeechController) : this(controller::pause)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onNoisy()
    }
}
