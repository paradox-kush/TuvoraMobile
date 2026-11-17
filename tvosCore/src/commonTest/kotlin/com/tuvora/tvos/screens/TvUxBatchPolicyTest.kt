package com.tuvora.tvos.screens

import kotlin.test.Test
import kotlin.test.assertEquals

class TvUxBatchPolicyTest {
    @Test
    fun `league page opens on the first game card so a stray select never unfollows`() {
        assertEquals(TvLeagueFocus.FirstGame, TvLeagueFocusPolicy.initial(hasGames = true), "games present")
        assertEquals(TvLeagueFocus.Follow, TvLeagueFocusPolicy.initial(hasGames = false), "no card to land on")
    }

    @Test
    fun `up from the first game row reaches follow and nothing else moves it`() {
        assertEquals(TvLeagueFocus.Follow, TvLeagueFocusPolicy.up(onFirstRow = true), "first row goes up to Follow")
        assertEquals(null, TvLeagueFocusPolicy.up(onFirstRow = false), "other rows use the focus engine")
    }

    @Test
    fun `the channel on the play action is cleaned of provider tags`() {
        assertEquals(TvWatchLabel("Play", "Sky Sports Main Event"), TvWatchActionPolicy.label(null, "UK || SKY SPORTS MAIN EVENT HD", false), "tag + caps + quality")
        assertEquals(TvWatchLabel("Switch", "Now on Sky Sports Main Event"), TvWatchActionPolicy.label("UK || SKY SPORTS MAIN EVENT", "x", false), "watching name too")
    }

    @Test
    fun `the play action reads Play with a channel and Find channels without one for every sport`() {
        assertEquals(TvWatchLabel("Play", "ESPN"), TvWatchActionPolicy.label(watching = null, bestChannel = "ESPN HD", matching = false))
        assertEquals(TvWatchLabel("Find channels", null), TvWatchActionPolicy.label(watching = null, bestChannel = null, matching = false))
        assertEquals(TvWatchLabel("Finding channels…", null), TvWatchActionPolicy.label(watching = null, bestChannel = null, matching = true))
        assertEquals(TvWatchLabel("Switch", "Now on Sky"), TvWatchActionPolicy.label(watching = "Sky", bestChannel = "ESPN HD", matching = false))
    }
}
