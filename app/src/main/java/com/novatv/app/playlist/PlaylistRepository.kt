package com.novatv.app.playlist

import android.content.Context
import android.net.Uri
import com.novatv.app.settings.AppSettings
import com.novatv.app.settings.DataKeys
import com.novatv.app.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

const val DEFAULT_USER_AGENT = "KingVegasTV/0.1 (Linux; Android TV)"

class PlaylistRepository(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val playlistListSer = ListSerializer(Playlist.serializer())

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val xtream = XtreamClient(http)

    /** Called after a successful update (the app uses it to refresh the TV guide). */
    var onChannelsUpdated: (suspend () -> Unit)? = null

    /** Channels of all enabled playlists, loaded from cache or network. */
    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    /** Human-readable loading / error message, null when idle. */
    val status: StateFlow<String?> = _status.asStateFlow()

    // ---------- Playlist CRUD ----------

    val playlists: Flow<List<Playlist>> = settingsRepo.settings.map { decodePlaylists(it.raw[DataKeys.PLAYLISTS]) }

    fun decodePlaylists(raw: String?): List<Playlist> =
        raw?.let { runCatching { json.decodeFromString(playlistListSer, it) }.getOrNull() } ?: emptyList()

    suspend fun readPlaylists(): List<Playlist> = decodePlaylists(settingsRepo.current().raw[DataKeys.PLAYLISTS])

    private suspend fun writePlaylists(list: List<Playlist>) =
        settingsRepo.set(DataKeys.PLAYLISTS, json.encodeToString(playlistListSer, list))

    suspend fun save(p: Playlist) {
        val list = readPlaylists()
        writePlaylists(if (list.any { it.id == p.id }) list.map { if (it.id == p.id) p else it } else list + p)
    }

    suspend fun delete(id: String) {
        writePlaylists(readPlaylists().filterNot { it.id == id })
        cacheFile(id).delete()
        reloadFromCache()
    }

    fun newId(): String = UUID.randomUUID().toString().take(8)

    // ---------- Loading ----------

    /** Fast start: show cached channels immediately. */
    suspend fun reloadFromCache() = withContext(Dispatchers.IO) {
        val enabled = readPlaylists().filter { it.enabled }
        _channels.value = enabled.flatMap { p -> readCache(p.id)?.channels.orEmpty() }
        loadStats()
    }

    /** Channels per playlist (for Settings › Playlists); counted from memory, no file reads. */
    fun channelCount(playlistId: String): Int = _channels.value.count { it.playlistId == playlistId }

    /** Group names of one playlist (for Manage groups). */
    fun groupsOf(playlistId: String): List<String> =
        _channels.value.asSequence().filter { it.playlistId == playlistId }.map { it.group }.distinct().toList()

    /** Download and parse playlists. Pass null to refresh all enabled playlists. */
    suspend fun refresh(onlyId: String? = null): Result<Int> = withContext(Dispatchers.IO) {
        val settings = settingsRepo.current()
        val targets = readPlaylists().filter { it.enabled && (onlyId == null || it.id == onlyId) }
        var total = 0
        val errors = mutableListOf<String>()
        for (p in targets) {
            _status.value = "Updating ${p.name}…"
            try {
                val content = download(p, settings)
                writeCache(p.id, content)
                val groupsNow = content.channels.map { it.group }.distinct()
                // "Show newly added groups" off: hide groups that weren't there last time.
                if (!p.showNewGroups && p.knownGroups.isNotEmpty()) {
                    val fresh = groupsNow.filter { it !in p.knownGroups }
                    if (fresh.isNotEmpty()) settingsRepo.setList(DataKeys.HIDDEN_GROUPS,
                        (settingsRepo.getList(DataKeys.HIDDEN_GROUPS) + fresh).distinct())
                }
                save(p.copy(lastUpdated = System.currentTimeMillis(), knownGroups = groupsNow,
                    expDate = content.expDate.takeIf { it > 0 } ?: p.expDate,
                    maxConnections = content.maxConnections.takeIf { it > 0 } ?: p.maxConnections))
                total += content.channels.size
            } catch (e: Exception) {
                errors += "${p.name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        reloadFromCache()
        _status.value = errors.takeIf { it.isNotEmpty() }?.joinToString("\n")
        if (total > 0) onChannelsUpdated?.invoke()
        if (errors.isNotEmpty() && total == 0) Result.failure(IOException(errors.joinToString("\n")))
        else Result.success(total)
    }

    /** Refresh playlists whose own schedule says they're due (or that update on start). */
    suspend fun refreshDue(appStart: Boolean) {
        val now = System.currentTimeMillis()
        readPlaylists().filter { it.enabled }.forEach { p ->
            val due = p.autoUpdateHours > 0 && now - p.lastUpdated > p.autoUpdateHours * 3_600_000L
            if ((appStart && p.updateOnStart) || due || readCache(p.id) == null) refresh(p.id)
        }
    }

    fun userAgentFor(p: Playlist?, settings: AppSettings): String =
        p?.userAgent?.takeIf { it.isNotBlank() }
            ?: settings.str("playback.user_agent").takeIf { it.isNotBlank() }
            ?: DEFAULT_USER_AGENT

    private fun download(p: Playlist, settings: AppSettings): PlaylistContent {
        val ua = userAgentFor(p, settings)
        return when (p.type) {
            PlaylistType.M3U_URL -> {
                val req = Request.Builder().url(p.url).header("User-Agent", ua).build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("Server returned ${resp.code}")
                    val body = resp.body ?: throw IOException("Empty playlist")
                    M3uParser.parse(body.charStream().buffered(), p.id)
                }
            }
            PlaylistType.M3U_FILE -> {
                val stream = context.contentResolver.openInputStream(Uri.parse(p.url))
                    ?: throw IOException("Can't open the playlist file")
                stream.bufferedReader().use { M3uParser.parse(it, p.id) }
            }
            PlaylistType.XTREAM -> xtream.loadLive(p, ua)
            PlaylistType.STALKER -> throw IOException("Stalker portals aren't supported yet")
        }
    }

    // ---------- Cache ----------

    private fun cacheFile(id: String) = File(context.filesDir, "playlist_$id.json")

    private fun writeCache(id: String, content: PlaylistContent) =
        cacheFile(id).writeText(json.encodeToString(PlaylistContent.serializer(), content))

    private fun readCache(id: String): PlaylistContent? = cacheFile(id).takeIf { it.exists() }?.let {
        runCatching { json.decodeFromString(PlaylistContent.serializer(), it.readText()) }.getOrNull()
    }

    /** Global EPG sources, minus those switched off in every enabled playlist. */
    suspend fun assignedGlobalSources(global: List<String>): List<String> {
        val enabled = readPlaylists().filter { it.enabled }
        if (enabled.isEmpty()) return global
        return global.filter { src -> enabled.any { src !in it.disabledEpgSources } }
    }

    /** Guide URLs advertised by playlists (for the EPG loader). */
    suspend fun providerEpgUrls(): List<String> = withContext(Dispatchers.IO) {
        readPlaylists().filter { it.enabled }.mapNotNull { p ->
            p.epgUrl.takeIf { it.isNotBlank() } ?: if (p.useProviderEpg) readCache(p.id)?.epgUrl else null
        }
    }

    /** Name of the channel's playlist (for the player's info panel). */
    fun playlistNameFor(channel: Channel, s: AppSettings): String? =
        decodePlaylists(s.raw[DataKeys.PLAYLISTS]).firstOrNull { it.id == channel.playlistId }?.name

    suspend fun playlistFor(channel: Channel): Playlist? =
        readPlaylists().firstOrNull { it.id == channel.playlistId }

    // ---------- Organising channels (applies the Channels settings) ----------

    suspend fun organize(settings: AppSettings): List<ChannelGroup> {
        val hidden = settingsRepo.getList(DataKeys.HIDDEN_GROUPS).toSet()
        val lockedList = settingsRepo.getList(DataKeys.LOCKED_GROUPS).toSet()
        val favorites = settingsRepo.getList(DataKeys.FAVORITES)
        val recent = settingsRepo.getList(DataKeys.RECENT)
        return organize(_channels.value, settings, hidden, lockedList, favorites, recent)
    }

    fun organize(
        all: List<Channel>,
        s: AppSettings,
        hiddenGroups: Set<String>,
        lockedGroups: Set<String>,
        favorites: List<String>,
        recent: List<String>,
    ): List<ChannelGroup> {
        val editor = s.premium && s.bool("channels.names_editor")
        fun csv(key: String) = if (editor) s.str(key).split(',').map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
        val prefixes = csv("channels.strip_prefix")
        val suffixes = csv("channels.strip_suffix")
        val parental = s.premium && s.bool("parental.enabled")
        val lockAdult = parental && s.bool("parental.lock_adult")
        val hideLocked = parental && s.bool("parental.hide_locked")
        val showHidden = s.bool("channels.show_hidden")

        fun clean(name: String): String {
            var n = name.trim()
            prefixes.forEach { if (n.startsWith(it, ignoreCase = true)) n = n.substring(it.length).trim() }
            suffixes.forEach { if (n.endsWith(it, ignoreCase = true)) n = n.substring(0, n.length - it.length).trim() }
            return n.ifEmpty { name }
        }

        var channels = all.map { if (prefixes.isEmpty() && suffixes.isEmpty()) it else it.copy(name = clean(it.name)) }
        channels = when (s.effective("channels.sort")) {
            "name" -> channels.sortedBy { it.name.lowercase() }
            "date_added" -> {
                val seen = firstSeen
                channels.sortedByDescending { seen[it.id] ?: 0L }
            }
            "watch_time" -> {
                val w = watchTime
                channels.sortedByDescending { w[it.id] ?: 0L }
            }
            else -> channels
        }
        if (s.effective("channels.fav_first") == "true") {
            val fav = favorites.toSet()
            channels = channels.sortedBy { if (it.id in fav) 0 else 1 }
        }

        val numbering = s.str("channels.numbering")
        if (numbering == "sequential") channels = channels.mapIndexed { i, c -> c.copy(number = i + 1) }

        fun isLocked(group: String, list: List<Channel>) =
            parental && (group in lockedGroups || (lockAdult && list.any { it.isAdult }))

        // Several playlists: Appearance › Groups › Show "All playlists" category merges groups with the same
        // name from every playlist; off, each playlist keeps its own groups ("Playlist · Group").
        val pls = decodePlaylists(s.raw[DataKeys.PLAYLISTS])
        val multi = pls.size > 1
        val plName = pls.associate { it.id to it.name }
        val plOrder = pls.mapIndexed { i, p -> p.id to i }.toMap()
        var groupedMap = if (multi && !s.bool("groups.show_all_playlists"))
            channels.groupBy { "${plName[it.playlistId] ?: "Playlist"} · ${it.group}" }
        else channels.groupBy { it.group }
        if (s.str("groups.sort") == "name") groupedMap = groupedMap.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        val grouped = groupedMap
            .filter { (g, _) -> showHidden || g !in hiddenGroups }
            .map { (g, list) ->
                val numbered = if (numbering == "per_group") list.mapIndexed { i, c -> c.copy(number = i + 1) } else list
                ChannelGroup(g, numbered, locked = isLocked(g, list))
            }
            .filterNot { hideLocked && it.locked }

        val byId = channels.associateBy { it.id }
        val result = mutableListOf<ChannelGroup>()
        val favChannels = favorites.mapNotNull { byId[it] }
        if (s.bool("groups.show_favorites") && s.premium) result += ChannelGroup("Favorites", favChannels)
        if (s.bool("general.recent_group")) {
            val rec = recent.mapNotNull { byId[it] }.take(s.int("general.recent_count"))
            if (rec.isNotEmpty()) result += ChannelGroup("Recently watched", rec)
        }
        if (s.bool("channels.show_all_group")) {
            var visible = grouped.filterNot { it.locked }.flatMap { it.channels }
            // Channels › "Group channels by playlists in All playlists category"
            if (multi && s.bool("channels.group_by_playlist")) visible = visible.sortedBy { plOrder[it.playlistId] ?: 0 }
            result += ChannelGroup(if (multi) "All playlists" else "All channels", visible)
        }
        result += grouped
        return result
    }

    // ---------- Watch time and date added (for Channels sorting) ----------

    /** channel id -> seconds watched. */
    @Volatile var watchTime: Map<String, Long> = emptyMap(); private set
    /** channel id -> first time the channel appeared. */
    @Volatile var firstSeen: Map<String, Long> = emptyMap(); private set
    private val statsFile get() = File(context.filesDir, "channel_stats.json")
    private val statsSer = MapSerializer(String.serializer(), MapSerializer(String.serializer(), Long.serializer()))

    suspend fun loadStats() = withContext(Dispatchers.IO) {
        runCatching {
            val m = json.decodeFromString(statsSer, statsFile.readText())
            watchTime = m["watch"].orEmpty(); firstSeen = m["seen"].orEmpty()
        }
        val now = System.currentTimeMillis()
        val missing = _channels.value.filter { it.id !in firstSeen }
        if (missing.isNotEmpty()) { firstSeen = firstSeen + missing.associate { it.id to now }; saveStats() }
    }

    private fun saveStats() = runCatching {
        statsFile.writeText(json.encodeToString(statsSer, mapOf("watch" to watchTime, "seen" to firstSeen)))
    }

    suspend fun addWatchTime(channelId: String, seconds: Long) = withContext(Dispatchers.IO) {
        watchTime = watchTime + (channelId to (watchTime[channelId] ?: 0L) + seconds)
        saveStats()
    }

    suspend fun resetWatchTime() = withContext(Dispatchers.IO) { watchTime = emptyMap(); saveStats() }

    /** All group names across playlists (for the Hide groups / Locked groups screens). */
    fun allGroupNames(): List<String> = _channels.value.map { it.group }.distinct()

    /**
     * History (TiviMate): the programs you watched, newest first, as
     * "channelId|programStart|programEnd|watchedAt|title" (no program info: start = end = 0).
     */
    suspend fun addHistory(channel: Channel, program: com.novatv.app.epg.Program?) {
        val now = System.currentTimeMillis()
        val start = program?.start ?: 0L
        val entry = "${channel.id}|$start|${program?.end ?: 0L}|$now|${program?.title ?: channel.name}"
        val old = settingsRepo.getList(DataKeys.HISTORY).filterNot {
            val p = it.split('|', limit = 5)
            p.getOrNull(0) == channel.id && p.getOrNull(1) == start.toString()
        }
        settingsRepo.setList(DataKeys.HISTORY, (listOf(entry) + old).take(500))
    }

    suspend fun markWatched(channel: Channel) {
        val recent = listOf(channel.id) + settingsRepo.getList(DataKeys.RECENT).filterNot { it == channel.id }
        settingsRepo.setList(DataKeys.RECENT, recent.take(50))
        settingsRepo.set(DataKeys.LAST_CHANNEL, channel.id)
    }

    suspend fun lastChannel(): Channel? {
        val id = settingsRepo.current().raw[DataKeys.LAST_CHANNEL] ?: return null
        return _channels.value.firstOrNull { it.id == id }
    }

    /** Backup JSON with Xtream / SMB passwords removed when the user asks for that. */
    suspend fun playlistsWithoutPasswords(): String =
        json.encodeToString(playlistListSer, readPlaylists().map { it.copy(password = "") })
}
