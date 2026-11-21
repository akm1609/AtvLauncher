#!/usr/bin/env bash
# Acer ATV R2 (Android TV 11) — debloat, speed up, install custom launcher.
# Every change is REVERSIBLE: packages are disabled (pm disable-user), never uninstalled.
#
#   ./scripts/tv.sh connect 192.168.1.50   # TV IP from Settings > Network
#   ./scripts/tv.sh audit                  # RAM, storage, packages (read-only)
#   ./scripts/tv.sh debloat                # disable ad/recommendation + junk apps
#   ./scripts/tv.sh speed                  # animations, caches, app optimisation
#   ./scripts/tv.sh install                # install the launcher APK
#   ./scripts/tv.sh sethome                # make it Home + disable stock launcher (ads gone)
#   ./scripts/tv.sh wallpapers ~/Pictures/TV   # Home background photos (rotate every 5 min)
#   ./scripts/tv.sh screensaver clip.mov       # loop a video (with sound) as the screensaver
#   ./scripts/tv.sh restore                # undo everything
set -u
cd "$(dirname "$0")/.."
LOG=scripts/disabled-packages.txt
SELF=com.ashish.atvlauncher

# Safe to disable on Android TV 11. Nothing here is needed for your 7 apps,
# the remote, voice search, Play Store, or Chromecast.
BLOAT="
com.google.android.tvrecommendations
com.google.android.leanbacklauncher.recommendations
com.google.android.play.games
com.google.android.videos
com.google.android.music
com.google.android.youtube.tvmusic
com.google.android.backdrop
com.google.android.tvtutorials
com.google.android.feedback
com.android.printspooler
com.android.dreams.basic
com.android.dreams.phototable
"

a() { adb shell "$@" 2>&1 | tr -d '\r'; }
installed() { a pm list packages | grep -qx "package:$1"; }

disable() {
  if installed "$1"; then
    if a pm disable-user --user 0 "$1" | grep -q "disabled"; then
      echo "  disabled  $1"; echo "$1" >> "$LOG"
    else
      echo "  skipped   $1 (system refused)"
    fi
  fi
}

