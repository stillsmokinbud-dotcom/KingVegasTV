package com.novatv.app.playlist

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

/**
 * Minimal Xtream Codes API client (player_api.php).
 * Live TV only for now; VOD (get_vod_streams) and series (get_series) follow the same pattern.
 */
class XtreamClient(private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun loadLive(p: Playlist, userAgent: String): PlaylistContent {
        val base = p.url.trimEnd('/')
        val user = enc(p.username)
        val pass = enc(p.password)
        val api = "$base/player_api.php?username=$user&password=$pass"

        val auth = get("$api", userAgent).jsonObject
        val info = auth["user_info"] as? JsonObject
        val status = info?.get("auth")?.str()
        val expDate = (info?.get("exp_date")?.str()?.toLongOrNull() ?: 0L) * 1000
        val maxConn = info?.get("max_connections")?.str()?.toIntOrNull() ?: 0
        if (status != null && status != "1") throw IOException("Xtream login failed. Check username and password.")

        val catList = get("$api&action=get_live_categories", userAgent).arrayOrEmpty()
        val categories = catList
            .associate { it.jsonObject["category_id"].str() to (it.jsonObject["category_name"].str() ?: "Uncategorized") }
        // The provider's own category order (what TiviMate shows): index in get_live_categories.
        val catOrder = catList.mapIndexed { i, el -> el.jsonObject["category_id"].str() to i }.toMap()

        val ext = if (p.xtreamOutput == "m3u8") "m3u8" else "ts"
        val adult = Regex("""(?i)\b(xxx|adult|18\+)\b""")

        if (!p.includeLive) return PlaylistContent(emptyList(), "$base/xmltv.php?username=$user&password=$pass", expDate, maxConn)
        val channels = get("$api&action=get_live_streams", userAgent).arrayOrEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val streamId = o["stream_id"].str() ?: return@mapNotNull null
            val group = categories[o["category_id"].str()] ?: "Uncategorized"
            val archive = o["tv_archive"].str() == "1"
            Channel(
                id = "${p.id}:$streamId",
                playlistId = p.id,
                name = o["name"].str() ?: "Channel $streamId",
                number = o["num"].str()?.toIntOrNull(),
                group = group,
                logo = o["stream_icon"].str()?.takeIf { it.isNotBlank() },
                url = "$base/live/${p.username}/${p.password}/$streamId.$ext",
                epgId = o["epg_channel_id"].str()?.takeIf { it.isNotBlank() },
                catchupDays = if (archive) o["tv_archive_duration"].str()?.toIntOrNull() ?: 0 else 0,
                catchupSource = if (archive) "xc" else null,
                isAdult = o["is_adult"].str() == "1" || adult.containsMatchIn(group),
                groupOrder = catOrder[o["category_id"].str()] ?: Int.MAX_VALUE,
            )
        }
        return PlaylistContent(channels, epgUrl = "$base/xmltv.php?username=$user&password=$pass", expDate = expDate, maxConnections = maxConn)
    }

    private fun get(url: String, userAgent: String): JsonElement {
        val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Server returned ${resp.code}")
            val body = resp.body?.string().orEmpty()
            return json.parseToJsonElement(body.ifBlank { "[]" })
        }
    }

    private fun JsonElement.arrayOrEmpty(): JsonArray = (this as? JsonArray) ?: JsonArray(emptyList())
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.content?.takeIf { it != "null" }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
