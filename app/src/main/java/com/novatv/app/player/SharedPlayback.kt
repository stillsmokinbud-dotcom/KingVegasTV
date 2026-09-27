package com.novatv.app.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.novatv.app.playlist.DEFAULT_USER_AGENT
import com.novatv.app.settings.AppSettings
import okhttp3.OkHttpClient

/**
 * One live-TV player shared by the full-screen player and the TV guide preview, like TiviMate:
 * pressing Back from full screen shows the guide with the same channel still playing in the
 * preview — no black screen, no reconnect — and OK/Back on it goes back to full screen seamlessly.
 *
 * Screens [attach] while they show the video and [detach] when they go away; when nobody shows it
 * (Movies, Settings full screen, Multiview…) playback is paused after a short grace period.
 */
class SharedPlayback(private val context: Context, private val http: OkHttpClient) {
    private var built: PlayerFactory.Built? = null
    /** Channel currently loaded in the shared player (null = nothing). */
    var channelId: String? = null
        private set
    private var users = 0
    private val main = Handler(Looper.getMainLooper())
    private val pauseIfUnused = Runnable {
        if (users == 0) built?.player?.let { it.playWhenReady = false; it.stop(); channelId = null }
    }

    fun obtain(settings: AppSettings): PlayerFactory.Built =
        built ?: PlayerFactory(context, http).create(settings, DEFAULT_USER_AGENT).also { built = it }

    fun attach() { users++; main.removeCallbacks(pauseIfUnused) }

    fun detach() {
        users = (users - 1).coerceAtLeast(0)
        main.removeCallbacks(pauseIfUnused)
        // Grace period: guide → player (or back) re-attaches within a frame or two.
        if (users == 0) main.postDelayed(pauseIfUnused, 600)
    }

    /** True when [id] is already loaded and playing (or buffering), so nothing needs to restart. */
    fun isPlaying(id: String): Boolean {
        val p = built?.player ?: return false
        return channelId == id && p.playbackState != androidx.media3.common.Player.STATE_IDLE &&
            p.playbackState != androidx.media3.common.Player.STATE_ENDED
    }

    fun markLoaded(id: String) { channelId = id }
}
