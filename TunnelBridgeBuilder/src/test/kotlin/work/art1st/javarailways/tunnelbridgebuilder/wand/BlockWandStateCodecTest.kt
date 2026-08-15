package work.art1st.javarailways.tunnelbridgebuilder.wand

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BlockWandStateCodecTest {
    @Test
    fun `round trips materials air barrier and multiple rail anchors`() {
        val state = BlockWandState(
            cells = MutableList(BlockWandState.CELL_COUNT) { index ->
                when (index % 3) {
                    0 -> WandCell.Air
                    1 -> WandCell.Ignore
                    else -> WandCell.Material("minecraft:stone")
                }
            },
            primaryRailCell = GridCell(column = 4, row = 2),
            secondaryRailCells = setOf(GridCell(column = 2, row = 2), GridCell(column = 6, row = 2))
        )

        assertEquals(state, BlockWandStateCodec.decode(BlockWandStateCodec.encode(state)))
    }

    @Test
    fun `round trips placement rules and defaults old materials to regular`() {
        val state = BlockWandState.createDefault().withCell(
            3,
            WandCell.Material("minecraft:stone_slab", PlacementRule.MATCH_HALF_FILL_DOWN)
        )
        val encoded = BlockWandStateCodec.encode(state)
        assertEquals(state, BlockWandStateCodec.decode(encoded))

        val regular = BlockWandState.createDefault().withCell(
            3,
            WandCell.Material("minecraft:stone_slab")
        )
        val legacy = BlockWandStateCodec.encode(regular).replace(",\"placementRule\":\"regular\"", "")
        assertEquals(regular, BlockWandStateCodec.decode(legacy))
    }

    @Test
    fun `new state reserves a bottom row and left column for axis markers`() {
        val state = BlockWandState.createDefault()

        assertEquals(8, state.width)
        assertEquals(5, state.height)
        assertEquals(40, state.cells.size)
        assertEquals(List(40) { WandCell.Ignore }, state.cells)
        assertEquals(54, BlockWandState.INVENTORY_SIZE)
        assertNull(state.primaryRailCell)
        assertEquals(emptySet<GridCell>(), state.secondaryRailCells)
    }

    @Test
    fun `rejects invalid cell count overlapping rails and out of bounds rails`() {
        assertThrows(IllegalArgumentException::class.java) {
            BlockWandState(cells = mutableListOf(WandCell.Ignore))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BlockWandState(
                cells = MutableList(40) { WandCell.Ignore },
                primaryRailCell = GridCell(8, 0)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BlockWandState(
                cells = MutableList(40) { WandCell.Ignore },
                primaryRailCell = GridCell(1, 1),
                secondaryRailCells = setOf(GridCell(1, 1))
            )
        }
    }

    @Test
    fun `maps editor cells around the left and bottom axes`() {
        assertEquals(1, BlockWandState.slotOf(GridCell(0, 0)))
        assertEquals(44, BlockWandState.slotOf(GridCell(7, 4)))
        assertEquals(GridCell(0, 0), BlockWandState.cellAtSlot(1))
        assertEquals(GridCell(7, 4), BlockWandState.cellAtSlot(44))
        assertNull(BlockWandState.cellAtSlot(0))
        assertNull(BlockWandState.cellAtSlot(45))
    }
}
