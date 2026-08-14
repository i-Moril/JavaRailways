package work.art1st.javarailways.tunnelbridgebuilder.rail

import org.bukkit.block.Block
import org.bukkit.block.BlockState

/** A Paper block snapshot including block data and block-entity data such as container contents. */
internal class FullBlockSnapshot private constructor(
    private val state: BlockState
) {
    val x: Int get() = state.x
    val y: Int get() = state.y
    val z: Int get() = state.z

    fun restore() {
        check(state.update(true, false)) {
            "Could not restore block snapshot at $x,$y,$z"
        }
    }

    companion object {
        fun capture(block: Block): FullBlockSnapshot = FullBlockSnapshot(block.getState(true))
    }
}
