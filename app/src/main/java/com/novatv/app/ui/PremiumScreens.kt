package com.novatv.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.novatv.app.app
import com.novatv.app.premium.Plans
import com.novatv.app.settings.AppSettings
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Lets any screen open Settings › Premium account (provided by MainActivity). */
val LocalOpenPremium = compositionLocalOf<() -> Unit> { {} }

/** Everything Premium unlocks (shown on the paywall and the Premium screen). */
val PREMIUM_FEATURES = listOf(
    "Unlimited playlists",
    "Recording: live, from the guide, custom and recurring",
    "Catch-up TV",
    "Multiview: up to 4 channels at once",
    "Favorites and custom groups",
    "Hide, sort and rename channels and groups",
    "TV guide update interval and reminders",
    "Parental control",
    "Backup and restore",
    "Themes, colors and layout options",
    "Auto frame rate, picture-in-picture, external player",
    "Use one account on up to 10 devices",
)

fun qrBitmap(text: String, size: Int = 360): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    return bmp
}

@Composable
fun QrCode(text: String, sizeDp: Int = 180) {
    val bmp = remember(text) { runCatching { qrBitmap(text) }.getOrNull() }
    if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = "QR code", modifier = Modifier.size(sizeDp.dp))
}

private fun money(cents: Int) = "$" + String.format(java.util.Locale.US, "%.2f", cents / 100.0)

/**
 * Shown whenever someone picks a Premium feature on the free version.
 * [feature] is the thing they tried to use, e.g. "Recording".
 */
@Composable
fun PaywallDialog(feature: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val openPremium = LocalOpenPremium.current
    val url by produceState("") { value = context.app.license.buyUrl() }
    val fr = remember { FocusRequester() }
    TvDialog("$feature is a Premium feature", onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Get Premium on your phone or computer, then sign in here with the same account.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                Text(url, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
            if (url.isNotBlank()) QrCode(url, 130)
        }
        Spacer(Modifier.height(12.dp))
        TvRow(modifier = Modifier.focusRequester(fr), onClick = { onDismiss(); openPremium() }) {
            RowTitle("Sign in to Premium", "Settings › Premium account")
        }
        TvRow(onClick = onDismiss) { RowTitle("Not now") }
    }
    AutoFocus(fr)
}

