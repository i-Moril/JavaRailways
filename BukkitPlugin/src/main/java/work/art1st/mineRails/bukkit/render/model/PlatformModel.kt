package work.art1st.mineRails.bukkit.render.model

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.entity.Chicken
import org.bukkit.entity.Shulker
import org.bukkit.util.Vector
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.mineRails.bukkit.config.model.PartPlatformConfig
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
import work.art1st.mineRails.bukkit.render.Animation
import work.art1st.mineRails.core.api.physics.World
import kotlin.math.abs

class PlatformModel internal constructor(
    world: World,
    state: ModelState,
    attachedModels: Collection<AbstractModel>,
    animation: Map<String, Animation>,
    private val initialLocation: Location,
    platformConfig: PartPlatformConfig
) : AbstractModel(world, state, attachedModels, animation) {
    private val units: MutableList<PlatformUnit> = mutableListOf()
    private val cellOffsets = platformConfig.cellOffsets().map { Vector3d(it.x, it.y, it.z) }
    private var activationTtlTicks: Int = 0

    private fun spawnEntities() {
        if (units.isNotEmpty()) return
        val bukkitWorld = world.bukkitWorldData.bukkitWorld
        for (offset in cellOffsets) {
            val chicken = bukkitWorld.spawn(initialLocation, Chicken::class.java)
            chicken.isPersistent = false
            chicken.isSilent = true
            chicken.isInvisible = true
            chicken.isInvulnerable = true
            chicken.isCollidable = false
            chicken.setAI(false)
            chicken.setGravity(false)
            chicken.setIsChickenJockey(true)

            val shulker = bukkitWorld.spawn(initialLocation, Shulker::class.java)
            shulker.isPersistent = false
            shulker.isSilent = true
            shulker.isInvisible = true
            shulker.isInvulnerable = true
            shulker.isCollidable = true
            shulker.setAI(false)
            shulker.setGravity(false)
            shulker.setAttachedFace(BlockFace.UP)
            shulker.setPeek(0.0f)

            chicken.addPassenger(shulker)
            units += PlatformUnit(chicken, shulker, offset)
        }
    }

    private fun destroyEntities() {
        units.forEach { unit ->
            unit.shulker.remove()
            unit.chicken.remove()
        }
        units.clear()
    }

    override fun updateState(
        parentRelativeTransform: CompactTransform,
        rootTransform: CompactTransform,
        rootVelocity: org.joml.Vector3dc,
        transformDirty: Boolean
    ) {
        super.updateState(parentRelativeTransform, rootTransform, rootVelocity, transformDirty)

        if (hasNearbyPlayers(state.position)) {
            activationTtlTicks = ACTIVE_TTL_TICKS
        } else if (activationTtlTicks > 0) {
            activationTtlTicks -= 1
        }

        if (activationTtlTicks <= 0) {
            if (units.isNotEmpty()) destroyEntities()
            return
        }

        if (units.isEmpty()) {
            spawnEntities()
        }

        val (yaw, pitch) = entityYawPitch()
        for (unit in units) {
            val position = Vector3d(unit.localOffset)
            state.worldTransform.rotation.transform(position)
            position.add(state.worldTransform.position)
            val location = Location(
                unit.chicken.world,
                position.x,
                position.y - SHULKER_PASSENGER_Y_OFFSET,
                position.z,
                yaw,
                pitch
            )
            unit.chicken.teleport(location)
            unit.chicken.setRotation(location.yaw, location.pitch)
            unit.chicken.setBodyYaw(location.yaw)
            unit.chicken.velocity = Vector(rootVelocity.x(), rootVelocity.y(), rootVelocity.z())
        }
    }

    private fun hasNearbyPlayers(position: Vector3dc): Boolean {
        val radiusSq = ACTIVE_HORIZONTAL_RADIUS * ACTIVE_HORIZONTAL_RADIUS
        val px = position.x()
        val py = position.y()
        val pz = position.z()
        return world.bukkitWorldData.bukkitWorld.players.any { player ->
            if (!player.isValid || player.isDead || player.gameMode == GameMode.SPECTATOR) {
                return@any false
            }
            val location = player.location
            val dx = location.x - px
            val dz = location.z - pz
            dx * dx + dz * dz <= radiusSq && abs(location.y - py) <= ACTIVE_VERTICAL_RADIUS
        }
    }

    private fun entityYawPitch(): Pair<Float, Float> {
        val forward = Vector3d(0.0, 0.0, 1.0)
        state.worldTransform.rotation.transform(forward)
        val yaw = Math.toDegrees(kotlin.math.atan2(-forward.x(), forward.z())).toFloat()
        val pitch = Math.toDegrees(kotlin.math.asin(forward.y().coerceIn(-1.0, 1.0))).toFloat()
        return yaw to pitch
    }

    override fun destroy() {
        destroyEntities()
        super.destroy()
    }

    private data class PlatformUnit(
        val chicken: Chicken,
        val shulker: Shulker,
        val localOffset: Vector3d
    )

    companion object {
        /** Empirical mount lift compensation for a shulker passenger on a chicken. */
        private const val SHULKER_PASSENGER_Y_OFFSET = 0.6875

        /**
         * Only keep platform collision entities alive while players are close enough to potentially board
         * or walk inside the carriage. This turns the platform cost from “per carriage, always on” into
         * “per occupied/nearby carriage”.
         */
        private const val ACTIVE_HORIZONTAL_RADIUS = 20.0
        private const val ACTIVE_VERTICAL_RADIUS = 6.0
        private const val ACTIVE_TTL_TICKS = 40
    }
}
