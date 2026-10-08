package com.nuvio.app.features.library

import kotlinx.atomicfu.atomic

/** Opaque profile-scoped inverse of one local favourite toggle; safe to invoke more than once. */
class LibrarySavedUndo internal constructor(private val action: () -> Unit) {
    private val consumed = atomic(false)

    fun undo() {
        if (consumed.compareAndSet(false, true)) action()
    }
}
