package com.nuvio.app.features.streams

/**
 * UX154: the stream list pre-selects the chip of the source the episode last played from, but only
 * ONCE per stream-list load. Re-entering the list after the player (the route recomposes, the
 * repository keeps its state) must keep whatever chip the user left selected, including "All".
 */
internal object PreferredFilterPolicy {
    /**
     * @param filterTouched true once any filter was chosen for this load (the user's tap or an earlier
     * preference application); it lives in [StreamsUiState], which is rebuilt on every fresh load.
     * @param preferredAddonId the source the episode last played from, if any.
     */
    fun shouldApply(
        filterTouched: Boolean,
        preferredAddonId: String?,
        groups: List<AddonStreamGroup>,
    ): Boolean =
        !filterTouched &&
            preferredAddonId != null &&
            groups.any { it.addonId == preferredAddonId }
}
