package com.nuvio.app.features.player

import kotlin.test.*
class UpstreamSdhRegressionTest {
    @Test fun retainsSentencesEndingInColon() {
        assertEquals("He said:", SubtitleSdhFilter.filter("He said:"))
        assertEquals("I need to tell you:", SubtitleSdhFilter.filter("I need to tell you:"))
    }
    @Test fun removesSpeakerLabelsButKeepsDialogue() {
        assertEquals("Hello", SubtitleSdhFilter.filter(">> JOHN: Hello"))
        assertEquals("- Hello", SubtitleSdhFilter.filter("- John Smith: Hello"))
    }
    @Test fun doesNotStripTimeCodesOrOrdinaryColonSentences() {
        assertEquals("12:30 tomorrow", SubtitleSdhFilter.filter("12:30 tomorrow"))
        assertEquals("He said: go now", SubtitleSdhFilter.filter("He said: go now"))
    }
}
