package com.nuvio.app.features.party

// Pure grouping rules for the chat list. Kept out of PartyChatContent.kt (Compose UI, excluded from
// tvosCore) so the shared PartyChatTest compiles and runs on every target.

internal data class PartyChatRow(
    val message: PartyChatMessage,
    val startsGroup: Boolean,
    val endsGroup: Boolean,
    val showTime: Boolean,
)

internal fun partyChatRows(messages: List<PartyChatMessage>): List<PartyChatRow> =
    messages.mapIndexed { index, message ->
        val previous = messages.getOrNull(index - 1)
        val next = messages.getOrNull(index + 1)
        val showTime = previous == null || message.sentAt - previous.sentAt > TIME_GAP_MS
        val nextShowsTime = next != null && next.sentAt - message.sentAt > TIME_GAP_MS
        PartyChatRow(
            message = message,
            startsGroup = showTime || previous?.author != message.author,
            endsGroup = next == null || nextShowsTime || next.author != message.author,
            showTime = showTime,
        )
    }

private val PartyChatMessage.author: String
    get() = if (mine) "" else memberId

private const val TIME_GAP_MS = 10 * 60 * 1000L
