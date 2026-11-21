package com.ashish.atvlauncher

import android.graphics.Color
import android.net.Uri
import android.service.dreams.DreamService
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * TV screensaver: loops the video in the launcher's private `files/screensaver` folder, with sound.
 * Put there by `tv.sh screensaver <video>`, which also selects this as the TV's screensaver.
 * Started by the system after the idle timeout; any remote key ends it. Player exists only while dreaming.
 */
@OptIn(UnstableApi::class)
class VideoDream : DreamService() {
    private var player: ExoPlayer? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false   // any key press wakes the TV back to what it was showing
        isFullscreen = true
        isScreenBright = true

        val video = getExternalFilesDir("screensaver")?.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in setOf("mp4", "m4v", "mkv", "mov", "webm") }
            .minByOrNull { it.name }
        if (video == null) { finish(); return }

        val view = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            setShutterBackgroundColor(Color.BLACK)
            setBackgroundColor(Color.BLACK)
        }
        setContentView(view)
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(video)))
                repeatMode = Player.REPEAT_MODE_ONE
                prepare()
            }
        view.player = player
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        player?.play()
    }

    override fun onDreamingStopped() {
        player?.pause()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        player?.release()
        player = null
        super.onDetachedFromWindow()
    }
}
