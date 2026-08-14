package work.art1st.javarailways.tunnelbridgebuilder.wand

/** Pure state transitions shared by the inventory GUI and tests. */
object BlockWandEditor {
    fun leftClick(
        state: BlockWandState,
        slot: Int,
        heldMaterialKey: String?
    ): BlockWandState {
        require(slot in 0 until BlockWandState.CELL_COUNT)
        if (state.isRailCell(slot)) return state.withCell(slot, WandCell.Air)
        val next = if (heldMaterialKey != null) {
            WandCell.Material(heldMaterialKey)
        } else {
            when (state[slot]) {
                WandCell.Air -> WandCell.Ignore
                WandCell.Ignore, is WandCell.Material -> WandCell.Air
            }
        }
        return state.withCell(slot, next)
    }

    fun rightClick(
        state: BlockWandState,
        slot: Int,
        makePrimary: Boolean = false
    ): BlockWandState = if (makePrimary) {
        state.makePrimaryRailCell(slot)
    } else {
        state.toggleRailCell(slot)
    }
}
