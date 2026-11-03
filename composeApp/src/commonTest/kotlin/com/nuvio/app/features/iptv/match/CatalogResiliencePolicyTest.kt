package com.nuvio.app.features.iptv.match

import com.nuvio.app.features.iptv.match.CatalogResiliencePolicy.CategoryStep
import com.nuvio.app.features.iptv.match.CatalogResiliencePolicy.Remembered
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogResiliencePolicyTest {
    private val ttl = 7 * 24 * 60 * 60 * 1000L

    @Test
    fun a_title_lookup_does_not_wait_for_a_missing_catalog() {
        assertFalse(CatalogResiliencePolicy.shouldAwaitBuild(existingBuiltAt = null, force = false, awaitRefresh = false, waitIfMissing = false))
    }

    @Test
    fun refreshes_and_explicit_waiters_still_wait() {
        assertTrue(CatalogResiliencePolicy.shouldAwaitBuild(null, force = true, awaitRefresh = false, waitIfMissing = false))
        assertTrue(CatalogResiliencePolicy.shouldAwaitBuild(5L, force = false, awaitRefresh = true, waitIfMissing = false))
        assertTrue(CatalogResiliencePolicy.shouldAwaitBuild(null, force = false, awaitRefresh = false, waitIfMissing = true))
        assertFalse(CatalogResiliencePolicy.shouldAwaitBuild(5L, force = false, awaitRefresh = false, waitIfMissing = true))
    }

    @Test
    fun a_remembered_sid_plays_when_the_catalog_never_landed() {
        assertEquals(Remembered.USE_SID, CatalogResiliencePolicy.remembered(1234, rowPresent = false, catalogComplete = false, updatedAtMs = 1, nowMs = 2, lastAddedAtMs = 0, negativeTtlMs = ttl))
    }

    @Test
    fun a_remembered_sid_missing_from_a_complete_catalog_is_rematched() {
        assertEquals(Remembered.UNKNOWN, CatalogResiliencePolicy.remembered(1234, rowPresent = false, catalogComplete = true, updatedAtMs = 1, nowMs = 2, lastAddedAtMs = 0, negativeTtlMs = ttl))
    }

    @Test
    fun a_remembered_row_in_the_index_is_used() {
        assertEquals(Remembered.USE_ROW, CatalogResiliencePolicy.remembered(1234, rowPresent = true, catalogComplete = true, updatedAtMs = 1, nowMs = 2, lastAddedAtMs = 0, negativeTtlMs = ttl))
    }

    @Test
    fun a_fresh_negative_verdict_holds_until_the_catalog_gains_titles() {
        assertEquals(Remembered.NOT_ON_PROVIDER, CatalogResiliencePolicy.remembered(null, false, true, updatedAtMs = 100, nowMs = 200, lastAddedAtMs = 50, negativeTtlMs = ttl))
        assertEquals(Remembered.UNKNOWN, CatalogResiliencePolicy.remembered(null, false, true, updatedAtMs = 100, nowMs = 200, lastAddedAtMs = 150, negativeTtlMs = ttl))
        assertEquals(Remembered.UNKNOWN, CatalogResiliencePolicy.remembered(null, false, true, updatedAtMs = 100, nowMs = 100 + ttl, lastAddedAtMs = 0, negativeTtlMs = ttl))
    }

    @Test
    fun the_full_list_is_avoided_for_six_hours_after_it_failed() {
        assertTrue(CatalogResiliencePolicy.useFullList(avoidFullUntilMs = null, nowMs = 0))
        assertFalse(CatalogResiliencePolicy.useFullList(avoidFullUntilMs = 1_000, nowMs = 999))
        assertTrue(CatalogResiliencePolicy.useFullList(avoidFullUntilMs = 1_000, nowMs = 1_000))
    }

    @Test
    fun an_overloaded_provider_stops_the_category_walk() {
        assertEquals(CategoryStep.STOP_AND_BACK_OFF, CatalogResiliencePolicy.afterCategoryFailure(429, hostGuardRefused = false))
        assertEquals(CategoryStep.STOP_AND_BACK_OFF, CatalogResiliencePolicy.afterCategoryFailure(520, hostGuardRefused = false))
        assertEquals(CategoryStep.STOP_AND_BACK_OFF, CatalogResiliencePolicy.afterCategoryFailure(null, hostGuardRefused = true))
    }

    @Test
    fun a_cut_off_category_is_skipped_not_fatal() {
        assertEquals(CategoryStep.SKIP_CATEGORY, CatalogResiliencePolicy.afterCategoryFailure(null, hostGuardRefused = false))
        assertEquals(CategoryStep.SKIP_CATEGORY, CatalogResiliencePolicy.afterCategoryFailure(404, hostGuardRefused = false))
    }

    @Test
    fun only_a_whole_single_pass_walk_finalises_the_index() {
        assertTrue(CatalogResiliencePolicy.walkCompletes(categoriesTotal = 301, categoriesDone = 301, startedAtCategory = 0))
        assertFalse(CatalogResiliencePolicy.walkCompletes(301, 300, 0))
        assertFalse(CatalogResiliencePolicy.walkCompletes(301, 301, 12))
        assertFalse(CatalogResiliencePolicy.walkCompletes(0, 0, 0))
    }

    @Test
    fun low_memory_devices_keep_one_build_at_a_time() {
        assertEquals(1, CatalogResiliencePolicy.globalBuildPermits(lowMemoryDevice = true))
        assertEquals(2, CatalogResiliencePolicy.globalBuildPermits(lowMemoryDevice = false))
    }
}
