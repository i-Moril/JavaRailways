package work.art1st.mineRails.bukkit.nms

import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.monster.Ghast
import net.minecraft.world.level.Level

abstract class PlatformEntity(type: EntityType<out Ghast>, world: Level) : Ghast(type, world) {
    init {
        isInvisible = true
        isSilent = true
        isPersistenceRequired = false
    }

    override fun tick() {
        // Don't tick
    }

    override fun registerGoals() {
        // Don't goal
    }
}