package com.ashish.atvlauncher

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Full-screen photo viewer. Left/Right = previous/next, OK = start/stop slideshow, Back = folder. */
class ImageActivity : ComponentActivity() {
    private lateinit var files: List<File>
    private val index = mutableIntStateOf(0)
    private val slideshow = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        files = intent.getStringArrayExtra(EXTRA_PATHS).orEmpty().map(::File)
        if (files.isEmpty()) { finish(); return }
        index.intValue = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(files.indices)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dm = resources.displayMetrics
        setContent {
            val i = index.intValue
            if (slideshow.value) LaunchedEffect(i) { delay(5_000); step(1) }
            Viewer(files[i], "${i + 1} / ${files.size}", slideshow.value, dm.widthPixels, dm.heightPixels)
        }
    }

    private fun step(by: Int) { index.intValue = Math.floorMod(index.intValue + by, files.size) }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action: (() -> Unit)? = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { { step(1) } }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> { { step(-1) } }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                { { slideshow.value = !slideshow.value } }
            else -> null
        }
        if (action == null || !::files.isInitialized) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) action()
        return true
    }
}

@Composable
private fun Viewer(file: File, position: String, slideshow: Boolean, maxW: Int, maxH: Int) {
    // Keeps showing the previous photo until the next one is decoded (no black flash).
    val loaded by produceState<Pair<File, ImageBitmap?>?>(null, file) {
        value = file to withContext(Dispatchers.IO) { decodeScaled(file, maxW, maxH, fill = false) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val l = loaded
        when {
            l == null -> Text("Loading…", color = Dim)
            l.second == null && l.first == file -> Text("Can't open ${file.name}", color = Dim)
            l.second != null -> Image(l.second!!, file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        if (!slideshow) {
            Text(
                "${file.name}   ·   $position   ·   ◀ ▶ browse   ·   OK slideshow",
                color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}
