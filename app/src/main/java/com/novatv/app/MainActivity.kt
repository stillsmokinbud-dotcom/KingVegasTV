package com.novatv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.novatv.app.playlist.PlaylistType
import com.novatv.app.ui.AddPlaylistScreen
import com.novatv.app.ui.GuideScreen
import com.novatv.app.ui.InfoScreen
import com.novatv.app.ui.MenuDest
import com.novatv.app.ui.SearchScreen
import com.novatv.app.ui.GetPremiumScreen
import com.novatv.app.ui.LocalOpenPremium
import com.novatv.app.ui.PremiumAccountScreen
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.novatv.app.settings.AppSettings
import com.novatv.app.settings.SettingAction
import com.novatv.app.ui.AppTheme
import com.novatv.app.ui.PinDialog
import com.novatv.app.ui.PlayerScreen
import com.novatv.app.ui.SettingsScreen

/** Simple screen stack (no navigation library needed). */
sealed interface Screen {
    data object Home : Screen
    /** Settings panel; [page] opens it straight at a section (e.g. "playlists"). */
    data class Settings(val page: String? = null) : Screen
    /** Add-playlist flow; null type = the "choose a type" screen. */
    data class AddPlaylist(val type: PlaylistType?) : Screen
    data object Search : Screen
    data object Premium : Screen
    data object GetPremium : Screen
    data class Info(val title: String, val text: String) : Screen
    data class Player(val channelId: String) : Screen
    data object Recordings : Screen
    data object History : Screen
    data object Reminders : Screen
    data class Multiview(val channelIds: List<String>) : Screen
    data class Vod(val kind: com.novatv.app.playlist.VodKind, val start: String? = null) : Screen
    data class MovieDetails(val item: com.novatv.app.playlist.VodItem) : Screen
    data class Series(val item: com.novatv.app.playlist.VodItem) : Screen
    /** Movie or episodes: (resume key, url) list, titles, where to start. */
    data class VodPlayer(
        val items: List<Pair<String, String>>,
        val titles: List<String>,
        val start: Int,
        val fromStart: Boolean,
    ) : Screen
}

class MainActivity : ComponentActivity() {
    /** General › Switch to picture-in-picture mode on press Home. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val s = app.lastSettings ?: return
        if (app.playerActive && s.premium && s.bool("general.pip_home") &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
        ) runCatching { enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build()) }
    }

    /** Leaving the app (Home, Back out, another app): no sound keeps playing in the background. PiP keeps playing. */
    override fun onStop() {
        super.onStop()
        val pip = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && isInPictureInPictureMode
        if (!pip) com.novatv.app.player.PlayerFactory.suspendAll()
    }

    override fun onStart() {
        super.onStart()
        com.novatv.app.player.PlayerFactory.resumeAll()
    }

    /** Closing the picture-in-picture window stops the video too. */
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
            com.novatv.app.player.PlayerFactory.suspendAll()
    }

    override fun onDestroy() {
        if (isFinishing) com.novatv.app.player.PlayerFactory.stopAll()
        super.onDestroy()
    }

    private val systemLocale: java.util.Locale = java.util.Locale.getDefault()

    @Suppress("DEPRECATION")
    private fun applyLanguage(code: String) {
        val locale = when {
            code == "system" || code.isBlank() -> systemLocale
            code.contains('_') -> java.util.Locale(code.substringBefore('_'), code.substringAfter('_').uppercase())
            else -> java.util.Locale(code)
        }
        java.util.Locale.setDefault(locale)
        runCatching {
            val cfg = android.content.res.Configuration(resources.configuration).apply { setLocale(locale) }
            resources.updateConfiguration(cfg, resources.displayMetrics)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by app.settings.settings.collectAsState(initial = null)
            val s = settings
            if (s == null) {
                Box(Modifier.fillMaxSize().background(Color.Black))
            } else {
                app.lastSettings = s
                // Appearance › Language: dates, times and number formats follow the chosen language.
                val lang = s.str("general.language")
                androidx.compose.runtime.remember(lang) { applyLanguage(lang) }
                AppTheme(s) { AppRoot(s, onFinish = { finish() }) }
            }
        }
    }
}

