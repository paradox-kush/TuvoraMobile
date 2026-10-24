package com.nuvio.app.features.player

internal object SubtitleSdhFilter {
    private val squareBrackets = Regex("\\[[^]]*][ \\t]*")
    private val parentheses = Regex(
        "(?:\\((?=[A-Za-z0-9 '#.,\\\"\\\\\\-\\r\\n]*\\))(?![0-9]*\\))[^)]*\\)|" +
            "\uFF08(?=[A-Za-z0-9 '#.,\\\"\\\\\\-\\r\\n]*\uFF09)(?![0-9]*\uFF09)[^\uFF09]*\uFF09)[ \\t]*"
    )
    private val speakerLabel = Regex(
        "(?m)^([ \\t]*-[ \\t]*)?([A-Za-z0-9 ()'#.,]+|\\[[^]\\r\\n]*]):(?=\\s|$)[ \\t]*"
    )
    private val speakerChevrons = Regex("[<>]{2,}[ \t]*")
    private val labelWords = Regex("[ \\t]+")

    fun filter(text: String): String? {
        // Runs before speakerLabel so that ">> NAME:" loses the chevrons first and
        // is then recognised as a speaker label.
        var filtered = speakerChevrons.replace(text, "")
        val unlabelled = filtered
        filtered = speakerLabel.replace(unlabelled) { match ->
            val textFollows = unlabelled.substring(match.range.last + 1).substringBefore('\n').isNotBlank()
            if (isSpeakerLabel(match.groupValues[2], textFollows)) match.groups[1]?.value.orEmpty() else match.value
        }
        filtered = squareBrackets.replace(filtered, "")
        filtered = parentheses.replace(filtered, "")
        return filtered.lines()
            .filter { line -> line.any { !it.isWhitespace() && it != '-' } }
            .joinToString("\n")
            .takeIf(String::isNotBlank)
    }

    /**
     * "JOHN:" and "[John]:" are always labels. A label in normal case only counts when it reads like a name and the
     * line goes on after it ("John: Hello"), so a sentence that ends in a colon ("He said:") stays.
     */
    internal fun isSpeakerLabel(label: String, textFollows: Boolean): Boolean {
        if (label.startsWith("[")) return true
        if (label.none(Char::isLetter)) return false
        if (label.none(Char::isLowerCase)) return true
        if (!textFollows) return false
        val words = label.trim().split(labelWords)
        return words.size <= 3 && words.none { it.first().isLowerCase() }
    }
}
