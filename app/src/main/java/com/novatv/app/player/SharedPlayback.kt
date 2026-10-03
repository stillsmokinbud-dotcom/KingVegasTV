package com.novatv.app.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
 *
 * It also keeps live channels alive by itself (see [Guard]): when a stream errors, ends, hangs
 * while loading or the picture freezes, it reconnects at the live point and keeps trying, like TiviMate.
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

    /** "Reconnecting…" while the guard is bringing a stream back (shown by the player). */
    @Volatile var reconnecting: Boolean = false
        private set
    /** Called on the main thread whenever [reconnecting] changes. */
    var onReconnectChanged: ((Boolean) -> Unit)? = null

    private var builtWith: String? = null
    private var guard: Guard? = null

    /**
     * The shared live player. If a Playback setting it's built with changed (buffer size, decoder,
     * passthrough, tunneling, surround, timeout…), a new one is made so the change takes effect
     * straight away instead of after restarting the app.
     */
    fun obtain(settings: AppSettings): PlayerFactory.Built {
        val sig = PlayerFactory.signature(settings)
        built?.let { old ->
            if (builtWith == sig) return old
            guard?.stop(); guard = null
            main.removeCallbacks(pauseIfUnused)
            PlayerFactory.forget(old.player)
            runCatching { old.player.release() }
            built = null; channelId = null
        }
        builtWith = sig
        return PlayerFactory(context, http).create(settings, DEFAULT_USER_AGENT).also { b ->
            built = b
            guard = Guard(b.player).also { it.start() }
        }
    }

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
        return channelId == id && p.playbackState != Player.STATE_IDLE && p.playbackState != Player.STATE_ENDED
    }

    /** True once the live picture is actually running (the start-up logo waits for this before it fades away). */
    val hasPicture: Boolean
        get() = built?.player?.let { it.playbackState == Player.STATE_READY && it.isPlaying } ?: false

    fun markLoaded(id: String) { channelId = id; reconnectAttempts = 0; guard?.cancelPending(); setReconnecting(false) }

    private var reconnectAttempts = 0

    private fun setReconnecting(on: Boolean) {
        if (reconnecting == on) return
        reconnecting = on
        onReconnectChanged?.invoke(on)
    }

    /**
     * Watches the live stream every second and reconnects it when it:
     *  - fails (network error, server hiccup, "behind live window"), with a growing pause between tries,
     *  - ends (IPTV servers often just close the connection),
     *  - hangs while loading for more than 10 seconds,
     *  - stops moving (frozen picture or no progress) for 6 seconds while it says it's playing.
     * It never gives up while the channel is on screen, like TiviMate.
     */
    private inner class Guard(private val p: androidx.media3.exoplayer.ExoPlayer) : Player.Listener {
        private var bufferingFor = 0
        private var stuckFor = 0
        private var lastFrames = -1
        private var lastPos = -1L
        private var playingFor = 0

        private var pending: Runnable? = null
        @Volatile private var stopped = false

        fun start() {
            p.addListener(this)
            main.postDelayed(tick, 1000)
        }

        fun stop() {
            stopped = true
            p.removeListener(this)
            main.removeCallbacks(tick)
            pending?.let { main.removeCallbacks(it) }
            pending = null
        }

        /** A new channel started (or the user paused/stopped): forget any reconnect still waiting. */
        fun cancelPending() { pending?.let { main.removeCallbacks(it) }; pending = null }

        private fun reset() { bufferingFor = 0; stuckFor = 0; lastFrames = -1; lastPos = -1L }

        private fun reconnect(delayMs: Long) {
            reset()
            setReconnecting(true)
            cancelPending()
            val r = Runnable {
                pending = null
                // Not if the user paused / stopped meanwhile, or the player was replaced.
                if (stopped || channelId == null || !p.playWhenReady) { setReconnecting(false); return@Runnable }
                runCatching {
                    p.seekToDefaultPosition()
                    p.prepare()
                    p.playWhenReady = true
                }
            }
            pending = r
            main.postDelayed(r, delayMs)
        }

        private fun backoff(): Long {
            reconnectAttempts++
            return when (reconnectAttempts) { 1 -> 500L; 2 -> 1_500L; 3 -> 3_000L; else -> 6_000L }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (channelId == null) return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) { reconnect(0); return }
            reconnect(backoff())
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> setReconnecting(false)
                // A live channel never really "ends": the server dropped us. Connect again.
                Player.STATE_ENDED -> if (channelId != null && p.playWhenReady) reconnect(300)
            }
        }

        private val tick: Runnable = object : Runnable {
            override fun run() {
                if (stopped) return
                try { check() } finally { main.postDelayed(this, 1000) }
            }
        }

        private fun check() {
            if (channelId == null || !p.playWhenReady || users == 0) { reset(); return }
            when (p.playbackState) {
                Player.STATE_BUFFERING -> {
                    stuckFor = 0
                    if (++bufferingFor >= 10) reconnect(0) // loading for 10 s: start over at the live point
                }
                Player.STATE_READY -> {
                    bufferingFor = 0
                    val counters = p.videoDecoderCounters?.also { it.ensureUpdated() }
                    val frames = counters?.renderedOutputBufferCount ?: -1
                    val pos = p.currentPosition
                    val videoStuck = p.videoFormat != null && frames >= 0 && frames == lastFrames
                    val allStuck = pos == lastPos
                    stuckFor = if (videoStuck || allStuck) stuckFor + 1 else 0
                    lastFrames = frames; lastPos = pos
                    if (stuckFor >= 6) reconnect(0) // picture frozen for 6 s
                    // Played fine for a minute: the next problem starts again with quick retries.
                    if (stuckFor == 0 && ++playingFor >= 60) { playingFor = 0; reconnectAttempts = 0 }
                }
                else -> reset()
            }
        }
    }
}
