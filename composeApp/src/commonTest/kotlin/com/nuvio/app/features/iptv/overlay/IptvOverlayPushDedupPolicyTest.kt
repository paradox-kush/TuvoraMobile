package com.nuvio.app.features.iptv.overlay

import kotlin.test.Test
import kotlin.test.assertEquals

class IptvOverlayPushDedupPolicyTest {

    private data class Row(val kind: String, val okey: String, val updatedAt: Long, val tag: String = "")

    private fun dedupe(rows: List<Row>) =
        IptvOverlayPushDedupPolicy.dedupe(rows, kind = { it.kind }, okey = { it.okey }, updatedAt = { it.updatedAt })

    @Test
    fun `duplicate identity collapses to the freshest entry`() {
        val rows = listOf(
            Row("channel", "fp:v1:aaa", updatedAt = 100, tag = "old"),
            Row("channel", "fp:v1:aaa", updatedAt = 300, tag = "new"),
            Row("channel", "fp:v1:aaa", updatedAt = 200, tag = "mid"),
        )
        val result = dedupe(rows)
        assertEquals(1, result.size, "the three same-identity rows collapse to one")
        assertEquals("new", result.single().tag, "the greatest updatedAt wins")
    }

    @Test
    fun `distinct identities are all preserved`() {
        val rows = listOf(
            Row("channel", "fp:v1:aaa", 100),
            Row("channel", "fp:v1:bbb", 100),
            Row("category", "c:v1:ccc", 100),
        )
        assertEquals(3, dedupe(rows).size, "three different identities all survive")
    }

    @Test
    fun `same okey under a different kind is not merged`() {
        val rows = listOf(
            Row("channel", "shared-okey", 100),
            Row("category", "shared-okey", 100),
        )
        assertEquals(2, dedupe(rows).size, "identity is (kind, okey) — kind must not be ignored")
    }

    @Test
    fun `a tie keeps the later element`() {
        val rows = listOf(
            Row("channel", "fp:v1:aaa", updatedAt = 100, tag = "first"),
            Row("channel", "fp:v1:aaa", updatedAt = 100, tag = "second"),
        )
        assertEquals("second", dedupe(rows).single().tag, "equal updatedAt keeps the last-seen row")
    }

    @Test
    fun `empty and singleton batches pass through unchanged`() {
        assertEquals(emptyList<Row>(), dedupe(emptyList()), "empty stays empty")
        val one = listOf(Row("channel", "fp:v1:aaa", 100))
        assertEquals(one, dedupe(one), "a single row is returned as-is")
    }
}