@Composable
private fun AppRoot(settings: AppSettings, onFinish: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.app
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    var pendingSettingsPage by remember { mutableStateOf<String?>(null) }
    var askPinForSettings by remember { mutableStateOf(false) }

    fun push(s: Screen) = stack.add(s)
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    // Startup: cached channels first, then updates that are due, then the start screen.
    // Premium status: cached first, then checked with the server every 6 hours.
    LaunchedEffect(Unit) {
        app.license.load()
        while (true) {
            app.license.refresh()
            delay(6 * 3600 * 1000L)
        }
    }

    LaunchedEffect(Unit) {
        app.playlists.reloadFromCache()
        app.epg.loadCache()
        if (settings.bool("general.start_last_channel")) {
            app.playlists.lastChannel()?.let { last ->
                app.playQueue = app.playlists.channels.value
                push(Screen.Player(last.id))
            }
        }
        app.vod.loadCache()
        // Let the first screen settle before heavy downloads start (smooth first launch).
        delay(2500)
        app.playlists.refreshDue(appStart = true)
        app.appScope.launch { app.epg.updateIfDue(appStart = true) }
        app.appScope.launch { runCatching { app.vod.refreshIfStale() } }
    }

    fun openSettings(page: String? = null) {
        val locked = settings.premium && settings.bool("parental.enabled") && settings.bool("parental.lock_settings")
        if (locked) { pendingSettingsPage = page; askPinForSettings = true } else push(Screen.Settings(page))
    }

    var lastBack by remember { mutableStateOf(0L) }
    var toast by remember { mutableStateOf<String?>(null) }
    BackHandler {
        when {
            stack.size > 1 -> pop()
            settings.bool("general.exit_confirm") -> {
                val now = System.currentTimeMillis()
                if (now - lastBack < 2500) onFinish() else { lastBack = now; toast = "Press Back again to exit" }
            }
            else -> onFinish()
        }
    }

    fun navigate(dest: MenuDest) {
        when (dest) {
            MenuDest.GUIDE -> while (stack.size > 1) pop()
            MenuDest.SEARCH -> push(Screen.Search)
            MenuDest.MOVIES -> push(Screen.Vod(com.novatv.app.playlist.VodKind.MOVIES))
            MenuDest.SHOWS -> push(Screen.Vod(com.novatv.app.playlist.VodKind.SHOWS))
            MenuDest.RECORDINGS -> push(Screen.Recordings)
            MenuDest.MY_TV_PROGRAMS -> push(Screen.Info("My TV programs", "No programs"))
            MenuDest.MY_REMINDERS -> push(Screen.Reminders)
            MenuDest.MY_MOVIES -> push(Screen.Vod(com.novatv.app.playlist.VodKind.MOVIES, com.novatv.app.ui.VOD_MY_LIST))
            MenuDest.MY_SHOWS -> push(Screen.Vod(com.novatv.app.playlist.VodKind.SHOWS, com.novatv.app.ui.VOD_MY_LIST))
            MenuDest.HISTORY -> push(Screen.History)
            MenuDest.MY_LIST -> push(Screen.Info("My list", "No programs"))
            MenuDest.SETTINGS -> openSettings()
        }
    }

    // Recordings start on time and reminders pop up while the app is open.
    // Appearance › Logos › Logos folder: needs permission to read pictures on the device.
    val logosFolder = settings.str("logos.folder").trim()
    LaunchedEffect(logosFolder) {
        if (logosFolder.isEmpty()) return@LaunchedEffect
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
            else android.Manifest.permission.READ_EXTERNAL_STORAGE
        val act = context as? android.app.Activity ?: return@LaunchedEffect
        if (androidx.core.content.ContextCompat.checkSelfPermission(act, perm) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            androidx.core.app.ActivityCompat.requestPermissions(act, arrayOf(perm), 11)
    }
    var dueReminder by remember { mutableStateOf<com.novatv.app.premium.Reminder?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            app.recordings.tick()
            val st = app.lastSettings
            if (dueReminder == null) app.reminders.takeDue(beforeMs = (st?.int("epg.reminder_before") ?: 0) * 60_000L)
                .firstOrNull()?.let { dueReminder = it }
            com.novatv.app.boot.WakeAlarms.scheduleNextReminder(context)
        }
    }

    CompositionLocalProvider(
        LocalOpenPremium provides { if (stack.lastOrNull() != Screen.Premium) push(Screen.Premium) },
        com.novatv.app.ui.LocalPlayUrl provides { title, url -> push(Screen.VodPlayer(listOf("url:$url" to url), listOf(title), 0, true)) },
        com.novatv.app.ui.LocalOpenMultiview provides { chs -> push(Screen.Multiview(chs.map { it.id })) },
    ) {
        when (val screen = stack.last()) {
            Screen.Home -> GuideScreen(
                settings = settings,
                onPlay = { queue, channel ->
                    app.fromGuidePreview = settings.bool("epg.show_preview")
                    app.playQueue = queue
                    push(Screen.Player(channel.id))
                },
                onNavigate = ::navigate,
                onAddPlaylist = { push(Screen.AddPlaylist(null)) },
            )
            Screen.Premium -> PremiumAccountScreen(settings, onClose = ::pop)
            Screen.GetPremium -> GetPremiumScreen(onClose = ::pop)
            Screen.Search -> SearchScreen(settings) { queue, channel ->
                app.playQueue = queue
                // Other › Search › "Stay on search screen when switching channels": otherwise Back from
                // the player goes to the guide, not back to Search.
                if (!settings.bool("search.stay")) pop()
                push(Screen.Player(channel.id))
            }
            is Screen.Info -> InfoScreen(screen.title, screen.text)
            is Screen.AddPlaylist -> AddPlaylistScreen(
                type = screen.type,
                onPickType = { t -> push(Screen.AddPlaylist(t)) },
                onFinished = { stack.clear(); stack.add(Screen.Home) },
            )
            is Screen.Settings -> {
                // TiviMate: Settings slides in from the right over the screen you came from.
                if (stack.getOrNull(stack.lastIndex - 1) == Screen.Home) com.novatv.app.ui.NoFocus {
                    GuideScreen(settings = settings, onPlay = { _, _ -> }, onNavigate = {}, onAddPlaylist = {}, background = true)
                }
                SettingsScreen(settings, screen.page, onClose = ::pop) { action ->
                when (action) {
                    SettingAction.ADD_PLAYLIST -> push(Screen.AddPlaylist(null))
                    SettingAction.PREMIUM_ACCOUNT -> push(Screen.Premium)
                    SettingAction.GET_PREMIUM -> push(Screen.GetPremium)
                    else -> Unit
                }
                }
            }
            Screen.Recordings -> com.novatv.app.ui.RecordingsScreen()
            Screen.History -> com.novatv.app.ui.HistoryScreen(settings) { queue, channel ->
                app.playQueue = queue
                push(Screen.Player(channel.id))
            }
            Screen.Reminders -> com.novatv.app.ui.RemindersScreen()
            is Screen.Multiview -> {
                val all = app.playQueue.ifEmpty { app.playlists.channels.value }
                val start = screen.channelIds.mapNotNull { id -> all.firstOrNull { it.id == id } }
                com.novatv.app.ui.MultiviewScreen(settings, start, all, onExit = ::pop)
            }
            is Screen.Vod -> com.novatv.app.ui.VodBrowseScreen(screen.kind, screen.start,
                onNavigate = { d -> if (d == MenuDest.SETTINGS || d == MenuDest.SEARCH) navigate(d) else { pop(); navigate(d) } }) { item ->
                push(if (screen.kind == com.novatv.app.playlist.VodKind.MOVIES) Screen.MovieDetails(item) else Screen.Series(item))
            }
            is Screen.MovieDetails -> com.novatv.app.ui.MovieDetailsScreen(screen.item) { fromStart ->
                app.vod.addHistory(screen.item.id)
                push(Screen.VodPlayer(listOf(screen.item.id to screen.item.url), listOf(screen.item.name), 0, fromStart))
            }
            is Screen.Series -> com.novatv.app.ui.SeriesScreen(screen.item) { eps, i ->
                app.vod.addHistory(screen.item.id)
                push(Screen.VodPlayer(eps.map { it.id to it.url },
                    eps.map { "${screen.item.name} · S${it.season} E${it.number} · ${it.title}" }, i, false))
            }
            is Screen.VodPlayer -> com.novatv.app.ui.VodPlayerScreen(settings, screen.items, screen.titles, screen.start,
                screen.fromStart, onExit = ::pop)
            is Screen.Player -> PlayerScreen(settings, screen.channelId, onExit = ::pop,
                onNavigate = { d -> pop(); navigate(d) },
                onFinishApp = onFinish)
        }
    }

    // New version on GitHub -> "Update available" pop-up (not over the full-screen player).
    com.novatv.app.update.UpdatePrompt(show = stack.last() !is Screen.Player && stack.last() !is Screen.VodPlayer)

    dueReminder?.let { r ->
        com.novatv.app.ui.ReminderPopup(r,
            timeoutSec = settings.int("reminders.popup_timeout"), defaultAction = settings.str("reminders.default_action"), onWatch = {
            dueReminder = null
            app.playlists.channels.value.firstOrNull { it.id == r.channelId }?.let { ch ->
                app.playQueue = app.playlists.channels.value
                push(Screen.Player(ch.id))
            }
        }, onDismiss = { dueReminder = null })
    }

    toast?.let { t ->
        LaunchedEffect(t) { delay(2500); toast = null }
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
            androidx.compose.material3.Text(t, color = Color.White, modifier = Modifier.padding(bottom = 48.dp)
                .background(Color(0xCC000000)).padding(horizontal = 18.dp, vertical = 10.dp))
        }
    }

    if (askPinForSettings) {
        PinDialog(settings.str("parental.pin"), onDismiss = { askPinForSettings = false }) {
            askPinForSettings = false
            push(Screen.Settings(pendingSettingsPage))
        }
    }
}
