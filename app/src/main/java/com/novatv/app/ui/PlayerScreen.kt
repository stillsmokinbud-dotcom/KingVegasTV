package com.novatv.app.ui

import androidx.compose.material.icons.filled.*
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.novatv.app.app
import com.novatv.app.player.PlayerFactory
import com.novatv.app.playlist.DEFAULT_USER_AGENT
import com.novatv.app.settings.AppSettings
import com.novatv.app.settings.DataKeys
import com.novatv.app.settings.PLAYER_MENU_BUTTONS
import com.novatv.app.settings.RemoteKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Overlay { NONE, CHANNELS, MENU, CONTROL, AUDIO, VIDEO, CAPTIONS, OFFSET, ASPECT, SLEEP, INFO, DESCRIPTION }

/**
 * Full-screen live TV player with TiviMate-style remote controls
 * (all configurable in Settings → Remote control).
 */
@Composable
fun PlayerScreen(
    settings: AppSettings,
    startChannelId: String,
    onExit: () -> Unit,
    onNavigate: (MenuDest) -> Unit = {},
    onFinishApp: () -> Unit = {},
    /** The TV guide is right underneath (Back returns to it); otherwise "TV guide" goes there directly. */
    canExitToGuide: Boolean = true,
) {
    val context = LocalContext.current
    val app = context.app
    val repo = app.playlists
    val scope = rememberCoroutineScope()
    var queue by remember { mutableStateOf(app.playQueue) }
    if (queue.isEmpty()) {
        LaunchedEffect(Unit) { onExit() }
        return
    }

    var index by remember { mutableIntStateOf(queue.indexOfFirst { it.id == startChannelId }.coerceAtLeast(0)) }
    var previousIndex by remember { mutableStateOf<Int?>(null) }
    var overlay by remember { mutableStateOf(Overlay.NONE) }
    var bannerTick by remember { mutableIntStateOf(0) }
    var bannerVisible by remember { mutableStateOf(true) }
    var numberBuffer by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var retries by remember { mutableIntStateOf(0) }
    var aspect by remember { mutableStateOf(settings.str("playback.aspect")) }
    // Sleep timer: an app-wide deadline (keeps counting in the guide too); when it's up the app closes.
    var sleepMinutes by remember {
        val def = settings.str("playback.sleep_default").toIntOrNull() ?: 0
        if (app.sleepAt == 0L && def > 0) app.sleepAt = System.currentTimeMillis() + def * 60_000L
        mutableIntStateOf(if (app.sleepAt > 0L) ((app.sleepAt - System.currentTimeMillis()) / 60_000L).toInt().coerceAtLeast(1) else 0)
    }
    var isPlaying by remember { mutableStateOf(false) }
    var keyLongFired by remember { mutableStateOf(false) }
    var downSeen by remember { mutableStateOf<String?>(null) }
    /** Channel shown by "Show info panel for next/previous channel" (OK switches to it). */
    var peekIndex by remember { mutableStateOf<Int?>(null) }
    var stopped by remember { mutableStateOf(false) }
    /** Channels list: "overlay" (over the video) or "preview" (video shrinks to a window), and groups first. */
    var listMode by remember { mutableStateOf("overlay") }
    var listGroups by remember { mutableStateOf(false) }
    var paywall by remember { mutableStateOf<String?>(null) }
    val favorites by remember(DataKeys.FAVORITES) { app.settings.listFlow(DataKeys.FAVORITES) }.collectAsState(initial = emptyList())
    val epg by app.epg.data.collectAsState()
    val rootFocus = remember { FocusRequester() }
    val allChannels by repo.channels.collectAsState()
    val recentIds by remember(DataKeys.RECENT) { app.settings.listFlow(DataKeys.RECENT) }.collectAsState(initial = emptyList())
    val recent = remember(recentIds, allChannels) {
        val byId = allChannels.associateBy { it.id }
        recentIds.mapNotNull { byId[it] }
    }

    // Shared with the guide preview: Back to the guide keeps the same stream playing (TiviMate).
    val built = remember(PlayerFactory.signature(settings)) { app.shared.obtain(settings) }
    DisposableEffect(Unit) { app.shared.attach(); onDispose { app.shared.detach() } }
    val player = built.player
    val channel = queue[index.coerceIn(queue.indices)]
    val overlayAlpha = (1f - settings.int("appearance.overlay_opacity") / 100f).coerceIn(0.25f, 1f)
    DisposableEffect(Unit) { app.playerActive = true; onDispose { app.playerActive = false } }
    // Watch time (Channels sorting › By watch time)
    LaunchedEffect(channel.id) {
        while (true) { delay(60_000); if (player.isPlaying) repo.addWatchTime(channel.id, 60) }
    }

    fun switchTo(i: Int) {
        if (queue.isEmpty()) return
        val wrapped = ((i % queue.size) + queue.size) % queue.size
        if (wrapped != index) previousIndex = index
        index = wrapped
    }

    // Start / switch channel
    LaunchedEffect(channel.id) {
        val pl = repo.playlistFor(channel)
        val ua = channel.userAgent?.takeIf { it.isNotBlank() } ?: repo.userAgentFor(pl, settings)
        error = null
        retries = 0
        // Settings › Playback › Use external player (For TV / For TV and VOD)
        if (com.novatv.app.player.ExternalPlayer.wanted(settings, vod = false) &&
            com.novatv.app.player.ExternalPlayer.open(context, PlayerFactory.viaUdpProxy(channel.url, settings), channel.name)) {
            app.lastPlayedId = channel.id
            onExit(); return@LaunchedEffect
        }
        if (!app.shared.isPlaying(channel.id)) {
            built.dataSource.setUserAgent(ua)
            player.setMediaItem(PlayerFactory.mediaItem(PlayerFactory.viaUdpProxy(channel.url, settings)))
            player.prepare()
            app.shared.markLoaded(channel.id)
        }
        player.volume = 1f
        player.playWhenReady = true
        stopped = false
        bannerTick++
        app.lastPlayedId = channel.id
        // Recent channels and History each have their own "Delay before adding" setting.
        launch {
            delay(settings.int("history.delay") * 1000L)
            repo.addHistory(channel, epg.at(channel, System.currentTimeMillis()))
        }
        delay(settings.int("recent.delay") * 1000L)
        repo.markWatched(channel)
    }

    // Freeze / drop-out recovery lives in the shared player (SharedPlayback.Guard): it reconnects
    // on errors, when the server closes the stream, when loading hangs and when the picture freezes.
    DisposableEffect(Unit) {
        app.shared.onReconnectChanged = { on -> error = if (on) "Reconnecting…" else null }
        onDispose { app.shared.onReconnectChanged = null }
    }

    // Channel info banner
    LaunchedEffect(bannerTick) {
        bannerVisible = true
        delay(settings.int("player.panels_timeout").coerceAtLeast(1) * 1000L)
        peekIndex = null
        bannerVisible = false
    }

    // Number-key channel entry
    LaunchedEffect(numberBuffer) {
        if (numberBuffer.isEmpty()) return@LaunchedEffect
        delay(settings.int("remote.number_delay").coerceAtLeast(1) * 1000L)
        val n = numberBuffer.toIntOrNull()
        numberBuffer = ""
        if (n == null) return@LaunchedEffect
        // Channel number: in this group first, then in all channels; playlists without numbers use the
        // position in the list (1 = first).
        val inQueue = queue.indexOfFirst { it.number == n }
        val inAll = if (inQueue < 0) allChannels.indexOfFirst { it.number == n } else -1
        when {
            inQueue >= 0 -> switchTo(inQueue)
            inAll >= 0 -> { previousIndex = null; queue = allChannels; app.playQueue = allChannels; index = inAll }
            (n - 1) in queue.indices -> switchTo(n - 1)
        }
    }

    // Sleep timer (checked here while watching, and app-wide in MainActivity)
    LaunchedEffect(sleepMinutes) {
        while (app.sleepAt > 0L) {
            val left = app.sleepAt - System.currentTimeMillis()
            if (left <= 0L) { app.sleepAt = 0L; player.playWhenReady = false; onFinishApp(); break }
            delay(minOf(left, 30_000L))
        }
    }

    // Errors and auto-reconnect
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                // The shared player reconnects by itself and keeps trying (like TiviMate).
                error = "Reconnecting…"
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) error = null
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            // Settings › Playback › Auto frame rate
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                com.novatv.app.player.Afr.apply(context as? android.app.Activity, settings, player.videoFormat, vod = false)
            }
        }
        player.addListener(listener)
        isPlaying = player.isPlaying
        onDispose {
            com.novatv.app.player.Afr.reset(context as? android.app.Activity)
            player.removeListener(listener)
            // Not released: the guide preview keeps showing this channel.
        }
    }

    BackHandler(enabled = overlay != Overlay.NONE) { overlay = Overlay.NONE }

    val audio = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager }

    val playUrl = LocalPlayUrl.current
    val openMultiview = LocalOpenMultiview.current
    fun action(a: String) {
        fun premiumOr(feature: String, block: () -> Unit) { if (settings.premium) block() else paywall = feature }
        when (a) {
            // Back to the TV guide (even when the player was opened from History or Search).
            "guide_overlay", "guide_preview" -> if (canExitToGuide) onExit() else onNavigate(MenuDest.GUIDE)
            "guide_groups_overlay", "guide_groups_preview" -> { app.openGuideGroups = true; onExit() }
            "channels_overlay", "channels_groups_overlay", "channels_preview", "channels_groups_preview" -> {
                listMode = if (a.endsWith("preview")) "preview" else "overlay"
                listGroups = a.contains("groups")
                overlay = Overlay.CHANNELS
            }
            "program_description" -> overlay = Overlay.DESCRIPTION
            "info_control" -> { bannerTick++; overlay = Overlay.CONTROL }
            "info" -> bannerTick++
            "control" -> overlay = Overlay.CONTROL
            "menu" -> overlay = Overlay.MENU
            "next_channel" -> switchTo(index + 1)
            "prev_channel" -> switchTo(index - 1)
            // The channel watched before this one (from Recent channels, also right after opening the player).
            "recent_channel" -> recent.firstOrNull { it.id != channel.id }?.let { c ->
                val i = queue.indexOfFirst { it.id == c.id }
                if (i >= 0) switchTo(i)
                else allChannels.indexOfFirst { it.id == c.id }.takeIf { it >= 0 }?.let { j ->
                    previousIndex = null; queue = allChannels; app.playQueue = allChannels; index = j
                }
            }
            "info_next" -> { peekIndex = (((peekIndex ?: index) + 1) % queue.size); bannerTick++ }
            "info_prev" -> { peekIndex = (((peekIndex ?: index) - 1 + queue.size) % queue.size); bannerTick++ }
            "volume_up" -> audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI)
            "volume_down" -> audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
            "volume_mute" -> audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_TOGGLE_MUTE, android.media.AudioManager.FLAG_SHOW_UI)
            "play_pause" -> if (stopped) { stopped = false; error = null; player.prepare(); player.play() } else player.playWhenReady = !player.playWhenReady
            "stop" -> { player.playWhenReady = false; player.stop(); stopped = true; error = "Stopped · press Play to resume" }
            "restart" -> premiumOr("Catch-up") {
                val prog = epg.at(channel, System.currentTimeMillis())
                val url = prog?.let { com.novatv.app.premium.Catchup.url(channel, it.start, it.end) }
                if (prog != null && url != null) playUrl("${channel.name} · ${prog.title}", url)
                else error = if (prog == null) "No TV guide info for this program, so it can't be restarted."
                    else "This channel doesn't offer catch-up."
            }
            "go_live" -> { player.seekToDefaultPosition(); player.play() }
            "search" -> onNavigate(MenuDest.SEARCH)
            "history" -> onNavigate(MenuDest.HISTORY)
            "movies" -> onNavigate(MenuDest.MOVIES)
            "shows" -> onNavigate(MenuDest.SHOWS)
            "recordings" -> onNavigate(MenuDest.RECORDINGS)
            "my_list" -> onNavigate(MenuDest.MY_LIST)
            "record" -> premiumOr("Recording") {
                val rec = app.recordings
                val running = rec.items.value.firstOrNull { it.channelId == channel.id && it.state == "recording" }
                if (running != null) { rec.stop(running.id); error = "Recording stopped" }
                else {
                    val now = System.currentTimeMillis()
                    val prog = epg.at(channel, now)
                    rec.schedule(channel, prog?.title ?: channel.name, now, prog?.end?.takeIf { it > now + 60_000 } ?: (now + 3_600_000))
                    error = "● Recording ${prog?.title ?: channel.name} (Recordings in the menu)"
                }
                scope.launch { delay(3000); if (error?.startsWith("●") == true || error == "Recording stopped") error = null }
            }
            "multiview" -> premiumOr("Multiview") {
                val others = queue.filter { it.id != channel.id }.take(3)
                openMultiview(listOf(channel) + others)
            }
            "pip" -> premiumOr("Picture-in-picture") {
                (context as? android.app.Activity)?.let { act ->
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                        runCatching { act.enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build()) }
                }
            }
            "video_tracks" -> overlay = Overlay.VIDEO
            "audio_tracks" -> overlay = Overlay.AUDIO
            "audio_offset" -> overlay = Overlay.OFFSET
            "captions" -> overlay = Overlay.CAPTIONS
            "display_mode" -> overlay = Overlay.ASPECT
            "sleep_timer" -> overlay = Overlay.SLEEP
            "favorite" -> premiumOr("Favorites") { scope.launch { app.settings.toggleInList(DataKeys.FAVORITES, channel.id) } }
            "channel_options" -> overlay = Overlay.INFO
            "settings" -> onNavigate(MenuDest.SETTINGS)
            "go_back" -> onExit()
            "exit" -> onFinishApp()
            else -> Unit
        }
    }

    fun mapped(id: String): String = if (settings.premium) settings.str(RemoteKeys.playerKey(id))
        else RemoteKeys.PLAYER_KEYS.first { it.id == id }.default

    // Safety net: if the remote's Back ever arrives while nothing on the player has the focus,
    // it still does what Remote control › Player › Back says (TiviMate default: back to the TV guide).
    BackHandler(enabled = overlay == Overlay.NONE) { action(mapped("back")) }

    /** Remote control › Player: which key this is. */
    fun keyId(k: Key): String? = when (k) {
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> "ok"
        Key.Back -> "back"
        Key.DirectionLeft -> "left"
        Key.DirectionRight -> "right"
        Key.DirectionUp -> "up"
        Key.DirectionDown -> "down"
        Key.ChannelUp, Key.PageUp -> "ch_up"
        Key.ChannelDown, Key.PageDown -> "ch_down"
        Key.VolumeUp -> "vol_up"
        Key.VolumeDown -> "vol_down"
        Key.VolumeMute -> "mute"
        Key.Menu -> "menu"
        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> "play_pause"
        Key.MediaStop -> "stop"
        Key.MediaRewind -> "rewind"
        Key.MediaFastForward -> "ffwd"
        Key.MediaRecord -> "record"
        Key.Backspace -> "backspace"
        Key.Info -> "info"
        Key.Guide -> "guide"
        Key.ProgramRed -> "red"
        Key.ProgramGreen -> "green"
        Key.ProgramYellow -> "yellow"
        Key.ProgramBlue -> "blue"
        else -> null
    }
    val longKeys = setOf("ok", "back", "left", "right", "up", "down", "menu", "play_pause")

    Box(
        Modifier
            .fillMaxSize()
            // See-through: the shared picture underneath shows here.
            .focusRequester(rootFocus)
            .onKeyEvent { e ->
                if (overlay != Overlay.NONE) return@onKeyEvent false
                val id = keyId(e.key)
                if (id == null) {
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val digit = digitOf(e.key)
                    if (digit != null && settings.bool("remote.number_keys")) { numberBuffer += digit; return@onKeyEvent true }
                    return@onKeyEvent false
                }
                // Volume keys doing volume: leave them to the system, so the TV's own volume (HDMI-CEC) works.
                if (id == "vol_up" || id == "vol_down" || id == "mute") {
                    val m = mapped(id)
                    if (m == "volume_up" || m == "volume_down" || m == "volume_mute") return@onKeyEvent false
                }
                // Remote control › Seeking options: rewind the live stream through catch-up.
                if (e.type == KeyEventType.KeyDown && channel.catchupDays > 0 && (
                        (id == "rewind" && settings.bool("remote.seek_rw_live")) ||
                        (id == "left" && settings.bool("remote.seek_left_live")) ||
                        (id == "down" && settings.bool("remote.seek_down_live")))) {
                    val now = System.currentTimeMillis()
                    val back = maxOf(60_000L, settings.int("playback.skip_short") * 1000L)
                    val prog = epg.at(channel, now)
                    val url = com.novatv.app.premium.Catchup.url(channel, now - back, prog?.end?.takeIf { it > now } ?: (now + 3_600_000L))
                    if (url != null) { playUrl("${channel.name} · ${prog?.title ?: "Catch-up"}", url); return@onKeyEvent true }
                }
                // OK while peeking at another channel's info turns that channel on.
                if (id == "ok" && peekIndex != null) {
                    if (e.type == KeyEventType.KeyUp) { switchTo(peekIndex!!); peekIndex = null }
                    return@onKeyEvent true
                }
                if (id in longKeys) {
                    // Ignore the rest of a press that started on another screen (e.g. Back held in the guide
                    // to come here must not also trigger "Long Back" in the player).
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) downSeen = id
                    else if (downSeen != id) return@onKeyEvent true
                    if (e.type == KeyEventType.KeyUp) downSeen = null
                    if (e.type == KeyEventType.KeyDown) {
                        if (e.nativeKeyEvent.repeatCount == 0) keyLongFired = false
                        else if (!keyLongFired) { keyLongFired = true; action(mapped(id + "_long")) }
                    } else if (e.type == KeyEventType.KeyUp) {
                        if (!keyLongFired) action(mapped(id))
                        keyLongFired = false
                    }
                    return@onKeyEvent true
                }
                if (e.type == KeyEventType.KeyDown) action(mapped(id))
                true
            }
            .focusable()
    ) {
        // Channels list in preview mode: the video shrinks into a window at the top right
        // (Settings › Player › Channels list › Preview mode › Animated transition).
        val previewList = overlay == Overlay.CHANNELS && listMode == "preview"
        val shrink by androidx.compose.animation.core.animateFloatAsState(
            if (previewList) 1f else 0f,
            androidx.compose.animation.core.tween(if (settings.bool("player.preview_animated")) 220 else 0), label = "preview")
        val blackOnSwitch = settings.bool("player.black_screen")
        // TV guide › Preview › Animated transition: the video grows from the preview window to full screen.
        val grow = remember {
            androidx.compose.animation.core.Animatable(if (app.fromGuidePreview && settings.bool("guide.preview_animated")) 0f else 1f)
                .also { app.fromGuidePreview = false }
        }
        LaunchedEffect(Unit) { grow.animateTo(1f, androidx.compose.animation.core.tween(260)) }
        val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.toFloat()
        val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.toFloat()
        val g = grow.value
        // The picture itself is the app-wide shared video (VideoStage); this screen just places it:
        // growing out of the guide preview, full screen, or shrunk top-right for the channels list.
        val dens = androidx.compose.ui.platform.LocalDensity.current
        val fullW = with(dens) { screenW.dp.toPx() }
        val fullH = with(dens) { screenH.dp.toPx() }
        val from = com.novatv.app.ui.VideoStage.lastPreview
            ?: with(dens) { androidx.compose.ui.geometry.Rect(24.dp.toPx(), 18.dp.toPx(), 380.dp.toPx(), 218.dp.toPx()) }
        val full = androidx.compose.ui.geometry.Rect(0f, 0f, fullW, fullH)
        val target = if (g < 1f) androidx.compose.ui.geometry.lerp(from, full, g) else {
            val k = 1f - 0.55f * shrink
            val m = with(dens) { (40 * shrink).dp.toPx() }
            androidx.compose.ui.geometry.Rect(fullW - fullW * k - m, m, fullW - m, m + fullH * k)
        }
        // Display mode 16:9 / 4:3: the picture is boxed to that shape (and stretched to fill it).
        fun forced(r: androidx.compose.ui.geometry.Rect, ar: Float): androidx.compose.ui.geometry.Rect {
            val w = minOf(r.width, r.height * ar); val h = w / ar
            return androidx.compose.ui.geometry.Rect(r.center.x - w / 2, r.center.y - h / 2, r.center.x + w / 2, r.center.y + h / 2)
        }
        val shown = when (aspect) { "16_9" -> forced(target, 16f / 9f); "4_3" -> forced(target, 4f / 3f); else -> target }
        val token = remember { com.novatv.app.ui.VideoStage.claim(shown) }
        androidx.compose.runtime.SideEffect {
            com.novatv.app.ui.VideoStage.move(token, shown)
            com.novatv.app.ui.VideoStage.resizeMode.value = resizeModeFor(aspect)
            // Settings › Player › "Show black screen when switching channels"
            com.novatv.app.ui.VideoStage.keepContent.value = !blackOnSwitch
            com.novatv.app.ui.VideoStage.keepScreenOn.value = true
        }
        DisposableEffect(Unit) {
            onDispose {
                com.novatv.app.ui.VideoStage.keepScreenOn.value = false
                com.novatv.app.ui.VideoStage.resizeMode.value = AspectRatioFrameLayout.RESIZE_MODE_FIT
                com.novatv.app.ui.VideoStage.keepContent.value = true
                com.novatv.app.ui.VideoStage.release(token)
            }
        }
        // Settings › Player › Clock (always on screen while watching)
        if (settings.bool("appearance.show_clock") && overlay == Overlay.NONE && !bannerVisible) PlayerClock(settings)

        // TiviMate-style info panel: passive after a channel change, interactive (controls + tiles) on OK.
        if ((bannerVisible && overlay == Overlay.NONE) || overlay == Overlay.CONTROL) {
            val shownChannel = peekIndex?.let { queue.getOrNull(it) } ?: channel
            PlayerInfoPanel(
                settings = settings, channel = shownChannel, epg = epg, player = player,
                interactive = overlay == Overlay.CONTROL, peek = peekIndex != null, isPlaying = isPlaying,
                recent = recent.filter { it.id != channel.id },
                playlistName = remember(shownChannel.playlistId, settings) { repo.playlistNameFor(shownChannel, settings) },
                onAction = { a ->
                    when (a) {
                        "dismiss" -> overlay = Overlay.NONE
                        "guide" -> { overlay = Overlay.NONE; action("guide_overlay") }
                        "history" -> { overlay = Overlay.NONE; onNavigate(MenuDest.HISTORY) }
                        "clear_history" -> scope.launch { app.settings.setList(DataKeys.RECENT, listOf(channel.id)) }
                        "prev" -> switchTo(index - 1)
                        "next" -> switchTo(index + 1)
                        "rew" -> player.seekBack()
                        "ffwd" -> player.seekForward()
                        "play" -> action("play_pause")
                        "live" -> action("go_live")
                        "restart" -> action("restart")
                        "record" -> action("record")
                        "menu" -> overlay = Overlay.MENU
                    }
                },
                onPlayRecent = { c ->
                    overlay = Overlay.NONE
                    val i = queue.indexOfFirst { it.id == c.id }
                    if (i >= 0) switchTo(i) else {
                        previousIndex = null; queue = allChannels; app.playQueue = allChannels
                        index = allChannels.indexOfFirst { it.id == c.id }.coerceAtLeast(0)
                    }
                },
            )
        }

        if (numberBuffer.isNotEmpty()) {
            Text(numberBuffer, fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 40.dp))
        }

        error?.let {
            Text(it, fontSize = 18.sp, color = Color.White,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.7f)).padding(16.dp))
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = overlay == Overlay.CHANNELS,
            enter = androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(190)) { -it / 4 } +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
        ) {
            PlayerChannelList(
                settings = settings, allChannels = allChannels, queue = queue, current = index, epg = epg, favorites = favorites,
                mode = listMode, startWithGroups = listGroups,
                onPick = { list, i, stay ->
                    if (!stay) overlay = Overlay.NONE
                    if (list !== queue) {
                        queue = list; app.playQueue = list; previousIndex = null; index = i.coerceIn(list.indices)
                    } else switchTo(i)
                },
                onPlayArchive = { title, url -> overlay = Overlay.NONE; playUrl(title, url) },
                onLong = { c -> if (settings.premium) scope.launch { app.settings.toggleInList(DataKeys.FAVORITES, c.id) } else paywall = "Favorites" },
                onDismiss = { overlay = Overlay.NONE },
            )
        }

        if (overlay == Overlay.MENU) {
            val fav = channel.id in favorites
            val v = player.videoFormat
            val a = player.audioFormat
            val labels = mapOf(
                "video_tracks" to (v?.takeIf { it.width > 0 }?.let { "${it.width} × ${it.height}" }),
                "audio_tracks" to (a?.let { f ->
                    val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { java.util.Locale(it).displayLanguage } ?: "Audio"
                    val ch = when (f.channelCount) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1 surround sound"; 8 -> "7.1 surround sound"; else -> "" }
                    if (ch.isEmpty()) lang else "$lang\n$ch"
                }),
                "audio_offset" to "0 ms",
                "captions" to (if (player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) "Off" else "On"),
                "display_mode" to when (aspect) { "fill" -> "Stretch"; "zoom" -> "Zoom"; "16_9" -> "16:9"; "4_3" -> "4:3"; else -> "Normal" },
                "sleep_timer" to (if (sleepMinutes > 0) "$sleepMinutes min" else "Off"),
                "favorite" to (if (fav) "Remove from Favorites" else "Add to Favorites"),
            )
            val savedOrder by remember(DataKeys.MENU_ORDER) { app.settings.listFlow(DataKeys.MENU_ORDER) }.collectAsState(initial = emptyList())
            val ids = PLAYER_MENU_BUTTONS.map { it.first }
            val order = savedOrder.filter { it in ids } + ids.filter { it !in savedOrder }
            val buttons = order.filter { it != "audio_offset" && settings.bool("player.btn.$it") }.map { id ->
                id to (labels[id] ?: PLAYER_MENU_BUTTONS.first { it.first == id }.second)
            }
            PlayerMenuRow(buttons, ::menuButtonIcon, header = channel.group to dateTimeText(System.currentTimeMillis(), settings, context),
                onDismiss = { overlay = Overlay.NONE }) { id ->
                overlay = Overlay.NONE
                action(when (id) { "channels" -> "channels_overlay"; else -> id })
            }
        }
    }

    // Menus
    when (overlay) {
        Overlay.DESCRIPTION -> {
            val p = epg.at(channel, System.currentTimeMillis())
            MessageDialog(p?.title ?: channel.name, p?.desc?.ifBlank { null } ?: "No description") { overlay = Overlay.NONE }
        }
        Overlay.VIDEO -> {
            val v = player.videoFormat
            MessageDialog("Video tracks", if (v == null) "No video" else "${v.width}x${v.height}" +
                (v.frameRate.takeIf { it > 0 }?.let { " · %.2f fps".format(it) } ?: "") + " · ${v.sampleMimeType ?: ""}") { overlay = Overlay.NONE }
        }
        Overlay.CAPTIONS -> {
            val off = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            ChoiceDialog("Closed captions", listOf("off" to "Off", "on" to "On"), if (off) "off" else "on", { overlay = Overlay.NONE }) {
                val b = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, it == "off")
                if (it == "on") {
                    // IPTV caption tracks are rarely marked "default", so choose the first one that can be shown.
                    b.setSelectUndeterminedTextLanguage(true)
                    player.currentTracks.groups.firstOrNull { g -> g.type == C.TRACK_TYPE_TEXT && g.isTrackSupported(0) }
                        ?.let { g -> b.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)) }
                } else b.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                player.trackSelectionParameters = b.build()
                val none = it == "on" && player.currentTracks.groups.none { g -> g.type == C.TRACK_TYPE_TEXT }
                if (none) { error = "This channel has no captions"; scope.launch { delay(2500); if (error == "This channel has no captions") error = null } }
                overlay = Overlay.NONE
            }
        }
        Overlay.OFFSET -> MessageDialog("Audio offset", "Audio offset adjustment is coming in a later update.") { overlay = Overlay.NONE }
        Overlay.AUDIO -> {
            val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            val current = groups.withIndex().firstNotNullOfOrNull { (gi, g) -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { "$gi:$it" } }
            val options = groups.flatMapIndexed { gi, g ->
                (0 until g.length).filter { g.isTrackSupported(it) }.map { ti ->
                    val f = g.getTrackFormat(ti)
                    "$gi:$ti" to listOfNotNull(f.language?.uppercase(), f.label, f.sampleMimeType?.substringAfter('/'),
                        if (f.channelCount > 0) "${f.channelCount}ch" else null).joinToString(" · ").ifBlank { "Track ${ti + 1}" }
                }
            }
            if (options.size <= 1) MessageDialog("Audio track", if (options.isEmpty()) "No audio track" else "This channel has only one audio track.") { overlay = Overlay.NONE }
            else ChoiceDialog("Audio track", options, current, { overlay = Overlay.NONE }) { v ->
                val (gi, ti) = v.split(':').map { it.toInt() }
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(groups[gi].mediaTrackGroup, ti)).build()
                overlay = Overlay.NONE
            }
        }
        Overlay.ASPECT -> ChoiceDialog("Aspect ratio",
            listOf("fit" to "Fit", "fill" to "Stretch", "zoom" to "Zoom (crop)", "16_9" to "16:9", "4_3" to "4:3"),
            aspect, { overlay = Overlay.NONE }) {
                aspect = it; overlay = Overlay.NONE
                scope.launch { app.settings.set("playback.aspect", it) } // remembered for next time
            }
        Overlay.SLEEP -> ChoiceDialog("Sleep timer",
            listOf("0" to "Off", "15" to "15 min", "30" to "30 min", "60" to "1 hour", "90" to "90 min",
                "120" to "2 hours", "240" to "4 hours"),
            sleepMinutes.toString().takeIf { it in setOf("0", "15", "30", "60", "90", "120", "240") }, { overlay = Overlay.NONE }) {
                val m = it.toInt()
                app.sleepAt = if (m > 0) System.currentTimeMillis() + m * 60_000L else 0L
                sleepMinutes = m; overlay = Overlay.NONE
            }
        Overlay.INFO -> {
            val v = player.videoFormat
            val a = player.audioFormat
            val text = buildString {
                appendLine("Channel: ${channel.name}")
                appendLine("Video: ${v?.width ?: "?"}×${v?.height ?: "?"} · ${v?.frameRate?.takeIf { it > 0 }?.let { "%.2f fps".format(it) } ?: "? fps"} · ${v?.sampleMimeType ?: "?"}")
                appendLine("Audio: ${a?.sampleMimeType ?: "?"} · ${a?.channelCount ?: "?"} ch · ${a?.sampleRate ?: "?"} Hz")
                append("URL: ${channel.url.substringBefore('?')}")
            }
            MessageDialog("Stream info", text) { overlay = Overlay.NONE }
        }
        else -> Unit
    }

    paywall?.let { PaywallDialog(it) { paywall = null } }

    LaunchedEffect(overlay) {
        if (overlay == Overlay.NONE) runCatching { rootFocus.requestFocus() }
    }
}

