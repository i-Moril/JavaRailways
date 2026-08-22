package work.art1st.javarailways.tunnelbridgebuilder.wand

/** Pure state transitions shared by the inventory GUI and tests. */
object BlockWandEditor {
    fun leftClick(
        state: BlockWandState,
        slot: Int,
        heldMaterialKey: String?
    ): BlockWandState {
        require(slot in 0 until BlockWandState.CELL_COUNT)
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

    fun shiftLeftClick(
        state: BlockWandState,
        slot: Int,
        supportedRules: List<PlacementRule>
    ): BlockWandState {
        require(slot in 0 until BlockWandState.CELL_COUNT)
        require(supportedRules.isNotEmpty()) { "At least one placement rule is required" }
        val material = state[slot] as? WandCell.Material ?: return state
        if (supportedRules.size == 1) {
            return if (material.placementRule == supportedRules.single()) {
                state
            } else {
                state.withCell(slot, material.copy(placementRule = supportedRules.single()))
            }
        }
        val currentIndex = supportedRules.indexOf(material.placementRule)
        val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % supportedRules.size
        return state.withCell(slot, material.copy(placementRule = supportedRules[nextIndex]))
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
