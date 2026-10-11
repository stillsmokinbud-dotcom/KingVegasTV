package com.novatv.app.playlist

import com.novatv.app.settings.AppSettings
import com.novatv.app.settings.DataKeys
import com.novatv.app.settings.SettingsRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The user's own changes to channels (Premium, like TiviMate): hidden channels, renamed channels,
 * channels moved into their own order inside a group, and the user's own categories.
 * Everything is kept in the settings store (so it is part of Backup / Restore).
 */
object ChannelEdits {
    private const val SEP = "\u0001"
    private const val IDS = "\u0002"
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(String.serializer())

    private fun list(s: AppSettings, key: String): List<String> =
        s.raw[key]?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: emptyList()

    private fun pairs(s: AppSettings, key: String): List<Pair<String, List<String>>> = list(s, key).mapNotNull { e ->
        val parts = e.split(SEP, limit = 2)
        if (parts.size != 2) null else parts[0] to parts[1].split(IDS).filter { it.isNotEmpty() }
    }

    fun hidden(s: AppSettings): Set<String> = list(s, DataKeys.HIDDEN_CHANNELS).toSet()

    fun names(s: AppSettings): Map<String, String> = list(s, DataKeys.CHANNEL_NAMES).mapNotNull { e ->
        val parts = e.split(SEP, limit = 2)
        if (parts.size != 2 || parts[1].isBlank()) null else parts[0] to parts[1]
    }.toMap()

    /** Group name -> channel ids in the user's order. */
    fun orders(s: AppSettings): Map<String, List<String>> = pairs(s, DataKeys.CHANNEL_ORDER).toMap()

    /** The user's own categories, in the order they were made: name -> channel ids. */
    fun customGroups(s: AppSettings): List<Pair<String, List<String>>> = pairs(s, DataKeys.CUSTOM_GROUPS)

    /** Renames applied; hidden channels left out unless "Show hidden channels" is on. */
    fun apply(all: List<Channel>, s: AppSettings): List<Channel> {
        val names = names(s)
        val hidden = if (s.bool("channels.show_hidden")) emptySet() else hidden(s)
        if (names.isEmpty() && hidden.isEmpty()) return all
        return all.mapNotNull { c ->
            if (c.id in hidden) null else names[c.id]?.let { c.copy(name = it) } ?: c
        }
    }

    /** A group's channels in the user's order (channels never moved keep their place after the moved ones' slots). */
    fun ordered(group: String, list: List<Channel>, s: AppSettings, orders: Map<String, List<String>> = orders(s)): List<Channel> {
        val order = orders[group] ?: return list
        val pos = order.withIndex().associate { (i, id) -> id to i }
        // Stable: channels with a saved place by that place, new channels at the end in their original order.
        return list.withIndex().sortedWith(compareBy({ pos[it.value.id] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
    }

    // ---------------------------------------------------------------- changes

    suspend fun toggleHidden(repo: SettingsRepository, id: String) = repo.toggleInList(DataKeys.HIDDEN_CHANNELS, id)

    /** Blank name = back to the playlist's own name. */
    suspend fun rename(repo: SettingsRepository, id: String, name: String) {
        val rest = repo.getList(DataKeys.CHANNEL_NAMES).filterNot { it.startsWith(id + SEP) }
        repo.setList(DataKeys.CHANNEL_NAMES, if (name.isBlank()) rest else rest + (id + SEP + name.trim()))
    }

    /**
     * Moves [id] one place up (-1) or down (+1) in [group], whose channels are shown as [shown].
     * Favorites and the user's own categories keep their order in their own lists.
     */
    suspend fun move(repo: SettingsRepository, group: String, shown: List<String>, id: String, by: Int) {
        val from = shown.indexOf(id)
        val to = from + by
        if (from < 0 || to !in shown.indices) return
        val order = shown.toMutableList().apply { add(to, removeAt(from)) }
        when {
            group == "Favorites" -> {
                // Keep favorites that aren't on screen (hidden, other playlists) after the visible ones.
                val all = repo.getList(DataKeys.FAVORITES)
                repo.setList(DataKeys.FAVORITES, order + all.filter { it !in order })
            }
            repo.getList(DataKeys.CUSTOM_GROUPS).any { it.startsWith(group + SEP) } -> setGroup(repo, group, order)
            else -> {
                val rest = repo.getList(DataKeys.CHANNEL_ORDER).filterNot { it.startsWith(group + SEP) }
                repo.setList(DataKeys.CHANNEL_ORDER, rest + (group + SEP + order.joinToString(IDS)))
            }
        }
    }

    private suspend fun setGroup(repo: SettingsRepository, group: String, ids: List<String>) {
        val all = repo.getList(DataKeys.CUSTOM_GROUPS)
        val entry = group + SEP + ids.joinToString(IDS)
        val i = all.indexOfFirst { it.startsWith(group + SEP) }
        repo.setList(DataKeys.CUSTOM_GROUPS, if (i < 0) all + entry else all.toMutableList().apply { set(i, entry) })
    }

    suspend fun addToGroup(repo: SettingsRepository, group: String, id: String) {
        val name = group.trim().ifBlank { return }
        val current = repo.getList(DataKeys.CUSTOM_GROUPS).firstOrNull { it.startsWith(name + SEP) }
            ?.substringAfter(SEP)?.split(IDS)?.filter { it.isNotEmpty() }.orEmpty()
        if (id !in current) setGroup(repo, name, current + id)
    }

    suspend fun removeFromGroup(repo: SettingsRepository, group: String, id: String) {
        val current = repo.getList(DataKeys.CUSTOM_GROUPS).firstOrNull { it.startsWith(group + SEP) }
            ?.substringAfter(SEP)?.split(IDS)?.filter { it.isNotEmpty() } ?: return
        setGroup(repo, group, current - id)
    }

    suspend fun deleteGroup(repo: SettingsRepository, group: String) =
        repo.setList(DataKeys.CUSTOM_GROUPS, repo.getList(DataKeys.CUSTOM_GROUPS).filterNot { it.startsWith(group + SEP) })
}
