package com.novatv.app

import android.app.Application
import android.content.Context
import com.novatv.app.epg.EpgRepository
import com.novatv.app.premium.LicenseManager
import com.novatv.app.playlist.Channel
import com.novatv.app.playlist.PlaylistRepository
import com.novatv.app.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    lateinit var settings: SettingsRepository
        private set
    lateinit var playlists: PlaylistRepository
        private set
    lateinit var epg: EpgRepository
        private set
    lateinit var license: LicenseManager
        private set
    lateinit var vod: com.novatv.app.playlist.VodRepository
        private set
    /** Live player shared by the full-screen player and the guide preview (seamless switching). */
    val shared by lazy { com.novatv.app.player.SharedPlayback(this, playlists.http) }
    lateinit var reminders: com.novatv.app.premium.ReminderStore
        private set
    lateinit var recordings: com.novatv.app.premium.RecordingManager
        private set

    /** Background work that should outlive a single screen (e.g. guide downloads). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The channel list the player flips through with Up/Down. */
    var playQueue: List<Channel> = emptyList()
    /** True while the full-screen player is open (for picture-in-picture on Home). */
    @Volatile var playerActive: Boolean = false
    /** Channel last watched in the player (the guide marks it with ▶). */
    @Volatile var lastPlayedId: String? = null
    @Volatile var guideHintsShown: Boolean = false
    /** Channel groups the guide showed last (shown instantly when the guide opens again). */
    @Volatile var guideGroups: List<com.novatv.app.playlist.ChannelGroup> = emptyList()
    /** Where the guide was (group and channel row), so it reopens exactly there with no blank frame. */
    @Volatile var guideGroupIndex: Int = -1
    @Volatile var guideRow: Int = 0
    /** Group the playing channel was started from (the guide goes back to it after full screen). */
    @Volatile var playGroupIndex: Int = -1
    /** Sleep timer: when the app closes (0 = off). */
    @Volatile var sleepAt: Long = 0L
    /** Settings › Playlists › Update all playlists: spinning while it runs, then the result under it (TiviMate). */
    val playlistsUpdating = androidx.compose.runtime.mutableStateOf(false)
    val playlistsUpdateNote = androidx.compose.runtime.mutableStateOf<String?>(null)
    /** Search box text and the result you opened (Back from it returns to the same search). */
    @Volatile var searchQuery: String = ""
    @Volatile var searchFocus: String? = null
    /** The guide's menu item that opened the current screen (the guide reopens with the menu on it). */
    @Volatile var guideMenuReturn: com.novatv.app.ui.MenuDest? = null
    /** Latest settings, for code outside Compose. */
    @Volatile var lastSettings: com.novatv.app.settings.AppSettings? = null
    /** Set by the player to open the TV guide with the groups list showing. */
    @Volatile var openGuideGroups: Boolean = false
    /** The player was opened from the guide's preview window (for the animated grow-to-full-screen). */
    @Volatile var fromGuidePreview: Boolean = false
    /** When the parental PIN was last entered (Parental controls › Don't require PIN after unlocking). */
    @Volatile var pinUnlockedAt: Long = 0L

    override fun onCreate() {
        super.onCreate()
        // If the app ever closes by itself, keep what went wrong so it can be shown on the next start.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                java.io.File(filesDir, "last_crash.txt").writeText(
                    "Version ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()}\n" +
                    java.text.DateFormat.getDateTimeInstance().format(java.util.Date()) + "\n\n" + android.util.Log.getStackTraceString(e))
            }
            previous?.uncaughtException(t, e)
        }
        settings = SettingsRepository(this)
        playlists = PlaylistRepository(this, settings)
        epg = EpgRepository(this, settings, playlists)
        license = LicenseManager(settings, playlists.http)
        vod = com.novatv.app.playlist.VodRepository(this, playlists.http, playlists)
        reminders = com.novatv.app.premium.ReminderStore(this)
        recordings = com.novatv.app.premium.RecordingManager(this, playlists, appScope)
        com.novatv.app.boot.WakeAlarms.registerScreenOn(this)
        // One time: put every remote button back to the TiviMate defaults (fixes remotes that only scrolled the guide).
        appScope.launch {
            val s = settings.current()
            if (s.str("data.remote_reset_v1") != "done") {
                com.novatv.app.settings.RemoteKeys.GUIDE_KEYS.forEach { settings.set(com.novatv.app.settings.RemoteKeys.guideKey(it.id), it.default) }
                com.novatv.app.settings.RemoteKeys.PLAYER_KEYS.forEach { settings.set(com.novatv.app.settings.RemoteKeys.playerKey(it.id), it.default) }
                settings.set("data.remote_reset_v1", "done")
            }
            // One time: groups exactly like TiviMate (provider order, no "Recently watched" group).
            if (settings.current().str("data.groups_like_tivimate_v1") != "done") {
                settings.set("general.recent_group", "false")
                settings.set("groups.sort", "playlist")
                settings.set("data.groups_like_tivimate_v1", "done")
            }
            // One time: Search starts listening as soon as it opens (TiviMate).
            if (settings.current().str("data.voice_search_v1") != "done") {
                settings.set("search.voice", "true")
                settings.set("data.voice_search_v1", "done")
            }
            // One time: the dark black / dark grey look (like TiviMate) for everyone.
            if (settings.current().str("data.theme_dark_v1") != "done") {
                settings.set("appearance.theme", "dark")
                settings.set("data.theme_dark_v1", "done")
            }
            // One time: the channel info panel shows at the bottom (like TiviMate) for everyone.
            if (settings.current().str("data.info_bottom_v1") != "done") {
                settings.set("player.info_bottom", "true")
                settings.set("data.info_bottom_v1", "done")
            }
        }
        // Settings → TV guide → "Update when a playlist is updated"
        playlists.onChannelsUpdated = {
            if (settings.current().bool("epg.update_on_playlist_change")) appScope.launch { epg.update(skipIfNewerThanMs = 10 * 60_000L) }
            appScope.launch { runCatching { vod.refreshIfStale() } }
        }
    }
}

val Context.app: App get() = applicationContext as App
