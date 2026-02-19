package work.art1st.mineRails.bukkit.render

import org.bukkit.entity.Display
import work.art1st.mineRails.core.api.render.Asset
import work.art1st.mineRails.core.api.render.SynchronizedRenderable

abstract class DisplayEntityAsset : Asset {
    private var displayEntity: Display? = null

    abstract fun load() : Display

    fun unload() {
        displayEntity = null
    }

    override fun updateState(
        lastState: SynchronizedRenderable.StateSnapshot,
        currentState: SynchronizedRenderable.StateSnapshot
    ) {
    }

//    fun render(renderable: Renderable, player: Player): String {
//        // Construct packet
//        val packet = WrapperPlayServerEntityRelativeMoveAndRotation(
//
//        )
//    }
}