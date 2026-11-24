package com.nuvio.app.features.sportsdetail.api

/**
 * The game page's sports-alerts chip ("Alerts: Default / All / Off", or "Sign in for alerts" disabled), as a neutral
 * value: the caller (the Sports hub) decides it; the game screen only draws it and reports the tap. Null = no chip.
 */
data class GameAlertsChip(val label: String, val selected: Boolean, val enabled: Boolean)
