package com.nuvio.app.features.iptv

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * A string set safe to touch from any thread. Every access holds one lock, and every critical section is a
 * plain set operation that never suspends. Kotlin/Native aborts the process on a concurrent mutation of a
 * plain HashSet, so a set shared between the UI thread and background workers must not be a bare mutableSetOf.
 */
internal class SynchronizedStringSet {
    private val lock = SynchronizedObject()
    private val items = mutableSetOf<String>()

    /** True if [value] was not present (and now is). */
    fun add(value: String): Boolean = synchronized(lock) { items.add(value) }

    fun remove(value: String): Boolean = synchronized(lock) { items.remove(value) }

    fun contains(value: String): Boolean = synchronized(lock) { value in items }

    fun clear() = synchronized(lock) { items.clear() }

    val size: Int get() = synchronized(lock) { items.size }
}
