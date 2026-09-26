package com.novatv.app.playlist

import kotlinx.serialization.Serializable

@Serializable
enum class PlaylistType(val label: String) {
    M3U_URL("M3U playlist (URL)"),
    M3U_FILE("M3U playlist (file)"),
    XTREAM("Xtream Codes"),
    STALKER("Stalker portal"),
}

/** One playlist and its own settings (TiviMate keeps these per playlist). */
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val type: PlaylistType,
    /** M3U URL, content:// URI of an M3U file, Xtream server URL or Stalker portal URL. */
    val url: String = "",
    val username: String = "",
    val password: String = "",
    /** Stalker only. */
    val mac: String = "",
    /** Overrides the default user agent for this playlist's streams. */
    val userAgent: String = "",
    /** Use the guide URL that comes with the playlist (x-tvg-url / Xtream xmltv.php). */
    val useProviderEpg: Boolean = true,
    val updateOnStart: Boolean = true,
    /** 0 = manual only. */
    val autoUpdateHours: Int = 24,
    /** Xtream only: "ts" or "m3u8". */
    val xtreamOutput: String = "ts",
    /** "auto", "default", "append", "shift", "flussonic", "xc". */
    val catchupMode: String = "auto",
    val enabled: Boolean = true,
    val lastUpdated: Long = 0,
    /** Xtream: also load movies and series (shown in Movies / TV Shows). */
    val includeVod: Boolean = true,
    /** Overrides the playlist's own TV guide link when set. */
    val epgUrl: String = "",
    /** "default" (follow Appearance › Logos), "playlist", "epg" or "folder". */
    val logosPriority: String = "default",
    /** Catch-up duration override in days; 0 = what the provider says. */
    val catchupDays: Int = 0,
    /** Catch-up start time offset, h:mm:ss. */
    val catchupOffset: String = "0:00:00",
    /** Xtream: load live TV channels. */
    val includeLive: Boolean = true,
    /** "default", "playlist", "name". */
    val groupsSort: String = "default",
    /** New groups found on update are shown (on) or hidden (off). */
    val showNewGroups: Boolean = true,
    /** Groups seen on the last update (to spot new ones). */
    val knownGroups: List<String> = emptyList(),
    /** Global EPG sources switched off for this playlist. */
    val disabledEpgSources: List<String> = emptyList(),
    /** Xtream account info from the last update. */
    val expDate: Long = 0,
    val maxConnections: Int = 0,
)

@Serializable
data class Channel(
    /** Stable id: playlistId + stream url hash (or Xtream stream id). */
    val id: String,
    val playlistId: String,
    val name: String,
    val number: Int? = null,
    val group: String = "Uncategorized",
    val logo: String? = null,
    val url: String,
    val epgId: String? = null,
    val catchupDays: Int = 0,
    val catchupSource: String? = null,
    val userAgent: String? = null,
    val isAdult: Boolean = false,
)

@Serializable
data class PlaylistContent(
    val channels: List<Channel>,
    /** Guide URL the playlist advertises, if any. */
    val epgUrl: String? = null,
    /** Xtream account info (0 = unknown). */
    val expDate: Long = 0,
    val maxConnections: Int = 0,
)

data class ChannelGroup(
    val name: String,
    val channels: List<Channel>,
    val locked: Boolean = false,
)
