package com.ashish.atvlauncher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private lateinit var repo: AppRepository
    private val reloadTick = mutableIntStateOf(0)
    private val visible = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = AppRepository(applicationContext)
        // Back on the home screen does nothing (except closing All apps, handled in Compose).
        onBackPressedDispatcher.addCallback(this) { }
        setContent { HomeScreen(repo, reloadTick.intValue, visible.value) }
    }

    // Wallpaper rotation runs only while Home is on screen.
    override fun onStart() { super.onStart(); visible.value = true }
    override fun onStop() { super.onStop(); visible.value = false }

    override fun onResume() {
        super.onResume()
        // Only rescan apps if something was installed/removed — keeps Home instant.
        if (repo.changedSinceLastLoad()) reloadTick.intValue++
    }
}

internal val Bg = Color(0xFF0B0C10)
internal val Dim = Color(0xFF9AA0A6)
private val CardBg = Color(0xFF1C1F26)

// Sized for the TV's 960x540dp screen. Side margin 48dp (TV overscan-safe) = Outer + Inner;
// Inner is room for a focused card to zoom without being clipped.
private val Outer = 38.dp
private val Inner = 10.dp
private val Gap = 16.dp
private val FavWidth = 106.dp   // Watch row
private val GridWidth = 92.dp   // All apps; GRID_COLUMNS * 92 + gaps = 848dp of the 864dp available
private const val GRID_COLUMNS = 8
private val HeaderSpace = 96.dp // the open All apps panel stops below the clock

