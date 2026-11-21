package com.ashish.atvlauncher

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

/**
 * Plays USB video/audio with ExoPlayer (hardware decoders, mkv/mp4/mp3...).
 * The rest of the folder is queued, so the next episode / song plays automatically.
 * If the TV can't decode a file, it is handed to another installed player when one exists.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var view: PlayerView
    private lateinit var title: TextView
    private lateinit var files: List<File>
    private var player: ExoPlayer? = null
    private var index = 0
    private var position = 0L
    private var warnedIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        files = intent.getStringArrayExtra(EXTRA_PATHS).orEmpty().map(::File)
        if (files.isEmpty()) { finish(); return }
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(files.indices)
        val audio = intent.getBooleanExtra(EXTRA_AUDIO, false)
        val px = resources.displayMetrics.density

        view = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
            if (audio) {
                // Music: keep the controls (and song title) on screen.
                setControllerShowTimeoutMs(0)
                setControllerHideOnTouch(false)
            }
        }
        title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
            setPadding((48 * px).toInt(), (32 * px).toInt(), (48 * px).toInt(), 0)
        }
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { title.visibility = it })
        setContentView(FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(title, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        })
    }

    override fun onStart() {
        super.onStart()
        val p = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
            .build()
        p.addListener(listener)
        p.setMediaItems(files.map(::item), index, position)
        p.prepare()
        p.playWhenReady = true
        view.player = p
        view.requestFocus()
        player = p
    }

    override fun onStop() {
        super.onStop()
        player?.let {
            index = it.currentMediaItemIndex
            position = it.currentPosition
            it.release()
        }
        player = null
        if (::view.isInitialized) view.player = null
    }

    // Remote: D-pad shows the controls, play/pause/next/prev media keys work directly.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        (::view.isInitialized && view.dispatchKeyEvent(event)) || super.dispatchKeyEvent(event)

    private fun item(f: File) = MediaItem.Builder()
        .setUri(Uri.fromFile(f))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(f.nameWithoutExtension).build())
        .build()

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            title.text = mediaItem?.mediaMetadata?.title
        }

        override fun onTracksChanged(tracks: Tracks) {
            val p = player ?: return
            val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            if (audio.isEmpty() || audio.any { it.isSupported } || warnedIndex == p.currentMediaItemIndex) return
            warnedIndex = p.currentMediaItemIndex
            val codec = audio[0].getTrackFormat(0).sampleMimeType?.substringAfter('/') ?: "this"
            val reason = "This TV can't decode $codec audio"
            if (!openElsewhere(reason)) toast("$reason — playing without sound")
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            val reason = "Can't play ${files[p.currentMediaItemIndex].name} (${error.errorCodeName})"
            if (openElsewhere(reason)) return
            toast(reason)
            if (p.hasNextMediaItem()) { p.seekToNextMediaItem(); p.prepare() } else finish()
        }
    }

    /** Hands the current file to another installed player (e.g. the TV's own USB player). */
    private fun openElsewhere(reason: String): Boolean {
        val file = files.getOrNull(player?.currentMediaItemIndex ?: return false) ?: return false
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: return false
        val uri = runCatching { FileProvider.getUriForFile(this, "$packageName.files", file) }.getOrNull() ?: return false
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val target = packageManager.queryIntentActivities(i, 0)
            .firstOrNull { it.activityInfo.packageName != packageName } ?: return false
        i.setClassName(target.activityInfo.packageName, target.activityInfo.name)
        toast("$reason — opening in ${target.loadLabel(packageManager)}")
        return runCatching { startActivity(i); finish() }.isSuccess
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
