//package work.art1st.javarailways.bukkit.nms
//
//import net.minecraft.world.entity.EntityType
//import net.minecraft.world.entity.animal.happyghast.HappyGhast
//import net.minecraft.world.level.Level
//import org.bukkit.Location
//import org.bukkit.craftbukkit.CraftWorld
//
//class PlatformEntity private constructor(type: EntityType<out HappyGhast>, world: Level) : HappyGhast(type, world) {
//    init {
//        isInvisible = true
//        isSilent = true
//        isPersistenceRequired = false
//    }
//
//    override fun tick() {
//        // Don't tick
//    }
//
//    override fun registerGoals() {
//        // Don't goal
//    }
//
//    companion object {
//        fun spawn(location: Location) : org.bukkit.entity.HappyGhast {
//            val level = (location.world as CraftWorld).handle
//            val nmsEntity = PlatformEntity(EntityType.HAPPY_GHAST, level)
//            nmsEntity.absSnapTo(location.x, location.y, location.z, location.yaw, location.pitch)
//            val bukkitEntity = nmsEntity.bukkitEntity as org.bukkit.entity.HappyGhast
//            bukkitEntity.isInvisible = true
//            bukkitEntity.isSilent = true
//            bukkitEntity.isPersistent = false
//            location.world.addEntity(bukkitEntity)
//            return bukkitEntity
//        }
//    }
//}