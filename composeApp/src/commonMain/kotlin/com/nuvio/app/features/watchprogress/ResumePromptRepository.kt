package com.nuvio.app.features.watchprogress

object ResumePromptRepository {

    fun markPlayerEntered(videoId: String) {
        ResumePromptStorage.saveWasInPlayer(true)
        ResumePromptStorage.saveLastPlayerVideoId(videoId)
    }

    fun markPlayerExitedNormally() {
        ResumePromptStorage.saveWasInPlayer(false)
        ResumePromptStorage.saveLastPlayerVideoId(null)
    }

    fun hasPendingPrompt(): Boolean = ResumePromptStorage.loadWasInPlayer()

    /**
     * True once, after a launch that found the app was last left inside the player; the flag is cleared here.
     * WHICH title to offer is not decided here: see `ResumePromptPicker`, which follows the Continue Watching row.
     */
    fun consumeWasInPlayer(): Boolean {
        val wasInPlayer = ResumePromptStorage.loadWasInPlayer()
        if (!wasInPlayer) return false
        ResumePromptStorage.saveWasInPlayer(false)
        ResumePromptStorage.saveLastPlayerVideoId(null)
        return true
    }
}
