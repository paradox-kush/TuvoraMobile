package com.nuvio.app.features.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.recordings_title
import org.jetbrains.compose.resources.stringResource

/** Library header shortcut to the Recordings page, next to Downloads. */
@Composable
internal fun LibraryRecordingsButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Rounded.FiberManualRecord,
            contentDescription = stringResource(Res.string.recordings_title),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
