package work.art1st.mineRails.bukkit.util

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity

/** Runtime assertions for Folia ownership; unlike Bukkit.isPrimaryThread, these are target-aware. */
object BukkitThreadOwnership {
    fun isOwnedByCurrentRegion(entity: Entity): Boolean = Bukkit.isOwnedByCurrentRegion(entity)

    fun isOwnedByCurrentRegion(location: Location): Boolean = Bukkit.isOwnedByCurrentRegion(location)

    fun ensureEntity(entity: Entity, operation: String) {
        check(Bukkit.isOwnedByCurrentRegion(entity)) {
            "$operation must run on the scheduler that owns entity ${entity.uniqueId}"
        }
    }

    fun ensureLocation(location: Location, operation: String) {
        check(Bukkit.isOwnedByCurrentRegion(location)) {
            "$operation must run on the region that owns $location"
        }
    }
}
