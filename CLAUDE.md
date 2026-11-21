# AtvLauncher — custom ad-free Home for Ashish's Acer ATV R2 (Android TV 11, API 30)

Goal: TV was fast, now slow + apps crashing. Fix by (1) debloating over ADB, (2) replacing the
stock ad-heavy launcher with this minimal Kotlin + Compose for TV launcher.

## Apps to pin (order)
Prime Video, SmartTube, JioHotstar, Airtel Xstream (+ All apps, Settings pills).
SmartTube (`org.smarttube.stable`, ad-free YouTube, sideloaded from github.com/yuliskov/SmartTube
releases, armeabi-v7a build, verify the asset's SHA-256 + signer "CN=Yurii L, O=SmartTube") replaces YouTube in the row; official YouTube
stays installed (needed for casting) in All apps. Saved pins migrate via `FAVS_VERSION`/`SWAPS`.
USB shortcut is hidden at Ashish's request — the TV's own Player app (com.apps.usbmediaplayer) handles USB.
On this TV Airtel Xstream is `tv.airtel.xstream.tvapp`.
Matching lives in `AppRepository.kt` (`DEFAULT_FAVS`, package first then label keyword).
If one doesn't appear, check the real package with `adb shell pm list packages | grep -i <name>`
and fix the list. Pins persist in SharedPreferences; long-press to pin/unpin.

## Workflow for Claude Code
1. Wrapper is pinned to Gradle 8.14.3 (`./gradlew`). Needs Android SDK
   (ANDROID_HOME or local.properties `sdk.dir=/Users/<me>/Library/Android/sdk`).
2. `./scripts/tv.sh connect <TV-IP>` → `audit` → review output with Ashish.
3. `./scripts/tv.sh debloat` and `speed`. Disable extra vendor bloat only after confirming.
4. `./gradlew assembleRelease` → `./scripts/tv.sh install` → test → `./scripts/tv.sh sethome`.
5. Debug crashes: `adb logcat -b crash` / `adb logcat | grep -i atvlauncher`.

## Rules
- Never `pm uninstall`; only `pm disable-user --user 0` and log to scripts/disabled-packages.txt.
- Don't disable: com.android.vending, com.google.android.gms, com.google.android.katniss (voice),
  com.google.android.tv.remote.service, com.google.android.apps.mediashell (Chromecast),
  com.android.tv.settings, anything with "inputmethod"/"keyboard".
- `./scripts/tv.sh restore` must always work as the escape hatch.
- Keep the launcher tiny: no network, no images beyond app banners, no background work.
  Exception: USB media (MediaActivity/PlayerActivity/ImageActivity, Media3) lives in separate
  activities in their own task and loads only when the USB tile is opened — keep Home free of it.
  Exception: Home wallpaper (Wallpaper.kt) — one screen-sized photo from the app's private
  `files/wallpapers` dir, rotates every 5 min only while Home is visible. `tv.sh wallpapers <dir>`.
  Exception: screensaver (VideoDream.kt, a DreamService) loops `files/screensaver/*.mp4` with sound,
  only while the system is dreaming. `tv.sh screensaver <video>`; original component saved for restore.
- Hidden from All apps: vendor engineering/factory menus (com.rtk.engmenu etc.) — never launch them.
- Home layout: clock top-left; Watch row + pills docked at the bottom; All apps grows the dock upward.
