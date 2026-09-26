# King Vegas TV (placeholder name)

**Start here: [SETUP.md](SETUP.md)** — GitHub, Downloader code, Premium server, devices.

An IPTV player for Android TV and Fire TV, modelled on TiviMate. It ships with no channels.
Users add their own playlists.

## Open and run it

1. Install **Android Studio** (free): https://developer.android.com/studio
2. **File → Open…** and pick this `King Vegas TV` folder. Let Gradle sync finish. The first sync downloads
   everything it needs, which can take a few minutes. If Android Studio offers to upgrade the
   Gradle plugin, you can accept.
3. Pick a device:
   * **Emulator:** Tools → Device Manager → Create device → *TV* → 1080p.
   * **Fire TV / Android TV box:** turn on *Developer options → ADB debugging*, then
     `adb connect <box-ip>` in a terminal.
4. Press **Run ▶**. To get an installable file: **Build → Build APK(s)**. The APK lands in
   `app/build/outputs/apk/debug/`.

## What works in this first build

| Area | Status |
|---|---|
| Full Settings menu (12 sections, ~100 options), saved automatically | ✅ |
| Playlists: M3U link, M3U file, Xtream Codes, with per-playlist settings | ✅ |
| Playlist updates: manual (one playlist, all playlists, or from a channel's hold-OK menu), on app start, on a schedule | ✅ |
| Stalker portal | form only, loading not built yet |
| TiviMate-style home: program info + live preview on top, TV guide grid below | ✅ |
| Side menu (Left from the channel column): Search · TV · Movies · TV Shows · Recordings · Playlists · Settings, plus groups | ✅ (Movies / TV Shows / Recordings are placeholders) |
| TV guide (XMLTV .xml/.gz): playlist's own guide, extra sources, guide file, time offset, update interval | ✅ |
| Add playlist flow: choose M3U link / M3U file / Xtream Codes / Stalker → form → Next → name + guide → Done | ✅ (Stalker loading not built yet) |
| Playlists screen split into a section per login type | ✅ |
| Hold OK on a channel: favorite / hide group / lock group | ✅ |
| Favorites, Recently watched, All channels groups | ✅ |
| Hide groups, strip name prefixes, sorting, numbering | ✅ |
| Parental PIN on groups and on Settings | ✅ |
| Player: buffer size, decoder, tunneling, timeout, user agent, reconnect | ✅ |
| Player remote: Up/Down, OK, long-press OK, Left/Right, Back, number keys (all remappable) | ✅ |
| Player menu: favorite, audio track, subtitles, aspect ratio, sleep timer, stream info | ✅ |
| Theme / accent colour / text size | ✅ |
| Backup and restore to a file | ✅ |
| Start on boot, open on last channel, exit confirmation | ✅ |
| Premium: sign in (Settings › Premium account), paywall on every Premium feature, free = 1 playlist, up to 10 devices per account | ✅ |
| Premium website + admin panel + Stripe payments (monthly / yearly / lifetime), test mode | ✅ (`server/`) |
| Automatic APK builds and releases on GitHub, Downloader code, web version | ✅ see SETUP.md |

## Next milestones

1. **Catch-up:** play past programs from the guide (default/append/shift/flussonic/xc)
2. **Movies and TV Shows:** Xtream VOD and series, posters, details, resume
3. **Recording/DVR:** record now, from the guide, custom and recurring, USB/SMB storage
4. **Multiview:** up to 4 players, layouts, audio focus
6. Manual channel reordering, full AFR (display-mode switching), Stalker loading

## Where things live

```
app/src/main/java/com/novatv/app/
  settings/SettingsSchema.kt     ← every setting (add one line = new setting, UI is automatic)
  settings/SettingsRepository.kt ← saving (DataStore), backup/restore
  playlist/                      ← M3U parser, Xtream client, playlist storage & channel organising
  epg/                           ← XMLTV parser, TV guide storage and lookups
  player/PlayerFactory.kt        ← ExoPlayer set up from Playback settings
  ui/                            ← screens (Home, Settings, Playlists, Player, dialogs)
  premium/LicenseManager.kt      ← Premium sign-in, status, devices
  MainActivity.kt                ← screen navigation and startup
server/                          ← Premium website, admin panel, Stripe, license API
web/                             ← web version (GitHub Pages)
.github/workflows/               ← automatic APK builds and releases
```

## Renaming the app

Change `app_name` in `app/src/main/res/values/strings.xml`, and `applicationId` in
`app/build.gradle.kts`. Also change `DEFAULT_USER_AGENT` in `PlaylistRepository.kt`.

## Legal note

Keep the app content-free, like TiviMate: no bundled channels, playlists or provider links.

## Clickable preview

`preview/KingVegasTV-preview.html` opens in Chrome or Edge on your PC. It can load a real M3U link,
M3U file or Xtream Codes login and play HLS / MPEG-TS streams, within browser limits (some
providers block web pages from reading their links; the Android app isn't affected).
