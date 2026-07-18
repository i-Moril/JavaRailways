package work.art1st.mineRails.bukkit.render.model

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.block.BlockFace
import io.papermc.paper.entity.TeleportFlag
import org.bukkit.entity.Chicken
import org.bukkit.entity.Player
import org.bukkit.entity.Shulker
import org.bukkit.util.Vector
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.config.model.PartPlatformConfig
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
import work.art1st.mineRails.bukkit.render.Animation
import work.art1st.mineRails.bukkit.util.BukkitThreadOwnership
import work.art1st.mineRails.core.api.physics.World
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class PlatformModel internal constructor(
    world: World,
    state: ModelState,
    attachedModels: Collection<AbstractModel>,
    animation: Map<String, Animation>,
    private val initialLocation: Location,
    platformConfig: PartPlatformConfig
) : AbstractModel(world, state, attachedModels, animation) {
    private val units: MutableList<PlatformUnit> = CopyOnWriteArrayList()
    private val cellOffsets = platformConfig.cellOffsets().map { Vector3d(it.x, it.y, it.z) }
    private val platformHalfX = ((platformConfig.rows.coerceAtLeast(1) - 1) * platformConfig.spacingX) / 2.0 + CELL_HALF_EXTENT
    private val platformHalfZ = ((platformConfig.columns.coerceAtLeast(1) - 1) * platformConfig.spacingZ) / 2.0 + CELL_HALF_EXTENT
    private val activePlayers: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val movementRevision = AtomicLong(0L)
    private val lifecycleRevision = AtomicLong(0L)
    private val spawnScheduled = AtomicBoolean(false)

    private fun spawnEntities() {
        if (units.isNotEmpty() || cellOffsets.isEmpty() || !spawnScheduled.compareAndSet(false, true)) return
        val bukkitWorld = world.bukkitWorldData.bukkitWorld
        val (yaw, pitch) = entityYawPitch()
        val generation = lifecycleRevision.get()
        val remaining = AtomicInteger(cellOffsets.size)
        for (offset in cellOffsets) {
            val location = unitLocation(offset, yaw, pitch)
            BukkitPlugin.INSTANCE.platformDispatcher.runAt(location) {
                try {
                    synchronized(this@PlatformModel) {
                        if (generation != lifecycleRevision.get() || activePlayers.isEmpty()) return@synchronized
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
                } finally {
                    if (remaining.decrementAndGet() == 0) spawnScheduled.set(false)
                }
            }
        }
    }

    @Synchronized
    private fun destroyEntities() {
        movementRevision.incrementAndGet()
        lifecycleRevision.incrementAndGet()
        val removed = units.toList()
        units.clear()
        removed.forEach { unit ->
            BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(unit.chicken, action = {
                unit.chicken.eject()
                unit.chicken.remove()
            })
            BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(unit.shulker, action = { unit.shulker.remove() })
        }
    }

    override fun updateState(
        parentRelativeTransform: CompactTransform,
        rootTransform: CompactTransform,
        rootVelocity: Vector3dc,
        transformDirty: Boolean
    ) {
        super.updateState(parentRelativeTransform, rootTransform, rootVelocity, transformDirty)
        if (activePlayers.isNotEmpty() && units.isEmpty()) spawnEntities()
        if (units.isNotEmpty()) syncEntities(rootVelocity)
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
        if (units.isEmpty()) spawnEntities()
    }

    fun deactivateFor(player: Player) {
        if (activePlayers.remove(player.uniqueId) && activePlayers.isEmpty()) {
            destroyEntities()
        }
    }

    private fun syncEntities(rootVelocity: Vector3dc) {
        val (yaw, pitch) = entityYawPitch()
        val velocity = Vector(rootVelocity.x(), rootVelocity.y(), rootVelocity.z())
        val revision = movementRevision.incrementAndGet()
        for (unit in units) {
            val location = unitLocation(unit.localOffset, yaw, pitch)
            BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(unit.chicken, action = entityTask@{
                if (revision != movementRevision.get() || !unit.chicken.isValid) return@entityTask
                if (
                    BukkitPlugin.INSTANCE.platformCapabilities.folia &&
                    !BukkitThreadOwnership.isOwnedByCurrentRegion(location)
                ) {
                    unit.chicken.teleportAsync(location, TeleportFlag.EntityState.RETAIN_PASSENGERS)
                        .thenAccept { teleported ->
                            if (teleported) applyMotionLater(unit.chicken, location, velocity, revision)
                        }
                } else {
                    unit.chicken.teleport(location, TeleportFlag.EntityState.RETAIN_PASSENGERS)
                    applyMotion(unit.chicken, location, velocity)
                }
            })
        }
    }

    private fun applyMotionLater(chicken: Chicken, location: Location, velocity: Vector, revision: Long) {
        BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(chicken, action = motionTask@{
            if (revision != movementRevision.get()) return@motionTask
            applyMotion(chicken, location, velocity)
        })
    }

    private fun applyMotion(chicken: Chicken, location: Location, velocity: Vector) {
        chicken.setRotation(location.yaw, location.pitch)
        chicken.setBodyYaw(location.yaw)
        chicken.velocity = velocity
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
