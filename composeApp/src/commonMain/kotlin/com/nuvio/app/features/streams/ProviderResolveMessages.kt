package com.nuvio.app.features.streams

import com.nuvio.app.core.contracts.DeferredResolveRefusal
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.provider_source_access_denied
import nuvio.composeapp.generated.resources.provider_source_nothing_to_play
import nuvio.composeapp.generated.resources.provider_source_resolve_failed
import nuvio.composeapp.generated.resources.provider_source_session_limit
import org.jetbrains.compose.resources.StringResource

/** The toast a play-time source refusal shows (B02): the reason in plain words, the generic text for the rest. */
internal fun DeferredResolveRefusal.messageRes(): StringResource = when (this) {
    DeferredResolveRefusal.SESSION_LIMIT -> Res.string.provider_source_session_limit
    DeferredResolveRefusal.NOTHING_TO_PLAY -> Res.string.provider_source_nothing_to_play
    DeferredResolveRefusal.ACCESS_DENIED -> Res.string.provider_source_access_denied
    DeferredResolveRefusal.OTHER -> Res.string.provider_source_resolve_failed
}
