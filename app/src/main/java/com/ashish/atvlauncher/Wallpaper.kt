package com.ashish.atvlauncher

import android.graphics.ImageDecoder
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** How often the Home background photo changes. */
private const val ROTATE_MS = 5 * 60_000L
private val PHOTO_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")

/** Light at the centre so the photo shows; darker at the top (clock) and bottom (app dock). */
private val Scrim = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.45f),
    0.3f to Color.Black.copy(alpha = 0.08f),
    0.6f to Color.Black.copy(alpha = 0.15f),
    1f to Color.Black.copy(alpha = 0.8f),
)

/**
 * Photo background for Home, read from the launcher's private folder
 * (/sdcard/Android/data/com.ashish.atvlauncher/files/wallpapers, filled by `tv.sh wallpapers`).
 * Changes every [ROTATE_MS], and only while Home is on screen — nothing runs while you watch.
 * One screen-sized bitmap in memory (two for the ~1 s crossfade). Empty folder = plain background.
 */
@Composable
fun Wallpaper(visible: Boolean) {
    val ctx = LocalContext.current
    val dm = ctx.resources.displayMetrics
    var current by remember { mutableStateOf<Pair<String, ImageBitmap>?>(null) }

    LaunchedEffect(visible) {
        while (visible) {
            // Slot from the wall clock: no saved state, and coming back to Home after
            // 5+ minutes shows the next photo.
            val slot = System.currentTimeMillis() / ROTATE_MS
            current = withContext(Dispatchers.IO) {
                val photos = ctx.getExternalFilesDir("wallpapers")?.listFiles().orEmpty()
                    .filter { it.isFile && it.extension.lowercase() in PHOTO_EXT }
                    .sortedBy { it.name }
                val f = photos.getOrNull((slot % photos.size.coerceAtLeast(1)).toInt())
                when {
                    f == null -> null
                    f.path == current?.first -> current
                    else -> decodeScaled(f, dm.widthPixels, dm.heightPixels, fill = true)?.let { f.path to it }
                }
            }
            delay((slot + 1) * ROTATE_MS - System.currentTimeMillis())
        }
    }

    Crossfade(targetState = current, animationSpec = tween(1200), label = "wallpaper") { w ->
        if (w != null) {
            Box(Modifier.fillMaxSize()) {
                Image(w.second, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Scrim))
            }
        }
    }
}

/**
 * Decodes at screen size instead of full camera resolution (a 12 MP photo would be ~48 MB).
 * fill = cover the screen (wallpaper, cropped); otherwise fit inside it (photo viewer).
 * ImageDecoder also applies EXIF rotation and handles HEIC.
 */
internal fun decodeScaled(f: File, w: Int, h: Int, fill: Boolean): ImageBitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, info, _ ->
        val iw = info.size.width
        val ih = info.size.height
        val sx = w.toFloat() / iw
        val sy = h.toFloat() / ih
        val s = minOf(1f, if (fill) maxOf(sx, sy) else minOf(sx, sy))
        if (s < 1f) d.setTargetSize((iw * s).toInt().coerceAtLeast(1), (ih * s).toInt().coerceAtLeast(1))
    }.asImageBitmap()
}.getOrNull()
