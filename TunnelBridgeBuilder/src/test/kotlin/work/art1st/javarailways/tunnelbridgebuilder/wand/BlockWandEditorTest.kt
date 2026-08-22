package work.art1st.javarailways.tunnelbridgebuilder.wand

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BlockWandEditorTest {
    @Test
    fun `empty-hand left click cycles air and ignore`() {
        val initial = BlockWandState.createDefault()

        val air = BlockWandEditor.leftClick(initial, slot = 7, heldMaterialKey = null)
        val ignored = BlockWandEditor.leftClick(air, slot = 7, heldMaterialKey = null)

        assertEquals(WandCell.Air, air[7])
        assertEquals(WandCell.Ignore, ignored[7])
    }

    @Test
    fun `left click with a material copies it without changing other cells`() {
        val initial = BlockWandState.createDefault().withCell(1, WandCell.Air)

        val changed = BlockWandEditor.leftClick(initial, slot = 2, heldMaterialKey = "minecraft:polished_andesite")

        assertEquals(WandCell.Material("minecraft:polished_andesite"), changed[2])
        assertEquals(WandCell.Air, changed[1])
    }

    @Test
    fun `shift left cycles supported slab placement rules and returns to regular`() {
        val initial = BlockWandState.createDefault().withCell(
            2,
            WandCell.Material("minecraft:stone_slab")
        )
        val rules = PlacementRule.entries.toList()

        val matched = BlockWandEditor.shiftLeftClick(initial, 2, rules)
        val fillDown = BlockWandEditor.shiftLeftClick(matched, 2, rules)
        val fillUp = BlockWandEditor.shiftLeftClick(fillDown, 2, rules)
        val regular = BlockWandEditor.shiftLeftClick(fillUp, 2, rules)

        assertEquals(PlacementRule.MATCH_HALF, (matched[2] as WandCell.Material).placementRule)
        assertEquals(PlacementRule.MATCH_HALF_FILL_DOWN, (fillDown[2] as WandCell.Material).placementRule)
        assertEquals(PlacementRule.MATCH_HALF_FILL_UP, (fillUp[2] as WandCell.Material).placementRule)
        assertEquals(PlacementRule.REGULAR, (regular[2] as WandCell.Material).placementRule)
    }

    @Test
    fun `shift left edits placement rules on rail cells like other cells`() {
        val material = BlockWandState.createDefault().withCell(
            2,
            WandCell.Material("minecraft:stone_slab", PlacementRule.MATCH_HALF)
        )
        val rail = BlockWandEditor.rightClick(material, slot = 2)

        val changed = BlockWandEditor.shiftLeftClick(rail, 2, PlacementRule.entries.toList())

        assertEquals(
            WandCell.Material("minecraft:stone_slab", PlacementRule.MATCH_HALF_FILL_DOWN),
            changed[2]
        )
        assertEquals(rail.primaryRailCell, changed.primaryRailCell)
    }

    @Test
    fun `first right click creates primary rail and later clicks toggle secondary rails`() {
        val initial = BlockWandState.createDefault()
            .withCell(4, WandCell.Material("minecraft:stone"))
            .withCell(20, WandCell.Material("minecraft:polished_andesite"))

        val primary = BlockWandEditor.rightClick(initial, slot = 4)
        val secondary = BlockWandEditor.rightClick(primary, slot = 20)
        val removed = BlockWandEditor.rightClick(secondary, slot = 20)

        assertEquals(GridCell(4, 0), secondary.primaryRailCell)
        assertEquals(setOf(GridCell(4, 2)), secondary.secondaryRailCells)
        assertEquals(WandCell.Material("minecraft:stone"), secondary[4])
        assertEquals(WandCell.Material("minecraft:polished_andesite"), secondary[20])
        assertEquals(emptySet<GridCell>(), removed.secondaryRailCells)
        assertEquals(WandCell.Material("minecraft:polished_andesite"), removed[20])
    }

    @Test
    fun `shift right click makes a secondary rail primary`() {
        val state = BlockWandEditor.rightClick(
            BlockWandEditor.rightClick(BlockWandState.createDefault(), slot = 4),
            slot = 20
        )

        val changed = BlockWandEditor.rightClick(state, slot = 20, makePrimary = true)

        assertEquals(GridCell(4, 2), changed.primaryRailCell)
        assertEquals(setOf(GridCell(4, 0)), changed.secondaryRailCells)
    }

    @Test
    fun `removing primary promotes a secondary rail`() {
        val state = BlockWandEditor.rightClick(
            BlockWandEditor.rightClick(BlockWandState.createDefault(), slot = 4),
            slot = 20
        )

        val changed = BlockWandEditor.rightClick(state, slot = 4)

        assertEquals(GridCell(4, 2), changed.primaryRailCell)
        assertEquals(emptySet<GridCell>(), changed.secondaryRailCells)
    }

    @Test
    fun `left click edits rail cells like other cells without removing their markers`() {
        val initial = BlockWandState.createDefault()
            .withCell(10, WandCell.Material("minecraft:andesite"))
            .withCell(12, WandCell.Air)
        val state = BlockWandEditor.rightClick(
            BlockWandEditor.rightClick(initial, slot = 10),
            slot = 12
        )

        val primary = BlockWandEditor.leftClick(state, 10, "minecraft:stone")
        val secondary = BlockWandEditor.leftClick(state, 12, null)

        assertEquals(WandCell.Material("minecraft:stone"), primary[10])
        assertEquals(WandCell.Ignore, secondary[12])
        assertEquals(state.primaryRailCell, primary.primaryRailCell)
        assertEquals(state.secondaryRailCells, secondary.secondaryRailCells)
    }
}
