package com.nuvio.app.features.recordings.internal

object SeriesEpisodeKey {
    data class Key(val season: Int?, val episode: Int) {
        val normalized: String get() = TODO()
    }
    fun fromText(raw: String?): Key? = TODO()
    fun fromXmltvNs(raw: String?): Key? = TODO()
    fun fromProgramme(title: String?, description: String?, xmltvEpisode: String? = null): Key? = TODO()
}
