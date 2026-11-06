package com.nuvio.app.features.recordings.api

import androidx.compose.runtime.Composable
import com.nuvio.app.core.contracts.RecordingPlayback
import com.nuvio.app.core.contracts.RecordingsContent
import com.nuvio.app.features.recordings.internal.ui.RecordingsScreen

/** The Recordings page, handed to the shell through the `RecordingsContentAccess` slot (registered in FeatureWiring). */
object RecordingsContentImpl : RecordingsContent {
    @Composable
    override fun Render(onBack: () -> Unit, onPlay: (RecordingPlayback) -> Unit) {
        RecordingsScreen(onBack = onBack, onPlay = onPlay)
    }
}
