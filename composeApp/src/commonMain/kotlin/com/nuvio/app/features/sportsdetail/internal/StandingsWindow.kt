package com.nuvio.app.features.sportsdetail.internal

import com.nuvio.app.features.sportsdetail.api.StandingsGroup
import com.nuvio.app.features.sportsdetail.api.StandingsTableBlock

/**
 * For panels that cannot scroll (TV, Apple TV): at most [max] rows IN TOTAL (so also at most [max] per group), centred on
 * the game's teams, never pushing a game team out of view. A table that already fits is returned unchanged. When space is
 * short, groups without a game team are dropped and the budget is split across the groups that have one; inside a group the
 * rows nearest the game teams are kept, in the table's own order (so a window can skip rows between two far-apart teams).
 */
internal object StandingsWindow {
    fun window(b: StandingsTableBlock, max: Int): StandingsTableBlock {
        if (max <= 0) return b.copy(groups = emptyList())
        if (b.groups.sumOf { it.rows.size } <= max) return b
        val withTeam = b.groups.filter { g -> g.rows.any { it.isGameTeam } }
        if (withTeam.isEmpty()) {
            val first = b.groups.firstOrNull() ?: return b
            return b.copy(groups = listOf(first.copy(rows = first.rows.take(max))))
        }
        val n = withTeam.size
        val groups = withTeam.mapIndexed { i, g ->
            val share = max / n + if (i < max % n) 1 else 0
            val needed = g.rows.count { it.isGameTeam }
            g.window(maxOf(share, needed))
        }
        return b.copy(groups = groups)
    }

    private fun StandingsGroup.window(budget: Int): StandingsGroup {
        if (rows.size <= budget) return this
        val keep = mutableSetOf<Int>()
        rows.forEachIndexed { i, r -> if (r.isGameTeam) keep += i }
        var radius = 1
        while (keep.size < budget && radius < rows.size) {
            for (i in rows.indices.filter { rows[it].isGameTeam }) {
                for (j in listOf(i - radius, i + radius)) {
                    if (keep.size < budget && j in rows.indices) keep += j
                }
            }
            radius++
        }
        return copy(rows = keep.sorted().map { rows[it] })
    }
}
