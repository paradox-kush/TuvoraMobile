package com.nuvio.app.features.servers

/** Whether a loaded server should list its libraries now: it has none stored, is enabled and this device can sign in. Pure. */
internal object ServerLibraryBootstrapPolicy {
    fun needsRefresh(connection: ServerConnection, hasToken: Boolean): Boolean =
        connection.libraries.isEmpty() && connection.availableOnDevice && hasToken
}
