package com.ashish.atvlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

data class TvApp(
    val pkg: String,
    val label: String,
    val image: ImageBitmap,
    val isBanner: Boolean,
)

/** The apps Ashish wants on the home row, in this order. Matched by package first, then by name. */
private data class Fav(val packages: List<String>, val keywords: List<String>)

private const val SMARTTUBE = "org.smarttube.stable" // ad-free YouTube (older builds: com.liskovsoft.smarttubetv)

private val DEFAULT_FAVS = listOf(
    Fav(listOf("com.amazon.amazonvideo.livingroom"), listOf("primevideo", "amazonprime")),
    Fav(listOf(SMARTTUBE, "com.liskovsoft.smarttubetv"), listOf("smarttube")), // official YouTube stays in All apps
    Fav(listOf("in.startv.hotstar", "in.startv.hotstar.dplus.tv"), listOf("hotstar")),
    Fav(listOf("tv.airtel.xstream.tvapp", "tv.accedo.airtel.wynk"), listOf("xstream")),
)

/** Bump when an existing install's saved pins should pick up [SWAPS]. */
private const val FAVS_VERSION = 2

/** One-time swaps applied to saved pins on upgrade (other custom pins are kept). */
private val SWAPS = mapOf("com.google.android.youtube.tv" to SMARTTUBE)

/** Never show these: ourselves, and the vendor's engineering/factory menus (easy to open by mistake). */
private val HIDDEN = setOf(
    "com.ashish.atvlauncher",
    "com.rtk.engmenu",
    "com.apps.factory.ui",
    "com.realtek.factorytools",
)

class AppRepository(private val context: Context) {
    private val pm: PackageManager = context.packageManager
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)
    private var seq = 0

    /** True if anything was installed/removed/updated since the last load. */
    fun changedSinceLastLoad(): Boolean =
        pm.getChangedPackages(seq) != null

    fun loadAll(): List<TvApp> {
        seq = pm.getChangedPackages(0)?.sequenceNumber ?: 0
        val tv = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0
        )
        val phone = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        val seen = HashSet<String>()
        val out = ArrayList<TvApp>()
        for (ri in tv + phone) {
            val pkg = ri.activityInfo.packageName
            if (pkg in HIDDEN || !seen.add(pkg)) continue
            val ai = ri.activityInfo.applicationInfo
            val banner: Drawable? = ri.activityInfo.loadBanner(pm) ?: ai.loadBanner(pm)
            val drawable = banner ?: ri.loadIcon(pm)
            val bmp = if (banner != null) drawable.toBitmap(320, 180) else drawable.toBitmap(144, 144)
            out += TvApp(pkg, ri.loadLabel(pm).toString(), bmp.asImageBitmap(), banner != null)
        }
        return out.sortedBy { it.label.lowercase() }
    }

    /** Pinned package order; seeded from DEFAULT_FAVS on first run. */
    fun pinned(all: List<TvApp>): List<String> {
        prefs.getString("pinned", null)?.let { s ->
            var list = s.split(',').filter { it.isNotBlank() }
            if (prefs.getInt("favsVersion", 1) < FAVS_VERSION) {
                val installed = all.mapTo(HashSet()) { it.pkg }
                // Wait to mark done until every swap target is installed (e.g. SmartTube sideloaded later).
                val pending = list.any { p -> SWAPS[p]?.let { it !in installed } == true }
                list = list.map { p -> SWAPS[p]?.takeIf { it in installed } ?: p }.distinct()
                savePinned(list)
                if (!pending) prefs.edit().putInt("favsVersion", FAVS_VERSION).apply()
            }
            return list
        }
        val seeded = DEFAULT_FAVS.mapNotNull { fav -> match(fav, all)?.pkg }.distinct()
        savePinned(seeded)
        prefs.edit().putInt("favsVersion", FAVS_VERSION).apply()
        return seeded
    }

    fun savePinned(list: List<String>) {
        prefs.edit().putString("pinned", list.joinToString(",")).apply()
    }

    fun resetPinned() { prefs.edit().remove("pinned").apply() }

    private fun match(fav: Fav, all: List<TvApp>): TvApp? {
        fav.packages.forEach { p -> all.firstOrNull { it.pkg == p }?.let { return it } }
        return all.firstOrNull { app ->
            val n = app.label.lowercase().filter { it.isLetterOrDigit() }
            fav.keywords.any { n.contains(it) } && !n.contains("music") && !n.contains("kids")
        }
    }

    fun launch(pkg: String) {
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }

    fun openUsb() {
        context.startActivity(Intent(context, MediaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openSettings() {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
