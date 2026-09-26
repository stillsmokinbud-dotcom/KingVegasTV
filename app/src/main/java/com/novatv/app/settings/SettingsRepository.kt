package com.novatv.app.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Keys for app data that isn't a user-facing setting (stored as JSON). */
object DataKeys {
    const val PLAYLISTS = "data.playlists"
    const val EPG_SOURCES = "data.epg_sources"
    const val HIDDEN_GROUPS = "data.hidden_groups"
    const val LOCKED_GROUPS = "data.locked_groups"
    const val FAVORITES = "data.favorites"
    const val RECENT = "data.recent"
    const val LAST_CHANNEL = "data.last_channel"
    const val MENU_ORDER = "data.menu_order"
}

/** Typed, read-only view of all settings, with schema defaults filled in. */
class AppSettings(val raw: Map<String, String>) {
    fun str(key: String): String = raw[key] ?: SettingsSchema.defaultValue(key)
    fun bool(key: String): Boolean = str(key).toBooleanStrictOrNull() ?: false
    fun int(key: String): Int = str(key).toIntOrNull() ?: SettingsSchema.defaultValue(key).toIntOrNull() ?: 0

    /**
     * Premium comes from the signed-in account (see LicenseManager). Test (debug) builds also
     * have a developer switch so you can try Premium features without a server.
     */
    val premium: Boolean get() = bool("license.premium") || (com.novatv.app.BuildConfig.DEBUG && bool("premium.dev_unlock"))

    /** A premium setting falls back to its default when Premium is locked. */
    fun effective(key: String): String {
        val item = SettingsSchema.allItems[key]
        return if (item != null && item.premium && !premium) SettingsSchema.defaultValue(key) else str(key)
    }
}

class SettingsRepository(context: Context) {

    private val store = context.applicationContext.dataStore
    private val json = Json { ignoreUnknownKeys = true }
    private val stringList = ListSerializer(String.serializer())

    val settings: Flow<AppSettings> = store.data.map { prefs -> AppSettings(prefs.toStringMap()) }

    suspend fun current(): AppSettings = settings.first()

    suspend fun set(key: String, value: String) {
        store.edit { it[stringPreferencesKey(key)] = value }
    }

    // ---- String lists (EPG sources, hidden groups, favorites, ...) ----

    fun listFlow(key: String): Flow<List<String>> = store.data.map { prefs ->
        prefs[stringPreferencesKey(key)]?.let { decodeList(it) } ?: emptyList()
    }

    suspend fun getList(key: String): List<String> = listFlow(key).first()

    suspend fun setList(key: String, values: List<String>) =
        set(key, json.encodeToString(stringList, values))

    suspend fun toggleInList(key: String, value: String) {
        val list = getList(key)
        setList(key, if (value in list) list - value else list + value)
    }

    private fun decodeList(raw: String): List<String> =
        runCatching { json.decodeFromString(stringList, raw) }.getOrDefault(emptyList())

    // ---- Backup / restore ----

    suspend fun exportAll(includePasswords: Boolean, playlistsOverride: String? = null): String {
        var map = store.data.first().toStringMap()
        if (!includePasswords) {
            map = map.filterKeys { !it.endsWith("_pass") && it != "parental.pin" }
        }
        if (playlistsOverride != null) map = map + (DataKeys.PLAYLISTS to playlistsOverride)
        return json.encodeToString(MapSerializer(String.serializer(), String.serializer()), map)
    }

    suspend fun importAll(backupJson: String) {
        val map = json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), backupJson)
        store.edit { prefs ->
            map.forEach { (k, v) -> prefs[stringPreferencesKey(k)] = v }
        }
    }

    private fun Preferences.toStringMap(): Map<String, String> =
        asMap().entries.associate { (k, v) -> k.name to v.toString() }
}
