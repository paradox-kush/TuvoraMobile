package com.nuvio.app.features.debrid

import com.nuvio.app.features.streams.*
import kotlin.test.*
class UpstreamLanguageRegressionTest {
    private fun languages(text: String, title: String? = null, parsed: List<String> = emptyList()): List<DebridStreamLanguage> =
        DebridStreamMetadata.facts(StreamItem(addonName = "Test", addonId = "test", name = text,
            clientResolve = StreamClientResolve(title = title, stream = StreamClientResolveStream(raw = StreamClientResolveRaw(
                parsed = StreamClientResolveParsed(parsedTitle = title, languages = parsed))))), DebridStreamPreferences()).languages
    @Test fun recognizesReleaseLanguageWords() {
        assertEquals(listOf(DebridStreamLanguage.EN, DebridStreamLanguage.IT), languages("Film.ENG.ITA.1080p"))
        assertEquals(listOf(DebridStreamLanguage.FR), languages("Film.TrueFrench.1080p"))
    }
    @Test fun ignoresLanguageWordsInTheMediaTitle() {
        assertEquals(listOf(DebridStreamLanguage.EN), languages("The.French.Dispatch.2021.English.1080p", "The French Dispatch"))
        assertTrue(languages("The.Italian.Job.2003.1080p", "The Italian Job").isEmpty())
    }
    @Test fun preservesRealLanguageAfterRemovingTheTitle() {
        assertEquals(listOf(DebridStreamLanguage.FR), languages("The.French.Dispatch.2021.French.1080p", "The French Dispatch"))
        assertEquals(listOf(DebridStreamLanguage.IT), languages("Film.English.1080p", parsed = listOf("ita")))
    }
}