@Composable
private fun ChannelListOverlay(
    settings: AppSettings,
    channels: List<com.novatv.app.playlist.Channel>,
    epg: com.novatv.app.epg.EpgData,
    current: Int,
    favorites: List<String>,
    alpha: Float,
    onPick: (Int) -> Unit,
    onLong: (Int) -> Unit,
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 4).coerceAtLeast(0))
    val fr = remember { FocusRequester() }
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .width(480.dp)
                .fillMaxHeight()
                .background(Color(0xFF101318).copy(alpha = alpha))
                .padding(12.dp)
        ) {
            Text(channels.getOrNull(current)?.group ?: "Channels", fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(8.dp))
            LazyColumn(state = state) {
                itemsIndexed(channels) { i, ch ->
                    val num = ch.number
                    val nowProg = epg.at(ch, System.currentTimeMillis())
                    TvRow(
                        modifier = if (i == current) Modifier.focusRequester(fr) else Modifier,
                        selected = i == current,
                        onLongClick = { onLong(i) },
                        onClick = { onPick(i) },
                    ) {
                        if (settings.bool("channels.show_numbers") && num != null) {
                            Text("$num", fontSize = 15.sp, color = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.width(56.dp))
                        }
                        ChannelLogo(settings, ch, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ch.name, fontSize = 16.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (nowProg != null) {
                                Text(nowProg.title, fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                val t = System.currentTimeMillis()
                                ProgressBar((t - nowProg.start).toFloat() / (nowProg.end - nowProg.start), Modifier.width(180.dp).padding(top = 4.dp))
                            }
                        }
                        if (ch.id in favorites) Text("★", color = Color(0xFFFFC107))
                    }
                }
            }
        }
    }
    AutoFocus(fr)
}

