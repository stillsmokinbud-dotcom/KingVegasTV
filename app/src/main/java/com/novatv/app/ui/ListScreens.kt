package com.novatv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.novatv.app.app
import kotlinx.coroutines.launch

/** Editable list of URLs (Settings → TV guide → TV guide sources). */
@Composable
fun UrlListScreen(title: String, subtitle: String, key: String) {
    val repo = LocalContext.current.app.settings
    val scope = rememberCoroutineScope()
    val items by remember(key) { repo.listFlow(key) }.collectAsState(initial = emptyList())
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    val fr = remember { FocusRequester() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp)) {
        ScreenHeader(title, subtitle)
        LazyColumn {
            item {
                TvRow(modifier = Modifier.focusRequester(fr), onClick = { adding = true }) { RowTitle("+  Add URL") }
            }
            items(items) { url ->
                TvRow(onClick = { removing = url }) { RowTitle(url, "OK to remove") }
            }
        }
    }
    AutoFocus(fr)

    if (adding) TextDialog("Add guide URL", "", hint = "http://example.com/guide.xml.gz",
        onDismiss = { adding = false }) { url ->
        if (url.isNotBlank()) scope.launch { repo.setList(key, items + url) }
        adding = false
    }
    removing?.let { url ->
        ChoiceDialog("Remove this source?", listOf("yes" to "Remove", "no" to "Cancel"), null, { removing = null }) {
            if (it == "yes") scope.launch { repo.setList(key, items - url) }
            removing = null
        }
    }
}

/** Tick groups on/off (Hide groups, Locked groups). */
@Composable
fun GroupPickerScreen(title: String, subtitle: String, key: String) {
    val context = LocalContext.current
    val repo = context.app.settings
    val channels by context.app.playlists.channels.collectAsState()
    val groups = remember(channels) { channels.map { it.group }.distinct() }
    val picked by remember(key) { repo.listFlow(key) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val fr = remember { FocusRequester() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp)) {
        ScreenHeader(title, subtitle)
        if (groups.isEmpty()) {
            Text("No channels loaded yet. Add a playlist first.", fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
        }
        LazyColumn {
            items(groups.size) { i ->
                val g = groups[i]
                TvRow(
                    modifier = if (i == 0) Modifier.focusRequester(fr) else Modifier,
                    selected = g in picked,
                    onClick = { scope.launch { repo.toggleInList(key, g) } },
                ) {
                    RowTitle(g, modifier = Modifier.weight(1f))
                    Text(if (g in picked) "✓" else "", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
    if (groups.isNotEmpty()) AutoFocus(fr)
}
