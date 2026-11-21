package com.nuvio.app.core.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

@Composable
internal fun rememberPosterCardStyleUiState(): PosterCardStyleUiState {
    PosterCardStyleRepository.ensureLoaded()
    val uiState by PosterCardStyleRepository.uiState.collectAsStateWithLifecycle()
    return uiState
}