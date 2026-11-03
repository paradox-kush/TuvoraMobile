package com.nuvio.app.core.contracts

/**
 * A playlist could not answer a title lookup because its catalog has not landed on this device yet
 * (still downloading, or the panel cut it off). Shown as "still loading", never as "no streams":
 * a failure is never "empty" (bounded-load rule).
 */
class IptvCatalogPendingException(val playlistName: String) :
    IllegalStateException("$playlistName is still loading its catalog")
