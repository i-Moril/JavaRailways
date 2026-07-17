package work.art1st.mineRails.bukkit.render.chunk

import org.bukkit.entity.Player
import work.art1st.mineRails.bukkit.BukkitPlugin
import java.util.UUID
import kotlin.math.min

data class ViewDistanceSettings(
    val viewDistance: Double,
    val lodThreshold: Double,
)

class ViewDistanceManager(
    private val maxViewDistance: Double
) {
    private val viewDistanceMap: MutableMap<UUID, ViewDistanceSettings> = HashMap()

    fun settingsFor(player: Player): ViewDistanceSettings = viewDistanceMap[player.uniqueId]
        ?: ViewDistanceSettings(
            viewDistance = min(player.clientViewDistance.coerceAtLeast(0) * 16.0, maxViewDistance),
            lodThreshold = DEFAULT_LOD_THRESHOLD
        )

    fun set(playerId: UUID, settings: ViewDistanceSettings) {
        require(settings.viewDistance.isFinite() && settings.viewDistance in 0.0..maxViewDistance) {
            "Player display distance must be finite and between 0 and $maxViewDistance blocks"
        }
        require(settings.lodThreshold.isFinite() && settings.lodThreshold >= 0.0) {
            "Player LOD threshold must be finite and >= 0"
        }
        viewDistanceMap[playerId] = settings
    }

    fun clear(playerId: UUID) {
        viewDistanceMap.remove(playerId)
    }

    companion object {
        private const val DEFAULT_LOD_THRESHOLD = 16.0
    }
}

val Player.railViewDistanceSettings: ViewDistanceSettings
    get() = BukkitPlugin.INSTANCE.viewDistanceManager.settingsFor(this)
