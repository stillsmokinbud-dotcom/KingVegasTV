package com.novatv.app.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.media3.common.Format
import com.novatv.app.settings.AppSettings
import kotlin.math.abs

/**
 * Settings › Playback › Auto frame rate (AFR): switches the TV's refresh rate (and optionally the
 * resolution) to match the video, like TiviMate. Restored when the player closes.
 */
object Afr {
    private val main = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    fun apply(activity: Activity?, s: AppSettings, format: Format?, vod: Boolean) {
        activity ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (!s.premium || !s.bool(if (vod) "playback.afr_vod" else "playback.afr_tv")) return
        val fps = format?.frameRate?.takeIf { it > 0 } ?: return
        val height = format.height
        val display = activity.windowManager.defaultDisplay ?: return
        val current = display.mode
        if (s.bool("playback.afr_50_60") && !(near(fps, 50f) || near(fps, 60f) || near(fps, 59.94f) || near(fps, 25f) || near(fps, 30f)))
            return
        val modes = display.supportedModes.toList()
        // Resolution: keep the current one unless "Switch screen resolution" is on.
        val sized = if (s.bool("playback.afr_resolution") && height > 0) {
            val wanted = modes.filter { it.physicalHeight == height || (height in 1000..1100 && it.physicalHeight == 1080) ||
                (height > 2000 && it.physicalHeight >= 2160) || (height in 700..800 && it.physicalHeight == 720) }
            wanted.ifEmpty { modes.filter { it.physicalHeight == current.physicalHeight } }
        } else modes.filter { it.physicalHeight == current.physicalHeight && it.physicalWidth == current.physicalWidth }
        val target = if (s.bool("playback.afr_rate")) {
            sized.minByOrNull { rateScore(it.refreshRate, fps) }?.takeIf { rateScore(it.refreshRate, fps) < 0.05f }
        } else sized.minByOrNull { abs(it.refreshRate - current.refreshRate) }
        target ?: return
        if (target.modeId == current.modeId) return
        pending?.let { main.removeCallbacks(it) }
        val r = Runnable {
            runCatching {
                val w = activity.window
                w.attributes = w.attributes.apply { preferredDisplayModeId = target.modeId }
            }
        }
        pending = r
        main.postDelayed(r, s.int("playback.afr_delay") * 1000L)
    }

    /** Back to the TV's own mode when leaving the player. */
    fun reset(activity: Activity?) {
        pending?.let { main.removeCallbacks(it) }; pending = null
        activity ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            val w = activity.window
            if (w.attributes.preferredDisplayModeId != 0) w.attributes = w.attributes.apply { preferredDisplayModeId = 0 }
        }
    }

    private fun near(a: Float, b: Float) = abs(a - b) < 0.6f

    /** 0 = the refresh rate is an exact multiple of the frame rate. */
    private fun rateScore(rate: Float, fps: Float): Float {
        val k = Math.round(rate / fps).coerceAtLeast(1)
        return abs(rate - fps * k) / rate
    }
}

/** Settings › Playback › Use external player: hands the stream to another installed player app. */
object ExternalPlayer {
    fun wanted(s: AppSettings, vod: Boolean): Boolean = when (s.str("playback.player")) {
        "all" -> true
        "tv" -> !vod
        "vod" -> vod
        else -> false
    }

    fun open(context: Context, url: String, title: String): Boolean = runCatching {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*")
            .putExtra("title", title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(i, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
