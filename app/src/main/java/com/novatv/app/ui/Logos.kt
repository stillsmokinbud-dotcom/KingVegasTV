package com.novatv.app.ui

import com.novatv.app.epg.EpgData
import com.novatv.app.playlist.Channel
import com.novatv.app.playlist.PlaylistRepository
import com.novatv.app.settings.AppSettings
import com.novatv.app.settings.DataKeys
import java.io.File

/**
 * Settings › Appearance › Logos (and each playlist's own "Logos priority"): picks a channel's logo from
 * the playlist, the TV guide (EPG) or a folder of image files named after the channels.
 */
object Logos {
    private val exts = setOf("png", "jpg", "jpeg", "webp", "gif", "svg")
    @Volatile private var folderPath = ""
    @Volatile private var indexedAt = 0L
    @Volatile private var exact: Map<String, File> = emptyMap()
    @Volatile private var loose: Map<String, File> = emptyMap()
    @Volatile private var plRaw: String? = null
    @Volatile private var plPriority: Map<String, String> = emptyMap()

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun index(path: String) {
        val now = System.currentTimeMillis()
        if (path == folderPath && now - indexedAt < 5 * 60_000L) return
        folderPath = path; indexedAt = now
        val files = runCatching { File(path).listFiles()?.filter { it.isFile && it.extension.lowercase() in exts } }.getOrNull().orEmpty()
        exact = files.associateBy { it.nameWithoutExtension.lowercase() }
        loose = files.associateBy { norm(it.nameWithoutExtension) }
    }

    private fun fromFolder(c: Channel, s: AppSettings): File? {
        val path = s.str("logos.folder").trim()
        if (path.isEmpty()) return null
        index(path)
        exact[c.name.lowercase()]?.let { return it }
        // "Inexact matching for logos files": ignore case, spaces and punctuation.
        if (s.bool("logos.inexact")) {
            val n = norm(c.name)
            loose[n]?.let { return it }
            loose.entries.firstOrNull { (k, _) -> k.isNotEmpty() && (n.startsWith(k) || k.startsWith(n)) }?.value?.let { return it }
        }
        return null
    }

    /** The logo to show (a URL or a File), or null for the colored initials. */
    fun resolve(c: Channel, s: AppSettings, epg: EpgData, repo: PlaylistRepository): Any? {
        val raw = s.raw[DataKeys.PLAYLISTS]
        if (raw != plRaw) {
            plRaw = raw
            plPriority = repo.decodePlaylists(raw).associate { it.id to it.logosPriority }
        }
        val prio = plPriority[c.playlistId]?.takeIf { it.isNotBlank() && it != "default" } ?: s.str("channels.logo_source")
        val order = when (prio) {
            "epg" -> listOf("epg", "playlist", "folder")
            "folder" -> listOf("folder", "playlist", "epg")
            else -> listOf("playlist", "epg", "folder")
        }
        for (src in order) {
            val v: Any? = when (src) {
                "playlist" -> c.logo?.takeIf { it.isNotBlank() }
                "epg" -> epg.iconFor(c)
                else -> fromFolder(c, s)
            }
            if (v != null) return v
        }
        return null
    }
}
