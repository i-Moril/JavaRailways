package work.art1st.mineRails.bukkit.render.model

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.entity.Chicken
import org.bukkit.entity.Player
import org.bukkit.entity.Shulker
import org.bukkit.util.Vector
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.mineRails.bukkit.config.model.PartPlatformConfig
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
import work.art1st.mineRails.bukkit.render.Animation
import work.art1st.mineRails.core.api.physics.World
import java.util.UUID
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
    private val platformHalfX = ((platformConfig.rows.coerceAtLeast(1) - 1) * platformConfig.spacingX) / 2.0 + CELL_HALF_EXTENT
    private val platformHalfZ = ((platformConfig.columns.coerceAtLeast(1) - 1) * platformConfig.spacingZ) / 2.0 + CELL_HALF_EXTENT
    private val activePlayers: MutableSet<UUID> = hashSetOf()
    private var lastRootVelocity: Vector3dc = Vector3d()

    private fun spawnEntities() {
        if (units.isNotEmpty()) return
        val bukkitWorld = world.bukkitWorldData.bukkitWorld
        val (yaw, pitch) = entityYawPitch()
        for (offset in cellOffsets) {
            val location = unitLocation(offset, yaw, pitch)
            val chicken = bukkitWorld.spawn(location, Chicken::class.java) { chicken ->
                chicken.isPersistent = false
                chicken.isSilent = true
                chicken.isInvisible = true
                chicken.isInvulnerable = true
                chicken.isCollidable = false
                chicken.setAI(false)
                chicken.setGravity(false)
                chicken.setIsChickenJockey(true)
            }

            val shulker = bukkitWorld.spawn(location, Shulker::class.java) { shulker ->
                shulker.isPersistent = false
                shulker.isSilent = true
                shulker.isInvisible = true
                shulker.isInvulnerable = true
                shulker.isCollidable = true
                shulker.setAI(false)
                shulker.setGravity(false)
                shulker.setAttachedFace(BlockFace.UP)
                shulker.setPeek(0.0f)
            }

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
        rootVelocity: Vector3dc,
        transformDirty: Boolean
    ) {
        super.updateState(parentRelativeTransform, rootTransform, rootVelocity, transformDirty)
        lastRootVelocity = Vector3d(rootVelocity)
        if (units.isNotEmpty()) {
            syncEntities(rootVelocity)
        }
    }

    fun refreshActivation(player: Player) {
        if (!isEligible(player)) {
            deactivateFor(player)
            return
        }

        val playerId = player.uniqueId
        val alreadyActive = playerId in activePlayers
        val horizontalMargin = if (alreadyActive) DEACTIVATE_HORIZONTAL_MARGIN else ACTIVATE_HORIZONTAL_MARGIN
        val verticalMargin = if (alreadyActive) DEACTIVATE_VERTICAL_MARGIN else 0.0
        if (!containsPlayer(player, horizontalMargin, verticalMargin)) {
            deactivateFor(player)
            return
        }

        activePlayers.add(playerId)
        if (units.isEmpty()) {
            spawnEntities()
            syncEntities(lastRootVelocity)
        }
    }

    fun deactivateFor(player: Player) {
        if (activePlayers.remove(player.uniqueId) && activePlayers.isEmpty()) {
            destroyEntities()
        }
    }

    private fun syncEntities(rootVelocity: Vector3dc) {
        val (yaw, pitch) = entityYawPitch()
        for (unit in units) {
            val location = unitLocation(unit.localOffset, yaw, pitch)
            unit.chicken.teleport(location)
            unit.chicken.setRotation(location.yaw, location.pitch)
            unit.chicken.setBodyYaw(location.yaw)
            unit.chicken.velocity = Vector(rootVelocity.x(), rootVelocity.y(), rootVelocity.z())
        }
    }

    private fun unitLocation(offset: Vector3dc, yaw: Float, pitch: Float): Location {
        val position = Vector3d(offset)
        state.worldTransform.rotation.transform(position)
        position.add(state.worldTransform.position)
        return Location(
            world.bukkitWorldData.bukkitWorld,
            position.x,
            position.y - SHULKER_PASSENGER_Y_OFFSET,
            position.z,
            yaw,
            pitch
        )
    }

    private fun isEligible(player: Player): Boolean {
        return player.isValid &&
            !player.isDead &&
            player.gameMode != GameMode.SPECTATOR &&
            player.world == world.bukkitWorldData.bukkitWorld
    }

    private fun containsPlayer(player: Player, horizontalMargin: Double, verticalMargin: Double): Boolean {
        val location = player.location
        val local = Vector3d(location.x, location.y, location.z).sub(state.worldTransform.position)
        Quaterniond(state.worldTransform.rotation).conjugate().transform(local)
        return abs(local.x()) <= platformHalfX + horizontalMargin &&
            abs(local.z()) <= platformHalfZ + horizontalMargin &&
            local.y() >= -ACTIVE_VERTICAL_BELOW - verticalMargin &&
            local.y() <= ACTIVE_VERTICAL_ABOVE + verticalMargin
    }

    private fun entityYawPitch(): Pair<Float, Float> {
        val forward = Vector3d(0.0, 0.0, 1.0)
        state.worldTransform.rotation.transform(forward)
        val yaw = Math.toDegrees(kotlin.math.atan2(-forward.x(), forward.z())).toFloat()
        val pitch = Math.toDegrees(kotlin.math.asin(forward.y().coerceIn(-1.0, 1.0))).toFloat()
        return yaw to pitch
    }

    override fun destroy() {
        activePlayers.clear()
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

        private const val CELL_HALF_EXTENT = 0.5
        private const val ACTIVATE_HORIZONTAL_MARGIN = 3.0
        private const val DEACTIVATE_HORIZONTAL_MARGIN = 5.0
        private const val ACTIVE_VERTICAL_ABOVE = 3.0
        private const val ACTIVE_VERTICAL_BELOW = 2.0
        private const val DEACTIVATE_VERTICAL_MARGIN = 1.0
    }
}
