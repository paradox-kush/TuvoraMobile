package com.nuvio.app.features.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LibraryFavouriteUndoTest {
    private fun item(id: String, stamp: Long) = LibraryItem(id = id, type = "tv", name = id, savedAtEpochMs = stamp)
    private fun loaded(items: List<LibraryItem>): LibraryLocalState = LibraryLocalState().apply {
        val token = beginProfileLoad(1).snapshot.token
        completeProfileLoad(token, 1, items)
    }

    @Test fun `undo handle executes its mutation only once`() {
        var writes = 0
        val undo = LibrarySavedUndo { writes += 1 }
        undo.undo()
        undo.undo()
        assertEquals(1, writes)
    }

    @Test fun `removed middle favourite returns at its original position and stamp`() {
        val original = item("middle", 200).copy(logo = "original-logo")
        val state = loaded(listOf(item("top", 300), original, item("bottom", 100)))
        val before = state.snapshot()
        state.toggle(original)
        val restored = state.undoToggle(before, original.id, original.type).snapshot
        assertEquals(listOf("top", "middle", "bottom"), restored.items.sortedByDescending { it.savedAtEpochMs }.map { it.id })
        assertEquals(original, restored.items.first { it.id == original.id })
        assertEquals(listOf("middle"), restored.pendingUpsertKeys.map { it.contentId })
        assertEquals(emptyList(), restored.pendingDeleteKeys)
    }

    @Test fun `undo newly added favourite removes it and is idempotent`() {
        val state = loaded(emptyList())
        val before = state.snapshot()
        state.toggle(item("new", 100))
        assertEquals(1, state.undoToggle(before, "new", "tv").affectedCount)
        assertEquals(0, state.undoToggle(before, "new", "tv").affectedCount)
        assertEquals(emptyList(), state.snapshot().items)
        assertEquals(listOf("new"), state.snapshot().pendingDeleteKeys.map { it.contentId })
    }

    @Test fun `undo removal is idempotent and preserves a subsequent readd`() {
        val state = loaded(listOf(item("one", 100)))
        val before = state.snapshot()
        state.remove("one", "tv")
        val newer = item("one", 900).copy(name = "edited")
        state.upsert(newer)
        assertEquals(0, state.undoToggle(before, "one", "tv").affectedCount)
        assertEquals(newer, state.findById("one"))
    }

    @Test fun `undo never crosses profile generations even after returning to same profile`() {
        val state = loaded(listOf(item("one", 100)))
        val before = state.snapshot()
        state.remove("one", "tv")
        val token = state.beginProfileLoad(1).snapshot.token
        assertNotNull(state.completeProfileLoad(token, 1, listOf(item("other", 700))))
        assertEquals(0, state.undoToggle(before, "one", "tv").affectedCount)
        assertEquals(listOf("other"), state.snapshot().items.map { it.id })
    }

    @Test fun `undo only restores its channel and keeps unrelated edits`() {
        val state = loaded(listOf(item("one", 100), item("two", 200)))
        val before = state.snapshot()
        state.remove("one", "tv")
        state.upsert(item("two", 800))
        state.upsert(item("three", 900))
        state.undoToggle(before, "one", "tv")
        assertEquals(mapOf("one" to 100L, "two" to 800L, "three" to 900L), state.snapshot().items.associate { it.id to it.savedAtEpochMs })
    }
}
