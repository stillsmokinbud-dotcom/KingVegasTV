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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by app.settings.settings.collectAsState(initial = null)
            val s = settings
            if (s == null) {
                Box(Modifier.fillMaxSize().background(Color.Black))
            } else {
                app.lastSettings = s
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
        app.playlists.refreshDue(appStart = true)
        app.appScope.launch { app.epg.updateIfDue(appStart = true) }
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
            MenuDest.MOVIES -> push(Screen.Info("Movies", "No movies yet. Movies from your Xtream Codes playlist are coming in the next update."))
            MenuDest.SHOWS -> push(Screen.Info("Shows", "No shows yet. Series from your Xtream Codes playlist are coming in the next update."))
            MenuDest.RECORDINGS -> push(Screen.Info("Recordings", "No recordings"))
            MenuDest.MY_TV_PROGRAMS -> push(Screen.Info("My TV programs", "No programs"))
            MenuDest.MY_REMINDERS -> push(Screen.Info("My reminders", "No reminders"))
            MenuDest.MY_MOVIES -> push(Screen.Info("My movies", "No movies"))
            MenuDest.MY_SHOWS -> push(Screen.Info("My shows", "No shows"))
            MenuDest.HISTORY -> push(Screen.Info("History", "Recently watched channels appear in the TV guide under \"Recently watched\"."))
            MenuDest.MY_LIST -> push(Screen.Info("My list", "No programs"))
            MenuDest.SETTINGS -> openSettings()
        }
    }

    CompositionLocalProvider(LocalOpenPremium provides { if (stack.lastOrNull() != Screen.Premium) push(Screen.Premium) }) {
        when (val screen = stack.last()) {
            Screen.Home -> GuideScreen(
                settings = settings,
                onPlay = { queue, channel ->
                    app.playQueue = queue
                    push(Screen.Player(channel.id))
                },
                onNavigate = ::navigate,
                onAddPlaylist = { push(Screen.AddPlaylist(null)) },
            )
            Screen.Premium -> PremiumAccountScreen(settings)
            Screen.GetPremium -> GetPremiumScreen()
            Screen.Search -> SearchScreen(settings) { queue, channel ->
                app.playQueue = queue
                push(Screen.Player(channel.id))
            }
            is Screen.Info -> InfoScreen(screen.title, screen.text)
            is Screen.AddPlaylist -> AddPlaylistScreen(
                type = screen.type,
                onPickType = { t -> push(Screen.AddPlaylist(t)) },
                onFinished = { stack.clear(); stack.add(Screen.Home) },
            )
            is Screen.Settings -> SettingsScreen(settings, screen.page, onClose = ::pop) { action ->
                when (action) {
                    SettingAction.ADD_PLAYLIST -> push(Screen.AddPlaylist(null))
                    SettingAction.PREMIUM_ACCOUNT -> push(Screen.Premium)
                    SettingAction.GET_PREMIUM -> push(Screen.GetPremium)
                    else -> Unit
                }
            }
            is Screen.Player -> PlayerScreen(settings, screen.channelId, onExit = ::pop,
                onNavigate = { d -> pop(); navigate(d) },
                onFinishApp = onFinish)
        }
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
