package com.nuvio.app.features.sportsdetail.internal

import com.nuvio.app.features.sportsdetail.api.BasketballStripBlock

/** Which side is in the bonus. ONE place, so the reading can be flipped without touching anything else. */
internal object BasketballStripPolicy {
    /**
     * A competitor whose OWN `bonusState` is anything but NONE is the team that is in the bonus (the field sits on the team that
     * benefits, i.e. the opponent of the team that ran out of fouls). Supported by the NBA capture of 2026-11-14 (MIA 2 fouls
     * "DOUBLE", DET 4 fouls "NONE"). UNVERIFIED against a live game's on-air bonus indicator (no live NBA game was available).
     * The label is always "<Team> in the bonus": the NBA has no double bonus, so ESPN's raw value is never printed.
     */
    fun inBonus(side: String, b: BasketballStripBlock): Boolean {
        val v = (if (side == "home") b.homeBonus else b.awayBonus) ?: return false
        return v.isNotBlank() && !v.equals("NONE", ignoreCase = true)
    }
}
