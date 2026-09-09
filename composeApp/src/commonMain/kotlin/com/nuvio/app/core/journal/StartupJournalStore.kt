package com.nuvio.app.core.journal

/**
 * Durable, verifiable persistence for the startup journal — a small blob (a few KB). Each platform
 * writes atomically and then READS BACK to confirm the content landed: a preferences/file setter
 * returning is not proof of durable persistence, so [writeVerified] returns true only when a
 * subsequent read matches exactly. On any failure it returns false, and the caller withholds
 * automatic risky work for the run.
 */
internal expect object StartupJournalStore {
    fun read(): String?
    fun writeVerified(content: String): Boolean
}

/** Wall-clock epoch millis for the journal — its own neutral clock so a core module never depends on
 *  a feature's clock. */
internal expect fun journalNowMs(): Long
