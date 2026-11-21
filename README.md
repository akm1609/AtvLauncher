# AtvLauncher

Ad-free, minimal Home screen for the Acer ATV R2 (Android TV 11).

## 1. Prepare the TV
Settings → Device Preferences → About → click **Build** 7 times.
Then Settings → Device Preferences → Developer options → turn on **USB debugging**
(and **Network debugging** if it's listed). Note the TV IP from Settings → Network.

## 2. Run (from this folder on your Mac)
```bash
brew install --cask android-platform-tools   # if adb is missing
chmod +x scripts/tv.sh
./scripts/tv.sh connect <TV-IP>
./scripts/tv.sh audit
./scripts/tv.sh debloat
./scripts/tv.sh speed
```

## 3. Build + install the launcher
Open the folder in Android Studio → let it sync → Build → Generate APKs (or `./gradlew assembleRelease`).
```bash
./scripts/tv.sh install     # test it: press Home, choose "Home"
./scripts/tv.sh sethome     # make it default + kill stock launcher ads
```

**Undo everything:** `./scripts/tv.sh restore`

Remote controls: long-press an app in *Watch* to unpin it; open *All apps* and long-press to pin.

## USB photos, music and videos
USB media is played with the TV's own **Player** app (in *All apps*); the launcher's USB shortcut
is hidden. The built-in browser below still exists in the code (`MediaActivity`) if it's re-enabled.

The **USB** browser opens any plugged-in drive (one drive opens directly).
- Videos (mp4, mkv, mov, webm, avi, ts…) and music (mp3, m4a, flac, wav, ogg…) play in-app;
  the rest of the folder is queued, so the next episode/song plays automatically.
  D-pad shows controls; play/pause/next keys on the remote work directly.
- Photos (jpg, png, webp, heic, gif…): ◀ ▶ browse, **OK** starts/stops a 5 s slideshow.
- If the TV can't decode a file (e.g. DTS audio), it's handed to the TV's own USB player.

`tv.sh install` grants the storage permission over adb, so the TV never prompts.

## Home background photos
```bash
./scripts/tv.sh wallpapers ~/Pictures/TV   # any folder of jpg/png/heic
```
Photos are resized on the Mac (1920 px wide) and stored in the launcher's private folder on the TV.
Home shows one at a time, changing every 5 minutes (only while Home is on screen).
Run it again with another folder to replace the set; with no photos, Home uses a plain dark background.

## Screensaver video
```bash
./scripts/tv.sh screensaver ~/Movies/clip.mov
```
Re-encodes the clip on the Mac (1080p H.264 + stereo AAC, needs `brew install ffmpeg`), copies it to the
TV and selects the launcher's **Home video** screensaver, which loops it with sound. It starts after the
TV's screen-saver delay (Settings › Device Preferences › Screen saver); any remote key ends it.
`./scripts/tv.sh restore` switches back to the stock screensaver.