case "${1:-}" in
  connect)
    [ -z "${2:-}" ] && { echo "usage: tv.sh connect <TV-IP>"; exit 1; }
    adb connect "$2:5555"
    echo "Accept the 'Allow USB debugging?' prompt on the TV (tick Always allow), then run: ./scripts/tv.sh audit"
    ;;

  audit)
    echo "== Device";  a getprop ro.product.model; a getprop ro.build.version.release
    echo "== RAM";     a cat /proc/meminfo | grep -E "MemTotal|MemAvailable"
    echo "== Storage (low free space is the #1 cause of a slow TV)"; a df -h /data
    echo "== Top memory users"; a dumpsys meminfo | sed -n '/Total PSS by process/,/Total PSS by OOM/p' | head -25
    echo "== Third-party apps"; a pm list packages -3 | sed 's/package:/  /' | sort
    echo "== Vendor/system apps that are not Google/Android (review for bloat)"
    a pm list packages -s | sed 's/package:/  /' | grep -vE "^  (com\.google|com\.android|android)" | sort
    ;;

  debloat)
    echo "Disabling bloat (logged to $LOG for restore)..."
    for p in $BLOAT; do disable "$p"; done
    echo "Done. Extra vendor junk from 'audit'? Run: adb shell pm disable-user --user 0 <pkg>  and add it to $LOG"
    ;;

  speed)
    echo "Animations -> 0.5x (snappier, still smooth)"
    a settings put global window_animation_scale 0.5
    a settings put global transition_animation_scale 0.5
    a settings put global animator_duration_scale 0.5
    echo "Trimming app caches..."; a pm trim-caches 999G
    echo "Re-optimising installed apps (can take 5-10 min, TV stays usable)..."
    a cmd package bg-dexopt-job
    echo "Free storage now:"; a df -h /data
    echo "Tip: reboot the TV once after this: adb reboot"
    ;;

  install)
    APK=app/build/outputs/apk/release/app-release.apk
    [ -f "$APK" ] || APK=app/build/outputs/apk/debug/app-debug.apk
    [ -f "$APK" ] || { echo "Build first: ./gradlew assembleRelease"; exit 1; }
    adb install -r "$APK" || exit 1
    # USB photos/music/videos: grant storage access now so the TV never prompts.
    a pm grant "$SELF" android.permission.READ_EXTERNAL_STORAGE
    a appops set --uid "$SELF" MANAGE_EXTERNAL_STORAGE allow
    echo "Installed. Press Home on the remote and pick 'Home' to test before sethome."
    ;;

  sethome)
    installed "$SELF" || { echo "Install the launcher first."; exit 1; }
    a cmd package set-home-activity "$SELF/.MainActivity"
    disable com.google.android.tvlauncher         # stock Android TV home (ad rows)
    disable com.google.android.leanbacklauncher
    a am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
    echo "Custom Home is now default. If anything breaks: ./scripts/tv.sh restore"
    ;;

  wallpapers)
    # Resize on the Mac (1920 px wide is plenty for the TV and keeps RAM low), then copy into
    # the launcher's private folder. Re-run with another folder to replace the set.
    SRC="${2:-}"
    [ -d "$SRC" ] || { echo "usage: tv.sh wallpapers <folder-of-photos>"; exit 1; }
    installed "$SELF" || { echo "Install the launcher first."; exit 1; }
    DEST=/sdcard/Android/data/$SELF/files/wallpapers
    # Let the launcher create its own folder first, so ownership/permissions are its own.
    if ! a "ls -d $DEST" | grep -q "^$DEST\$"; then
      a am start -n "$SELF/.MainActivity" >/dev/null; sleep 3
    fi
    TMP=$(mktemp -d); n=0
    for f in "$SRC"/*; do
      ext=$(echo "${f##*.}" | tr '[:upper:]' '[:lower:]')
      case "$ext" in jpg|jpeg|png|heic|webp) ;; *) continue ;; esac
      out="$TMP/$(printf 'wall-%02d.jpg' $((n + 1)))"
      sips -s format jpeg -s formatOptions 85 --resampleWidth 1920 "$f" --out "$out" >/dev/null 2>&1 && n=$((n + 1))
    done
    [ "$n" -gt 0 ] || { echo "No photos (jpg/png/heic/webp) found in $SRC"; rm -rf "$TMP"; exit 1; }
    a "rm -f $DEST/wall-*.jpg"
    adb push "$TMP"/. "$DEST"/ >/dev/null && echo "Pushed $n photo(s) ($(du -sh "$TMP" | cut -f1)). They show next time Home opens."
    rm -rf "$TMP"
    ;;

  screensaver)
    # Re-encode on the Mac for the TV (1080p30 H.264 + stereo AAC, phone metadata/extra tracks
    # dropped, tiny audio fades so the loop doesn't click), then select our VideoDream.
    SRC="${2:-}"
    [ -f "$SRC" ] || { echo "usage: tv.sh screensaver <video-file>"; exit 1; }
    installed "$SELF" || { echo "Install the launcher first."; exit 1; }
    command -v ffmpeg >/dev/null || { echo "Needs ffmpeg: brew install ffmpeg"; exit 1; }
    DEST=/sdcard/Android/data/$SELF/files/screensaver
    TMP=$(mktemp -d); OUT="$TMP/screensaver.mp4"
    DUR=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$SRC")
    FADE_AT=$(awk -v d="$DUR" 'BEGIN { printf "%.3f", d - 0.05 }')
    echo "Optimising $(basename "$SRC") ($(du -h "$SRC" | cut -f1))..."
    ffmpeg -v error -y -i "$SRC" -map 0:v:0 -map 0:a:0 \
      -vf "scale='min(1920,iw)':-2,fps=30" -c:v libx264 -profile:v high -preset slow -crf 21 \
      -maxrate 8M -bufsize 16M -pix_fmt yuv420p \
      -c:a aac -b:a 160k -ac 2 -af "afade=t=in:d=0.05,afade=t=out:st=$FADE_AT:d=0.05" \
      -map_metadata -1 -movflags +faststart "$OUT" || { rm -rf "$TMP"; exit 1; }
    a mkdir -p "$DEST"; a "rm -f $DEST/*"
    adb push "$OUT" "$DEST/screensaver.mp4" >/dev/null || { rm -rf "$TMP"; exit 1; }
    # Remember the stock screensaver once, so `restore` can put it back.
    [ -f scripts/screensaver-original.txt ] || a settings get secure screensaver_components > scripts/screensaver-original.txt
    a settings put secure screensaver_components "$SELF/.VideoDream"
    a settings put secure screensaver_enabled 1
    START=$(a settings get system screen_off_timeout); SLEEP=$(a settings get secure sleep_timeout)
    echo "Screensaver set ($(du -h "$OUT" | cut -f1)): starts after $((START / 60000)) min idle; TV sleeps after $((SLEEP / 60000)) min."
    echo "Change timings on the TV: Settings > Device Preferences > Screen saver."
    rm -rf "$TMP"
    ;;

  restore)
    echo "Re-enabling stock launcher and everything we disabled..."
    if [ -f scripts/screensaver-original.txt ]; then
      a settings put secure screensaver_components "$(cat scripts/screensaver-original.txt)"
      rm -f scripts/screensaver-original.txt; echo "  screensaver restored"
    fi
    for p in com.google.android.tvlauncher com.google.android.leanbacklauncher; do
      installed "$p" && a pm enable "$p" >/dev/null && echo "  enabled $p"
    done
    if [ -f "$LOG" ]; then
      sort -u "$LOG" | while read -r p; do [ -n "$p" ] && a pm enable "$p" >/dev/null && echo "  enabled $p"; done
      rm -f "$LOG"
    fi
    a settings put global window_animation_scale 1
    a settings put global transition_animation_scale 1
    a settings put global animator_duration_scale 1
    echo "Restored. Optionally remove the launcher: adb uninstall $SELF"
    ;;

  *) awk 'NR > 1 && /^set -u/ { exit } NR > 1' "$0" ;;
esac
