package work.art1st.mineRails.bukkit.render.chunk

import org.bukkit.entity.Player
import work.art1st.mineRails.bukkit.BukkitPlugin

data class ViewDistanceSettings(
    val viewDistance: Double,
    val lodThreshold: Double,
)

class ViewDistanceManager {
    val viewDistanceMap : MutableMap<Player, ViewDistanceSettings> = HashMap()
    // TODO: Set, command
}

val Player.railViewDistanceSettings: ViewDistanceSettings
    get() = BukkitPlugin.INSTANCE.viewDistanceManager.viewDistanceMap.getOrDefault(this,
        ViewDistanceSettings(
            this.viewDistance * 16.0,
            16.0
        )
    )