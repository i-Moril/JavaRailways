package work.art1st.mineRails.asset

import org.bukkit.entity.Display
import org.bukkit.entity.Player
import work.art1st.mineRails.core.asset.Asset
import work.art1st.mineRails.core.asset.Renderable

abstract class DisplayEntityAsset : Asset {
    private var displayEntity: Display? = null

    abstract fun load() : Display

    fun unload() {
        displayEntity = null
    }

    fun render(renderable: Renderable, player: Player): String {
        // Construct packet
        val packet =
    }
}