@Composable
fun HomeScreen(repo: AppRepository, reloadTick: Int, visible: Boolean) {
    var all by remember { mutableStateOf<List<TvApp>>(emptyList()) }
    var pinned by remember { mutableStateOf<List<String>>(emptyList()) }
    var showAll by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    val appsButton = remember { FocusRequester() }

    LaunchedEffect(reloadTick) {
        val apps = withContext(Dispatchers.IO) { repo.loadAll() }
        all = apps
        pinned = repo.pinned(apps).filter { p -> apps.any { it.pkg == p } }
        loaded = true
    }
    // Start on the first Watch app. LazyRow composes its cards during layout (after this effect
    // runs), so retry for a few frames until the card exists.
    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        repeat(10) {
            if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(30)
        }
    }
    // Back closes All apps; focus goes back to its button so it isn't lost with the grid.
    BackHandler(enabled = showAll) {
        runCatching { appsButton.requestFocus() }
        showAll = false
    }

    val byPkg = remember(all) { all.associateBy { it.pkg } }
    val favApps = pinned.mapNotNull { byPkg[it] }
    val others = all.filter { it.pkg !in pinned }

    fun pin(pkg: String) { pinned = pinned + pkg; repo.savePinned(pinned) }
    fun unpin(pkg: String) { pinned = pinned - pkg; repo.savePinned(pinned) }

    // Extra darkening behind the open All apps panel, so the grid reads well over any photo.
    val dim by animateFloatAsState(if (showAll) 0.55f else 0f, tween(280), label = "dim")

    MaterialTheme(colorScheme = darkColorScheme()) {
      Box(Modifier.fillMaxSize().background(Bg)) {
        Wallpaper(visible)
        if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        Header(Modifier.padding(start = Outer, top = 28.dp))

        // Nothing focusable until apps are loaded, so initial focus can't land on the wrong card.
        if (loaded) {
            // Docked at the bottom; opening All apps grows it upward (animated) to show the grid.
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .animateContentSize(tween(280))
                    .then(if (showAll) Modifier.fillMaxHeight().padding(top = HeaderSpace) else Modifier)
                    .verticalScroll(rememberScrollState())
                    .padding(start = Outer, end = Outer, bottom = 24.dp)
            ) {
                SectionTitle("Watch", "hold OK to unpin")
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(Gap),
                    contentPadding = PaddingValues(horizontal = Inner, vertical = 10.dp)
                ) {
                    items(favApps, key = { it.pkg }) { app ->
                        val mod = if (app == favApps.first()) Modifier.focusRequester(firstFocus) else Modifier
                        AppCard(app, FavWidth, mod, onClick = { repo.launch(app.pkg) }, onLongClick = { unpin(app.pkg) })
                    }
                }

                Row(
                    Modifier.padding(horizontal = Inner, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // USB shortcut hidden: the TV's own "Player" app (in All apps) handles USB media.
                    val appsMod = Modifier.focusRequester(appsButton)
                        .then(if (favApps.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier)
                    Shortcut(if (showAll) "Hide apps" else "All apps", Icons.apps, appsMod) { showAll = !showAll }
                    Shortcut("Settings", Icons.settings) { repo.openSettings() }
                }

                if (showAll) {
                    Spacer(Modifier.height(16.dp))
                    SectionTitle("All apps", "hold OK to pin to Watch")
                    Column(
                        Modifier.padding(horizontal = Inner, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(Gap)
                    ) {
                        others.chunked(GRID_COLUMNS).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(Gap)) {
                                row.forEach { app ->
                                    key(app.pkg) {
                                        AppCard(app, GridWidth, onClick = { repo.launch(app.pkg) }, onLongClick = { pin(app.pkg) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
      }
    }
}

private val Legible = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), blurRadius = 8f)

@Composable
private fun Header(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) { now = Date(); delay(15_000) }
    }
    val time = remember(now) { SimpleDateFormat("h:mm", Locale.getDefault()).format(now) }
    val date = remember(now) { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now) }
    Row(modifier.padding(horizontal = Inner), verticalAlignment = Alignment.Bottom) {
        Text(
            time, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Light,
            style = TextStyle(shadow = Legible)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            date, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp,
            style = TextStyle(shadow = Legible), modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}

@Composable
private fun SectionTitle(title: String, hint: String) {
    Row(Modifier.padding(horizontal = Inner), verticalAlignment = Alignment.Bottom) {
        Text(
            title.uppercase(), color = Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp
        )
        Spacer(Modifier.width(10.dp))
        Text(hint, color = Dim.copy(alpha = 0.7f), fontSize = 11.sp)
    }
}

private val CardShape = RoundedCornerShape(10.dp)

/** 16:9 app card: the app's TV banner, or its icon + name if it has no banner. */
@Composable
private fun AppCard(
    app: TvApp, width: Dp, modifier: Modifier = Modifier,
    onClick: () -> Unit, onLongClick: () -> Unit,
) {
    val height = width * 9f / 16f
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.size(width = width, height = height),
        shape = CardDefaults.shape(CardShape),
        scale = CardDefaults.scale(focusedScale = 1.1f),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = CardShape)
        ),
        colors = CardDefaults.colors(containerColor = CardBg),
    ) {
        if (app.isBanner) {
            Image(app.image, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Column(
                Modifier.fillMaxSize().padding(5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(app.image, app.label, Modifier.size(height * 0.42f))
                Spacer(Modifier.height(3.dp))
                Text(
                    app.label, color = Color.White, fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Slim pill button: translucent over the wallpaper, solid white when focused. */
@Composable
private fun Shortcut(label: String, icon: ImageVector?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(42.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.14f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF111318),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Row(
            Modifier.fillMaxHeight().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(9.dp))
            }
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Material icons as inline vector paths — no icon library needed. */
private object Icons {
    val apps by lazy {
        path("M4 8h4V4H4v4zm6 12h4v-4h-4v4zm-6 0h4v-4H4v4zm0-6h4v-4H4v4zm6 0h4v-4h-4v4zm6-10v4h4V4h-4zm-6 4h4V4h-4v4zm6 6h4v-4h-4v4zm0 6h4v-4h-4v4z")
    }
    val settings by lazy {
        path("M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z")
    }

    // A malformed path just means no icon, never a crash on the home screen.
    private fun path(d: String): ImageVector? = runCatching {
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(d), fill = SolidColor(Color.White))
            .build()
    }.getOrNull()
}
