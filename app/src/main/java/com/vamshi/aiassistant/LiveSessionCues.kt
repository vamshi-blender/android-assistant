package com.vamshi.aiassistant

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.annotation.RawRes

/** Plays the short session earcons copied from the GPT-Live reference project. */
internal object LiveSessionCues {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun play(context: Context, @RawRes sound: Int) {
        val player = MediaPlayer.create(context.applicationContext, sound, attributes, 0) ?: return
        player.setOnCompletionListener { it.release() }
        player.setOnErrorListener { failed, _, _ ->
            failed.release()
            true
        }
        runCatching { player.start() }
            .onFailure {
                Log.w("LiveSessionCues", "Unable to play session cue", it)
                player.release()
            }
    }
}
