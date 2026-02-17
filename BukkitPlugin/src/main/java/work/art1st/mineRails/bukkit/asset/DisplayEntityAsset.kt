package work.art1st.mineRails.bukkit.asset

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import work.art1st.mineRails.core.api.render.Asset
import work.art1st.mineRails.core.api.render.Renderable

abstract class DisplayEntityAsset : Asset {
    private var displayEntity: Display? = null

    abstract fun load() : Display

    fun unload() {
        displayEntity = null
    }

//    fun render(renderable: Renderable, player: Player): String {
//        // Construct packet
//        val packet = WrapperPlayServerEntityRelativeMoveAndRotation(
//
//        )
//    }
}