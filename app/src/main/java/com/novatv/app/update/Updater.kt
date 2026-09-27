package com.novatv.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.novatv.app.BuildConfig
import com.novatv.app.app
import com.novatv.app.ui.AutoFocus
import com.novatv.app.ui.RowTitle
import com.novatv.app.ui.TvDialog
import com.novatv.app.ui.TvRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * App updates: every change pushed to GitHub builds a new release ("build-N", versionCode N).
 * The app checks for a newer release, asks the viewer, downloads it and opens the Android installer.
 * (Android always shows its own "Install" confirmation for apps from outside the Play Store.)
 */
object Updater {
    private const val REPO = "stillsmokinbud-dotcom/KingVegasTV"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    const val APK_URL = "https://github.com/$REPO/releases/latest/download/KingVegasTV.apk"

    data class Release(val version: Int, val notes: String?)

    /** Update waiting to be offered in the pop-up (set by the background check or Settings › Check for updates). */
    val pending = kotlinx.coroutines.flow.MutableStateFlow<Release?>(null)
    fun offer(r: Release) { pending.value = r }

    /** The newest release if it is newer than this app, else null. */
    suspend fun check(http: OkHttpClient): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val o = Json.parseToJsonElement(resp.body!!.string()) as JsonObject
                val tag = o["tag_name"]?.jsonPrimitive?.content.orEmpty()
                val n = tag.removePrefix("build-").toIntOrNull() ?: return@runCatching null
                if (n > BuildConfig.VERSION_CODE) Release(n, o["name"]?.jsonPrimitive?.content) else null
            }
        }.getOrNull()
    }

    /** Downloads the newest APK into the app's cache. */
    suspend fun download(context: Context, http: OkHttpClient, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "KingVegasTV.apk")
        http.newCall(Request.Builder().url(APK_URL).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("Download failed (${resp.code})")
            val body = resp.body ?: throw java.io.IOException("Empty download")
            val total = body.contentLength().toFloat()
            body.byteStream().use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val r = input.read(buf); if (r < 0) break
                        o.write(buf, 0, r); done += r
                        if (total > 0) onProgress(done / total)
                    }
                }
            }
        }
        out
    }

    /** True when Android lets this app open the installer (Fire TV / Android 8+ ask once). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Checks for an update the moment the app is opened (and every time it comes back to the screen),
 * and pops up "Update available" right away, even over the channel that starts playing. "Later"
 * only hides it until the next time the app is opened. While the app stays open it also checks
 * every 6 hours (that pop-up waits until the viewer isn't watching full screen).
 */
@Composable
fun UpdatePrompt(show: Boolean = true) {
    val context = LocalContext.current
    val http = context.app.playlists.http
    val scope = rememberCoroutineScope()
    val release by Updater.pending.collectAsState()
    var dismissedVersion by remember { mutableStateOf(0) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var progressValue by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var needsPermission by remember { mutableStateOf(false) }
    var apk by remember { mutableStateOf<File?>(null) }
    /** True when the pop-up comes from opening the app: then it shows on any screen. */
    var fromOpen by remember { mutableStateOf(false) }

    // Every time the app is opened / brought back: check now and prompt straight away.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        dismissedVersion = 0
        scope.launch {
            Updater.check(http)?.let { fromOpen = true; Updater.offer(it) }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(6 * 60 * 60 * 1000L)
            Updater.check(http)?.let { if (it.version > dismissedVersion) { fromOpen = false; Updater.offer(it) } }
        }
    }

    fun installNow(file: File) {
        if (Updater.canInstall(context)) { Updater.install(context, file); Updater.pending.value = null; progress = null }
        else { needsPermission = true; Updater.openInstallPermission(context) }
    }

    val r = release ?: return
    if (!show && !fromOpen && progress == null && !needsPermission) return
    val title = when {
        needsPermission -> "Allow updates"
        progress != null -> "Downloading update…"
        else -> "Update available"
    }
    TvDialog(title, onDismiss = { if (progress == null) { dismissedVersion = r.version; Updater.pending.value = null } }) {
        Column(Modifier.padding(bottom = 4.dp)) {
            val first = remember { FocusRequester() }
            when {
                needsPermission -> {
                    Text("Turn on \"Allow from this source\" for KINGVEGAS TV, then press Back and choose Install.",
                        fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(bottom = 12.dp))
                    TvRow(modifier = Modifier.focusRequester(first), onClick = { apk?.let { needsPermission = false; installNow(it) } }) { RowTitle("Install") }
                    TvRow(onClick = { Updater.openInstallPermission(context) }) { RowTitle("Open the setting again") }
                }
                progress != null -> {
                    Text("KINGVEGAS TV 1.0.${r.version}", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                    LinearProgressIndicator(progress = { progressValue }, modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp))
                    Text("${(progressValue * 100).toInt()}%", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
                else -> {
                    Text("A new version of KINGVEGAS TV (1.0.${r.version}) is ready. You're on 1.0.${BuildConfig.VERSION_CODE}.",
                        fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(bottom = 6.dp))
                    error?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 6.dp)) }
                    TvRow(modifier = Modifier.focusRequester(first), onClick = {
                        error = null; progress = 0f; progressValue = 0f
                        scope.launch {
                            runCatching { Updater.download(context, http) { p -> progressValue = p } }
                                .onSuccess { apk = it; installNow(it) }
                                .onFailure { error = "Couldn't download: ${it.message}"; progress = null }
                        }
                    }) { RowTitle("Update now") }
                    TvRow(onClick = { dismissedVersion = r.version; Updater.pending.value = null }) { RowTitle("Later") }
                }
            }
            if (progress == null) AutoFocus(first, title)
        }
    }
}
