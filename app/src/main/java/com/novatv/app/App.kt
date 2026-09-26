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

    /** Background work that should outlive a single screen (e.g. guide downloads). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The channel list the player flips through with Up/Down. */
    var playQueue: List<Channel> = emptyList()
    /** True while the full-screen player is open (for picture-in-picture on Home). */
    @Volatile var playerActive: Boolean = false
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
        // Settings → TV guide → "Update when a playlist is updated"
        playlists.onChannelsUpdated = {
            if (settings.current().bool("epg.update_on_playlist_change")) appScope.launch { epg.update() }
            appScope.launch { vod.refresh() }
        }
    }
}

val Context.app: App get() = applicationContext as App
