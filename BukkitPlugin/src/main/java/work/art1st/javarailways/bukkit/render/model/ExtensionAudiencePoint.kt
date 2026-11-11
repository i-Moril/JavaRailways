package work.art1st.javarailways.bukkit.render.model

import java.util.UUID

/** Detached eye/screen point. The screen point is the transformed part origin, never car center. */
internal data class ExtensionAudiencePoint(val worldId: UUID, val x: Double, val y: Double, val z: Double) {
    fun isWithin(screen: ExtensionAudiencePoint, maximumDistanceBlocks: Double?): Boolean {
        if (worldId != screen.worldId) return false
        if (maximumDistanceBlocks == null) return true
        if (maximumDistanceBlocks == 0.0) return false
        val dx = x - screen.x
        val dy = y - screen.y
        val dz = z - screen.z
        return dx * dx + dy * dy + dz * dz <= maximumDistanceBlocks * maximumDistanceBlocks
    }
}
