package com.nuvio.app.features.sportsdetail.internal

/**
 * The header's round text. A numeric round counts in weeks for American football ("Week 11") and in rounds for every other sport
 * ("Round 11"); a text round ("Quarterfinal", "Final") is shown as the feed wrote it. Pure.
 */
internal fun roundLabel(sport: String?, round: String): String {
    val r = round.trim()
    if (r.isEmpty() || !r.all { it.isDigit() }) return r
    return if (sport?.trim().equals("American Football", ignoreCase = true)) "Week $r" else "Round $r"
}
