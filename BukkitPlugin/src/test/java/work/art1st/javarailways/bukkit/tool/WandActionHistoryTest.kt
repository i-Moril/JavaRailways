package work.art1st.javarailways.bukkit.tool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class WandActionHistoryTest {
    @Test
    fun `keeps only the latest ten actions`() {
        val history = WandActionHistory()
        repeat(12) { index ->
            history.record(WandAction.CreateAnchor(UUID.randomUUID(), "A$index"))
        }

        assertEquals(10, history.undoSize)
        val undoneIds = List(10) {
            (history.takeUndo() as WandAction.CreateAnchor).anchorId
        }
        assertEquals((11 downTo 2).map { "A$it" }, undoneIds)
        assertNull(history.takeUndo())
        assertEquals(10, history.redoSize)
    }

    @Test
    fun `new action clears redo history`() {
        val history = WandActionHistory()
        history.record(WandAction.CreateAnchor(UUID.randomUUID(), "A0"))
        history.takeUndo()
        assertEquals(1, history.redoSize)

        history.record(WandAction.CreateAnchor(UUID.randomUUID(), "A1"))

        assertEquals(0, history.redoSize)
        assertNull(history.takeRedo())
    }

    @Test
    fun `consumed construction anchors do not occupy undo entries`() {
        val history = WandActionHistory()
        val worldId = UUID.randomUUID()
        val railId = UUID.randomUUID()
        history.record(WandAction.CreateRail(worldId, railId, "standard"))
        history.record(WandAction.MultiAction(listOf(
            WandAction.CreateAnchor(worldId, "A0"),
            WandAction.CreateAnchor(worldId, "A1")
        )))
        history.record(WandAction.CreateAnchor(worldId, "A2"))

        history.discardCreatedAnchors(setOf("A0", "A1"))

        assertEquals(2, history.undoSize)
        assertEquals("A2", (history.takeUndo() as WandAction.CreateAnchor).anchorId)
        val retainedRail = history.takeUndo()
        assertTrue(retainedRail is WandAction.CreateRail)
        assertEquals(railId, (retainedRail as WandAction.CreateRail).railId)
    }
}