/** Settings › Premium account (TiviMate's "Unlock premium"). */
@Composable
fun PremiumAccountScreen(settings: AppSettings) {
    val context = LocalContext.current
    val license = context.app.license
    val scope = rememberCoroutineScope()
    val account by license.account.collectAsState()
    var step by remember { mutableStateOf<String?>(null) } // "email" | "password" | "devices"
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    val fr = remember { FocusRequester() }
    val server by produceState("") { value = license.serverUrl() }

    LaunchedEffect(Unit) { if (license.account.value != null) license.refresh() }

    val a = account
    val status = when {
        a == null && settings.premium -> "Premium (developer switch)"
        a == null -> "Free version"
        a.admin -> "Administrator · Premium always on"
        a.premium && a.expiresAt == null -> "Premium · ${a.planLabel}"
        a.premium -> "Premium · ${a.planLabel} · until ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(a.expiresAt!!))}"
        else -> "Free · no active Premium plan"
    }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp)) {
        Column(Modifier.weight(1f)) {
            ScreenHeader("Premium account", busy ?: status)
            LazyColumn {
                if (a == null) {
                    item {
                        TvRow(modifier = Modifier.focusRequester(fr), onClick = { step = "email" }) {
                            RowTitle("Sign in", "Use the email and password from your Premium account")
                        }
                    }
                } else {
                    item { TvRow(modifier = Modifier.focusRequester(fr), onClick = {}) { RowTitle("Account", a.email) } }
                    item { TvRow(onClick = {}) { RowTitle("Status", status) } }
                    item { TvRow(onClick = {}) { RowTitle("This device", license.deviceName()) } }
                    item {
                        TvRow(onClick = { step = "devices" }) {
                            RowTitle("Devices", "${a.devices.size} of ${a.deviceLimit} in use · OK to manage")
                        }
                    }
                    item {
                        TvRow(onClick = {
                            scope.launch {
                                busy = "Checking…"
                                val r = license.refresh()
                                busy = null
                                if (r.isFailure) message = "Couldn't check" to (r.exceptionOrNull()?.message ?: "No connection")
                            }
                        }) { RowTitle("Refresh status") }
                    }
                    item {
                        TvRow(onClick = { scope.launch { license.signOut() } }) {
                            RowTitle("Sign out", "Frees up this device's slot on your account")
                        }
                    }
                }
                item { Text("Server: $server", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp)) }
            }
        }
        Spacer(Modifier.width(32.dp))
        GetPremiumPanel(Modifier.width(420.dp))
    }
    AutoFocus(fr, a?.email)

    when (step) {
        "email" -> TextDialog("Email", email, hint = "you@example.com", onDismiss = { step = null }) {
            email = it; step = "password"
        }
        "password" -> TextDialog("Password", "", secret = true, onDismiss = { step = null }) { pw ->
            step = null
            scope.launch {
                busy = "Signing in…"
                val r = license.signIn(email, pw)
                busy = null
                message = if (r.isSuccess) {
                    val acc = r.getOrNull()!!
                    (if (acc.premium) "Premium unlocked" else "Signed in") to
                        (if (acc.premium) "Welcome! All Premium features are now available on this device."
                        else "This account doesn't have Premium yet. Get a plan on the website, then choose Refresh status.")
                } else "Sign-in failed" to (r.exceptionOrNull()?.message ?: "Check your connection and the account server address.")
            }
        }
        "devices" -> {
            val devs = a?.devices.orEmpty()
            ChoiceDialog(
                "Devices (${devs.size} of ${a?.deviceLimit ?: 10}) · OK to remove",
                devs.map { it.id.toString() to (it.name + if (it.thisDevice) "  (this device)" else "") },
                null, { step = null },
            ) { id ->
                step = null
                scope.launch {
                    val r = license.removeDevice(id.toInt())
                    if (r.isFailure) message = "Couldn't remove device" to (r.exceptionOrNull()?.message ?: "")
                }
            }
        }
    }
    message?.let { (t, m) -> MessageDialog(t, m) { message = null } }
}

/** "Get Premium": prices from the server, what's included, and a QR code to buy on a phone. */
@Composable
fun GetPremiumPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val license = context.app.license
    val plans by produceState<Plans?>(null) { value = license.plans().getOrNull() }
    val url by produceState("") { value = license.buyUrl() }
    Column(modifier) {
        Text("Get Premium", fontSize = 22.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        val p = plans
        if (p != null && p.plans.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                p.plans.forEach { plan ->
                    Column(
                        Modifier.weight(1f).background(MaterialTheme.colorScheme.surface).padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(plan.id.replaceFirstChar { it.uppercase() }, fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        Text(money(plan.price), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            if (p.testMode) Text("Test mode: purchases are simulated", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp))
        } else {
            Text("Monthly, yearly or lifetime", fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (url.isNotBlank()) QrCode(url, 150)
            Column {
                Text("Scan with your phone, or visit:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                Text(url, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn {
            items(PREMIUM_FEATURES) { f ->
                Text("✓  $f", fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                    modifier = Modifier.padding(vertical = 3.dp))
            }
        }
    }
}

/** Full-screen "Get Premium" (Settings › Premium account › Get Premium). */
@Composable
fun GetPremiumScreen() {
    val fr = remember { FocusRequester() }
    val openPremium = LocalOpenPremium.current
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp)) {
        TvRow(modifier = Modifier.width(360.dp).focusRequester(fr), onClick = openPremium) {
            RowTitle("Already bought Premium? Sign in")
        }
        Spacer(Modifier.height(16.dp))
        GetPremiumPanel(Modifier.width(620.dp))
    }
    AutoFocus(fr)
}
