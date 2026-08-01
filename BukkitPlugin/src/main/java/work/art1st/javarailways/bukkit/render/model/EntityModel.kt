package work.art1st.javarailways.bukkit.render.model

import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.util.Vector
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.extension.bukkitWorldData
import work.art1st.javarailways.bukkit.render.Animation
import work.art1st.javarailways.core.api.physics.World
import java.util.concurrent.atomic.AtomicLong

abstract class EntityModel internal constructor(
    world: World,
    state: ModelState,
    attachedModels: Collection<AbstractModel>,
    animation: Map<String, Animation>,
    @Volatile protected open var entity: Entity? = null
) : AbstractModel(world, state, attachedModels, animation) {
    private val entitySyncRevision = AtomicLong(0L)

    abstract fun spawnEntity()

    override fun updateState(parentRelativeTransform: CompactTransform, rootTransform: CompactTransform, rootVelocity: Vector3dc, transformDirty: Boolean) {
        super.updateState(parentRelativeTransform, rootTransform, rootVelocity, transformDirty)
        entity?.let { syncEntity(it, rootVelocity) }
    }

    protected open fun syncEntity(entity: Entity, rootVelocity: Vector3dc) {
        val location = entityLocation(entity)
        val velocity = Vector(rootVelocity.x(), rootVelocity.y(), rootVelocity.z())
        val revision = entitySyncRevision.incrementAndGet()
        BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(entity, action = entityTask@{
            if (revision != entitySyncRevision.get()) return@entityTask
            if (BukkitPlugin.INSTANCE.platformCapabilities.folia) {
                entity.teleportAsync(location).thenAccept { teleported ->
                    if (teleported) {
                        BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(entity, action = motionTask@{
                            if (revision == entitySyncRevision.get()) {
                                applyEntityMotion(entity, location, velocity)
                            }
                        })
                    }
                }
            } else {
                entity.teleport(location)
                applyEntityMotion(entity, location, velocity)
            }
        })
    }

    private fun applyEntityMotion(entity: Entity, location: Location, velocity: Vector) {
        entity.setRotation(location.yaw, location.pitch)
        if (entity is LivingEntity) entity.setBodyYaw(location.yaw)
        entity.velocity = velocity
        // Ignore roll
    }

    protected open fun entityLocation(entity: Entity): Location {
        val (yaw, pitch) = entityYawPitch()
        return Location(world.bukkitWorldData.bukkitWorld, state.x, state.y, state.z, yaw, pitch)
    }

    protected fun entityYawPitch(): Pair<Float, Float> {
        val forward = Vector3d(0.0, 0.0, 1.0)
        state.worldTransform.rotation.transform(forward)
        val yaw = Math.toDegrees(kotlin.math.atan2(-forward.x(), forward.z())).toFloat()
        val pitch = Math.toDegrees(kotlin.math.asin(forward.y().coerceIn(-1.0, 1.0))).toFloat()
        return yaw to pitch
    }

    override fun destroy() {
        super.destroy()
        entitySyncRevision.incrementAndGet()
        val current = entity
        entity = null
        if (current != null) {
            BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(current, action = { current.remove() })
        }
    }
}