private fun resizeModeFor(aspect: String): Int = when (aspect) {
    // 16:9 and 4:3: the picture area itself is boxed to that shape, and the video fills it.
    "fill", "16_9", "4_3" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

internal fun digitOf(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}

/** TiviMate's player menu: a row of buttons along the bottom (Appearance › Player › Menu). */
@Composable
private fun PlayerMenuBar(settings: AppSettings, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val app = LocalContext.current.app
    val saved by remember(DataKeys.MENU_ORDER) { app.settings.listFlow(DataKeys.MENU_ORDER) }.collectAsState(initial = emptyList())
    val ids = PLAYER_MENU_BUTTONS.map { it.first }
    val order = saved.filter { it in ids } + ids.filter { it !in saved }
    val shown = order.filter { settings.bool("player.btn.$it") }
    val fr = remember { FocusRequester() }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                    .padding(horizontal = 30.dp, vertical = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                shown.forEachIndexed { i, id ->
                    val label = PLAYER_MENU_BUTTONS.first { it.first == id }.second
                    TvRow(modifier = Modifier.width(96.dp).then(if (i == 0) Modifier.focusRequester(fr) else Modifier), onClick = { onPick(id) }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            androidx.compose.material3.Icon(menuButtonIcon(id), null, tint = rowContentColor())
                            Text(label, fontSize = 11.sp, color = rowContentColor(), maxLines = 2,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
    AutoFocus(fr)
}

private fun menuButtonIcon(id: String): androidx.compose.ui.graphics.vector.ImageVector {
    val i = androidx.compose.material.icons.Icons.Filled
    return when (id) {
        "search" -> i.Search
        "channels" -> i.List
        "recordings" -> i.FiberDvr
        "multiview" -> i.GridView
        "pip" -> i.PictureInPicture
        "video_tracks" -> i.Hd
        "audio_tracks" -> i.Audiotrack
        "audio_offset" -> i.Sync
        "captions" -> i.ClosedCaption
        "display_mode" -> i.AspectRatio
        "sleep_timer" -> i.Timer
        "favorite" -> i.Star
        "channel_options" -> i.MoreVert
        "settings" -> i.Settings
        "history" -> i.History
        "movies" -> i.Movie
        "shows" -> i.VideoLibrary
        "my_list" -> i.BookmarkBorder
        else -> i.ExitToApp
    }
}

/** Control panel: previous / rewind / play-pause / forward / next, restart and record. */
@Composable
private fun ControlPanel(isPlaying: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val fr = remember { FocusRequester() }
    val i = androidx.compose.material.icons.Icons.Filled
    val buttons = listOf("restart" to i.Replay, "prev" to i.SkipPrevious, "rew" to i.FastRewind,
        "play" to (if (isPlaying) i.Pause else i.PlayArrow), "ffwd" to i.FastForward, "next" to i.SkipNext, "record" to i.FiberManualRecord)
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 180.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                buttons.forEach { (id, icon) ->
                    TvRow(modifier = Modifier.width(64.dp).then(if (id == "play") Modifier.focusRequester(fr) else Modifier), onClick = { onPick(id) }) {
                        androidx.compose.material3.Icon(icon, null, tint = rowContentColor(), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
    AutoFocus(fr)
}

/** Settings › Appearance › Player › Clock: position, size and transparency. */
@Composable
private fun PlayerClock(settings: AppSettings) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); now = System.currentTimeMillis() } }
    val size = when (settings.str("player.clock_size")) { "small" -> 14; "large" -> 26; else -> 18 }
    val alpha = when (settings.str("player.clock_transparency")) { "low" -> 0.75f; "medium" -> 0.5f; "high" -> 0.28f; else -> 1f }
    val align = when (settings.str("player.clock_position")) {
        "top_left" -> Alignment.TopStart; "bottom_left" -> Alignment.BottomStart; "bottom_right" -> Alignment.BottomEnd
        else -> Alignment.TopEnd
    }
    Box(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Text(timeText(now, settings, context), fontSize = size.sp, fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = alpha),
            modifier = Modifier.align(align).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.35f * alpha))
                .padding(horizontal = 10.dp, vertical = 4.dp))
    }
}
