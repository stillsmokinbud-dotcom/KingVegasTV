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
    /** Latest settings, for code outside Compose. */
    @Volatile var lastSettings: com.novatv.app.settings.AppSettings? = null
    /** Set by the player to open the TV guide with the groups list showing. */
    @Volatile var openGuideGroups: Boolean = false

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        playlists = PlaylistRepository(this, settings)
        epg = EpgRepository(this, settings, playlists)
        license = LicenseManager(settings, playlists.http)
        vod = com.novatv.app.playlist.VodRepository(this, playlists.http, playlists)
        reminders = com.novatv.app.premium.ReminderStore(this)
        recordings = com.novatv.app.premium.RecordingManager(this, playlists, appScope)
        // Settings → TV guide → "Update when a playlist is updated"
        playlists.onChannelsUpdated = {
            if (settings.current().bool("epg.update_on_playlist_change")) appScope.launch { epg.update(skipIfNewerThanMs = 10 * 60_000L) }
            appScope.launch { runCatching { vod.refreshIfStale() } }
        }
    }
}

val Context.app: App get() = applicationContext as App
