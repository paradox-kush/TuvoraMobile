package com.nuvio.app.features.home

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.nuvio.app.core.ui.NuvioScreen

/**
 * Lets Home declare its sections once, in any order, and still lay them out in the order the person chose
 * ([HomeSectionOrderPolicy]). Inside the Home list body, [section] marks where a section's items begin;
 * everything declared after it (until the next marker) belongs to that section. When the body ends, the
 * sections are emitted into the real list in [order]; ids not in [order] (hidden or unsupported here) emit
 * nothing, and items declared before the first marker are always emitted first.
 *
 * Item keys, content types and item content are passed through untouched, so a section looks and behaves
 * exactly as it did when it was written inline.
 */
internal class HomeSectionSlots(private val order: List<String>) : LazyListScope {
    private val leading = mutableListOf<LazyListScope.() -> Unit>()
    private val slots = LinkedHashMap<String, MutableList<LazyListScope.() -> Unit>>()
    private var current: MutableList<LazyListScope.() -> Unit> = leading

    /** Everything declared after this call, up to the next [section], is the section [id]. */
    fun section(id: String) {
        current = slots.getOrPut(id) { mutableListOf() }
    }

    fun emitInto(target: LazyListScope) {
        leading.forEach { it(target) }
        order.forEach { id -> slots[id]?.forEach { it(target) } }
    }

    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        current += { item(key, contentType, content) }
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        current += { items(count, key, contentType, itemContent) }
    }
}

/** Builds [content] as Home sections and emits them into this list in [order]. */
internal fun LazyListScope.orderedHomeSections(order: List<String>, content: HomeSectionSlots.() -> Unit) {
    HomeSectionSlots(order).apply(content).emitInto(this)
}

/** The Home list: [NuvioScreen] whose body is declared as sections and laid out in [order]. */
@Composable
internal fun HomeSectionsScreen(
    order: List<String>,
    modifier: Modifier,
    horizontalPadding: Dp,
    topPadding: Dp,
    listState: LazyListState,
    content: HomeSectionSlots.() -> Unit,
) {
    NuvioScreen(
        modifier = modifier,
        horizontalPadding = horizontalPadding,
        topPadding = topPadding,
        listState = listState,
    ) {
        orderedHomeSections(order, content)
    }
}
