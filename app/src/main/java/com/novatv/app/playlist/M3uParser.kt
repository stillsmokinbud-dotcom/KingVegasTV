package com.novatv.app.playlist

import java.io.BufferedReader

/**
 * Parses extended M3U playlists:
 *
 *   #EXTM3U x-tvg-url="http://guide.xml"
 *   #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One" tvg-logo="http://..." group-title="UK" tvg-chno="101"
 *       catchup="default" catchup-days="7" catchup-source="...",BBC One HD
 *   #EXTVLCOPT:http-user-agent=Mozilla/5.0
 *   http://provider/stream/123.ts
 */
object M3uParser {

    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")
    private val adultRegex = Regex("""(?i)\b(xxx|adult|18\+|for adults)\b""")

    fun parse(reader: BufferedReader, playlistId: String): PlaylistContent {
        val channels = ArrayList<Channel>(4096)
        var epgUrl: String? = null

        var pendingInfo: String? = null
        var pendingGroup: String? = null
        var pendingUserAgent: String? = null

        reader.useLines { lines ->
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                when {
                    line.startsWith("#EXTM3U") -> {
                        val attrs = attributes(line)
                        epgUrl = attrs["x-tvg-url"] ?: attrs["url-tvg"]
                        epgUrl = epgUrl?.split(',')?.firstOrNull()?.trim()
                    }
                    line.startsWith("#EXTINF") -> {
                        pendingInfo = line
                        pendingGroup = null
                        pendingUserAgent = null
                    }
                    line.startsWith("#EXTGRP:") -> pendingGroup = line.removePrefix("#EXTGRP:").trim()
                    line.startsWith("#EXTVLCOPT:http-user-agent=") ->
                        pendingUserAgent = line.substringAfter("=").trim()
                    line.startsWith("#") -> Unit // other tags ignored
                    else -> {
                        val info = pendingInfo ?: continue
                        channels += buildChannel(info, line, pendingGroup, pendingUserAgent, playlistId)
                        pendingInfo = null
                    }
                }
            }
        }
        return PlaylistContent(channels, epgUrl)
    }

    private fun buildChannel(
        info: String, url: String, extGroup: String?, userAgent: String?, playlistId: String,
    ): Channel {
        val attrs = attributes(info)
        // Display name is everything after the last comma that's outside quotes.
        val name = displayName(info).ifBlank { attrs["tvg-name"] ?: "Channel" }
        val group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: extGroup ?: "Uncategorized"
        val catchupDays = (attrs["catchup-days"] ?: attrs["timeshift"] ?: attrs["tvg-rec"])?.toIntOrNull() ?: 0
        return Channel(
            id = "$playlistId:${url.hashCode()}:${name.hashCode()}",
            playlistId = playlistId,
            name = name,
            number = (attrs["tvg-chno"] ?: attrs["channel-number"])?.toIntOrNull(),
            group = group,
            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
            url = url,
            epgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
            catchupDays = catchupDays,
            catchupSource = attrs["catchup-source"],
            userAgent = userAgent ?: attrs["user-agent"],
            isAdult = adultRegex.containsMatchIn(group),
        )
    }

    private fun attributes(line: String): Map<String, String> =
        attrRegex.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    private fun displayName(info: String): String {
        var inQuotes = false
        var lastComma = -1
        info.forEachIndexed { i, c ->
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) lastComma = i
        }
        return if (lastComma >= 0) info.substring(lastComma + 1).trim() else ""
    }
}
