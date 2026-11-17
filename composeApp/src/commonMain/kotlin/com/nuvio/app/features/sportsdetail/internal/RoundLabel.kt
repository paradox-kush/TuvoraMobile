package com.nuvio.app.features.sportsdetail.internal

/** The header's round text: American football counts in weeks ("Week 11"), every other sport in rounds ("Round 11"). Pure. */
internal fun roundLabel(sport: String?, round: String): String =
    if (sport?.trim().equals("American Football", ignoreCase = true)) "Week $round" else "Round $round"
