package com.nuvio.app.features.iptv.epg

// F14 picker dialog (Compose; phone/tablet/desktop). Its state holder is GuideChannelPickerController.kt,
// which has no UI import so Apple TV's tvosCore compiles it for a SwiftUI picker.

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
internal fun GuideChannelPickerHost() {
    val req by GuideChannelPickerController.request.collectAsStateWithLifecycle()
    val current = req ?: return
    GuideChannelPickerDialog(current)
}

@Composable
private fun GuideChannelPickerDialog(req: GuideChannelPickerController.Request) {
    var query by remember(req) { mutableStateOf("") }
    var state by remember(req) { mutableStateOf<GuideChannelPickerController.State?>(null) }
    var failed by remember(req) { mutableStateOf(false) }
    LaunchedEffect(req, query) {
        if (query.isNotEmpty()) delay(250)   // debounce typing; the read is local but not free
        // Bounded: a wedged read ends as "Couldn't read the guide" rather than "Loading…" forever.
        val outcome = com.nuvio.app.features.iptv.BoundedLoad.run(
            com.nuvio.app.features.iptv.LoadSurface.SETTINGS, report = mapOf("row" to "guide_picker"),
        ) { GuideChannelPickerController.load(req, query) }
        failed = outcome.valueOrNull() == null
        outcome.valueOrNull()?.let { state = it }
    }
    AlertDialog(
        onDismissRequest = { GuideChannelPickerController.close() },
        title = { Text("Guide for ${req.channelName}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                val s = state
                when {
                    s == null && failed -> Text("Couldn’t read the guide on this device. Close and try again.", fontSize = 13.sp)
                    s == null -> Text("Loading guide channels…", fontSize = 13.sp)
                    !s.hasGuide -> Text(
                        "No guide is loaded for this playlist on this device yet. Add an EPG URL in the " +
                            "playlist's settings, or wait for the guide to finish downloading.",
                        fontSize = 13.sp,
                    )
                    else -> {
                        Text(
                            text = when {
                                s.currentIsManual -> "You picked: ${s.currentGuideId}"
                                s.currentGuideId != null -> "Matched automatically: ${s.currentGuideId}"
                                else -> "No guide channel matched automatically."
                            },
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            label = { Text("Search the guide") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            if (s.currentIsManual && s.entityId != null) {
                                item(key = "auto") {
                                    PickerRow(
                                        title = "Automatic",
                                        subtitle = "Let Tuvora match this channel again",
                                        selected = false,
                                    ) { GuideChannelPickerController.pick(req, s.entityId, null) }
                                    HorizontalDivider()
                                }
                            }
                            items(s.options, key = { it.guideKey }) { row ->
                                PickerRow(
                                    title = row.name,
                                    subtitle = row.guideId + if (row.sourceIndex > 0) "  ·  EPG source ${row.sourceIndex + 1}" else "",
                                    selected = row.guideId == s.currentGuideId,
                                ) { s.entityId?.let { GuideChannelPickerController.pick(req, it, row) } }
                            }
                        }
                        if (s.options.isEmpty()) Text("No guide channel matches “$query”.", fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { GuideChannelPickerController.close() }) { Text("Close") } },
    )
}

@Composable
private fun PickerRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = if (selected) "✓  $title" else title,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(subtitle, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
