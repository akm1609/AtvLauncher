package com.ashish.atvlauncher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

const val EXTRA_PATHS = "paths"
const val EXTRA_INDEX = "index"
const val EXTRA_AUDIO = "audio"

enum class Kind(val icon: String) { DRIVE("💾"), FOLDER("📁"), VIDEO("🎬"), AUDIO("🎵"), IMAGE("🖼") }

data class Entry(val file: File, val name: String, val kind: Kind, val detail: String = "")

/** USB drive discovery + folder listing. Plain java.io.File: the TV mounts USB at /storage/<id>. */
object Media {
    private val VIDEO = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m2ts", "mts", "3gp", "flv")
    private val AUDIO = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "amr")
    private val IMAGE = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

    private fun kindOf(f: File): Kind? = when (f.extension.lowercase()) {
        in VIDEO -> Kind.VIDEO
        in AUDIO -> Kind.AUDIO
        in IMAGE -> Kind.IMAGE
        else -> null
    }

    fun hasAccess(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) ||
            ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun drives(ctx: Context): List<Entry> {
        val sm = ctx.getSystemService(StorageManager::class.java)
        val found = sm.storageVolumes
            .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
            .mapNotNull { v ->
                val dir = if (Build.VERSION.SDK_INT >= 30) v.directory else v.uuid?.let { File("/storage/$it") }
                dir?.let { Entry(it, v.getDescription(ctx), Kind.DRIVE) }
            }
        if (found.isNotEmpty()) return found
        return File("/storage").listFiles().orEmpty()
            .filter { it.isDirectory && it.name != "emulated" && it.name != "self" }
            .map { Entry(it, it.name, Kind.DRIVE) }
    }

    fun list(dir: File): List<Entry> {
        val files = dir.listFiles().orEmpty().filterNot { it.name.startsWith(".") }
        val folders = files.filter { it.isDirectory }
            .map { Entry(it, it.name, Kind.FOLDER) }
            .sortedWith(compareBy(NATURAL) { it.name })
        val media = files.filter { it.isFile }
            .mapNotNull { f -> kindOf(f)?.let { Entry(f, f.name, it, size(f.length())) } }
            .sortedWith(compareBy(NATURAL) { it.name })
        return folders + media
    }

    private fun size(b: Long) = when {
        b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
        b >= 1L shl 20 -> "%.0f MB".format(b / 1048576.0)
        else -> "${b / 1024} KB"
    }

    /** "Episode 2" sorts before "Episode 10". */
    private val CHUNK = Regex("\\d+|\\D+")
    private val NATURAL = Comparator<String> { a, b ->
        val x = CHUNK.findAll(a.lowercase()).map { it.value }.iterator()
        val y = CHUNK.findAll(b.lowercase()).map { it.value }.iterator()
        while (x.hasNext() && y.hasNext()) {
            val p = x.next()
            val q = y.next()
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                val pn = p.trimStart('0')
                val qn = q.trimStart('0')
                if (pn.length != qn.length) pn.length.compareTo(qn.length) else pn.compareTo(qn)
            } else p.compareTo(q)
            if (c != 0) return@Comparator c
        }
        x.hasNext().compareTo(y.hasNext())
    }
}

/** USB browser: drives -> folders -> photos / music / videos. Back goes up a folder. */
class MediaActivity : ComponentActivity() {
    private val access = mutableStateOf(false)
    private val askPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { access.value = Media.hasAccess(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        access.value = Media.hasAccess(this)
        if (!access.value) askPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        setContent { MediaBrowser(access.value, ::open, ::finish) }
    }

    override fun onResume() {
        super.onResume()
        access.value = Media.hasAccess(this)
    }

    /** Opens the clicked file with every file of the same kind in that folder as the playlist. */
    private fun open(list: List<Entry>, e: Entry) {
        val same = list.filter { it.kind == e.kind }
        val cls = if (e.kind == Kind.IMAGE) ImageActivity::class.java else PlayerActivity::class.java
        startActivity(
            Intent(this, cls)
                .putExtra(EXTRA_PATHS, same.map { it.file.path }.toTypedArray())
                .putExtra(EXTRA_INDEX, same.indexOf(e))
                .putExtra(EXTRA_AUDIO, e.kind == Kind.AUDIO)
        )
    }
}

@Composable
private fun MediaBrowser(hasAccess: Boolean, onOpen: (List<Entry>, Entry) -> Unit, onExit: () -> Unit) {
    val ctx = LocalContext.current
    var dir by remember { mutableStateOf<File?>(null) }
    var entries by remember { mutableStateOf<List<Entry>?>(null) }
    var drives by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var returnTo by remember { mutableStateOf<String?>(null) } // re-focus this path after going up
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(dir, hasAccess, reload) {
        if (!hasAccess) return@LaunchedEffect
        entries = null
        val d = dir
        val list = withContext(Dispatchers.IO) { if (d == null) Media.drives(ctx) else Media.list(d) }
        if (d == null) {
            drives = list
            // Only one drive plugged in: skip the drive picker.
            if (list.size == 1 && returnTo == null) { dir = list[0].file; return@LaunchedEffect }
        }
        entries = list
    }

    BackHandler {
        val d = dir
        when {
            d == null -> onExit()
            drives.any { it.file == d } -> if (drives.size <= 1) onExit() else { returnTo = d.path; dir = null }
            else -> { returnTo = d.path; dir = d.parentFile }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Bg)
                .padding(horizontal = 48.dp, vertical = 32.dp)
        ) {
            Text("USB", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Light)
            Text(
                dir?.path ?: "Choose a drive", color = Dim, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(16.dp))
            val list = entries
            when {
                !hasAccess -> Message("Storage permission is needed to read USB drives.\nAllow it in Settings › Apps › Home › Permissions.")
                list == null -> Message("Loading…")
                list.isEmpty() && dir == null -> {
                    Message("No USB drive found. Plug one in, then press OK.")
                    EntryList(listOf(Entry(File("/"), "Look again", Kind.DRIVE)), null) { reload++ }
                }
                list.isEmpty() -> Message("No photos, music or videos in this folder.")
                else -> EntryList(list, returnTo) { e ->
                    when (e.kind) {
                        Kind.DRIVE, Kind.FOLDER -> { returnTo = null; dir = e.file }
                        else -> onOpen(list, e)
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text, color = Dim, fontSize = 16.sp, modifier = Modifier.padding(vertical = 12.dp))
}

@Composable
private fun EntryList(list: List<Entry>, focusPath: String?, onClick: (Entry) -> Unit) {
    val state = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val target = list.indexOfFirst { it.file.path == focusPath }.coerceAtLeast(0)

    LaunchedEffect(list) {
        state.scrollToItem(target)
        // The target row may need a frame or two to be composed before it can take focus.
        repeat(10) {
            if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(30)
        }
    }

    LazyColumn(
        state = state,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        itemsIndexed(list, key = { _, e -> e.file.path }) { i, e ->
            ListItem(
                selected = false,
                onClick = { onClick(e) },
                modifier = if (i == target) Modifier.focusRequester(focus) else Modifier,
                leadingContent = { Text(e.kind.icon, fontSize = 20.sp) },
                headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = { if (e.detail.isNotEmpty()) Text(e.detail, fontSize = 13.sp) },
            )
        }
    }
}
