package com.nuvio.app.features.sportsdetail.internal.ui

/**
 * Pure geometry for the football field strip. Convention (verified on NFL feeds): `yardLine` is yards from the HOME team's goal
 * line, so 0f = home goal line (drawn on the left), 1f = away goal line (right). The home side attacks toward 1f, away toward 0f.
 */
internal object FieldGeometry {
    fun x(yardLine: Int): Float = yardLine.coerceIn(0, 100) / 100f

    fun attackingToward(possession: String): Float = if (possession == "home") 1f else 0f

    /** Line to gain; null when down/distance are unknown, the ball is between plays, or the line would reach the goal line (goal-to-go). */
    fun firstDownX(yardLine: Int, distance: Int?, possession: String?): Float? {
        if (distance == null || distance <= 0) return null
        val target = when (possession) {
            "home" -> yardLine + distance
            "away" -> yardLine - distance
            else -> return null
        }
        if (target >= 100 || target <= 0) return null
        return target / 100f
    }

    /** Shaded span from the drive start to the ball as (min, max); null without a start. */
    fun driveSpan(startYardLine: Int?, yardLine: Int): Pair<Float, Float>? {
        if (startYardLine == null) return null
        val a = x(startYardLine)
        val b = x(yardLine)
        return minOf(a, b) to maxOf(a, b)
    }
}
