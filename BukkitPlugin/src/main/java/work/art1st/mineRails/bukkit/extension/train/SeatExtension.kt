package work.art1st.mineRails.bukkit.extension.train

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.EntityType
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.config.model.SeatConfig
import work.art1st.mineRails.core.api.physics.train.Seat
import work.art1st.mineRails.core.api.physics.train.Car
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
import kotlin.math.asin
import kotlin.math.atan2

/**
 * 车厢座位系统：管理车厢座位的交互实体和玩家交互。
 *
 * 使用 Interaction 实体作为可交互目标（Paper 1.19.4+）。
 * 座位位置从 CarDefinition.seats 配置读取。
 */
class SeatExtension(
    private val car: Car,
    private val seatConfig: SeatConfig
) : Listener {

    /** seatIndex -> Interaction 实体 */
    private val seatEntities = mutableMapOf<Int, Interaction>()
    /** 正在座位上的玩家: player -> seatIndex */
    private val seatedPlayers = mutableMapOf<Player, Int>()

    init {
        Bukkit.getPluginManager().registerEvents(this, BukkitPlugin.INSTANCE)
        SeatExtension.register(this)
        car.seats.forEach { seat -> spawnSeatEntity(seat) }
    }

    fun onSeatAdded(seat: Seat) {
        spawnSeatEntity(seat)
    }

    fun onSeatRemoved(seatIndex: Int) {
        seatEntities.remove(seatIndex)?.remove()
    }

    /**
     * 每帧更新：
     * 1. 座位实体跟随车厢移动
     * 2. 同步坐着的玩家位置
     */
    fun tick() {
        val carPos = car.position
        val direction = car.direction
        val carLength = car.length

        for ((seatIndex, entity) in seatEntities) {
            val seatPos = computeSeatPosition(carPos, direction, carLength, seatIndex)
            val (yaw, pitch) = computeYawPitch(direction)
            entity.teleport(Location(
                entity.world,
                seatPos.x(), seatPos.y(), seatPos.z(),
                yaw.toFloat(), pitch.toFloat()
            ))
        }

        // 同步坐着的玩家
        val toStand = mutableListOf<Player>()
        for ((player, seatIdx) in seatedPlayers) {
            val entity = seatEntities[seatIdx]
            if (entity == null || !entity.isValid) {
                toStand.add(player)
                continue
            }
            val seatPos = computeSeatPosition(carPos, direction, carLength, seatIdx)
            val (yaw, pitch) = computeYawPitch(direction)
            player.teleport(Location(player.world, seatPos.x(), seatPos.y(), seatPos.z(), yaw.toFloat(), pitch.toFloat()))
        }
        toStand.forEach { seatedPlayers.remove(it) }
    }

    fun destroy() {
        seatedPlayers.keys.toList().forEach { seatedPlayers.remove(it) }
        seatEntities.values.forEach { it.remove() }
        seatEntities.clear()
        SeatExtension.unregister(this)
        PlayerInteractAtEntityEvent.getHandlerList().unregister(this)
        PlayerMoveEvent.getHandlerList().unregister(this)
    }

    fun makePlayerSit(player: Player, seatIndex: Int) {
        seatedPlayers[player] = seatIndex
        val entity = seatEntities[seatIndex] ?: return
        val seatPos = computeSeatPosition(car.position, car.direction, car.length, seatIndex)
        val (yaw, pitch) = computeYawPitch(car.direction)
        player.teleport(Location(player.world, seatPos.x(), seatPos.y(), seatPos.z(), yaw.toFloat(), pitch.toFloat()))
    }

    fun makePlayerStand(player: Player) {
        seatedPlayers.remove(player)
    }

    fun getFirstFreeSeat(): Int? {
        val occupiedIndices = seatedPlayers.values.toSet()
        return seatEntities.keys.firstOrNull { it !in occupiedIndices }
    }

    private fun spawnSeatEntity(seat: Seat) {
        val bukkitWorld = car.world.bukkitWorldData.bukkitWorld
        val seatPos = computeSeatPosition(car.position, car.direction, car.length, seat.seatIndex)
        val (yaw, pitch) = computeYawPitch(car.direction)

        val interaction = bukkitWorld.spawnEntity(
            Location(bukkitWorld, seatPos.x(), seatPos.y(), seatPos.z(), yaw.toFloat(), pitch.toFloat()),
            EntityType.INTERACTION
        ) as Interaction
        interaction.isPersistent = false

        interaction.setInteractionWidth(seatConfig.interaction.width.toFloat())
        interaction.setInteractionHeight(seatConfig.interaction.height.toFloat())

        seatEntities[seat.seatIndex] = interaction
    }

    private fun computeSeatPosition(
        carPos: Vector3dc,
        direction: Vector3dc,
        carLength: Double,
        seatIndex: Int
    ): Vector3d {
        val baseOffset = carLength * seatConfig.baseOffsetRatio + seatIndex * seatConfig.spacing
        return Vector3d(
            carPos.x() + direction.x() * baseOffset,
            carPos.y() + seatConfig.heightOffset,
            carPos.z() + direction.z() * baseOffset
        )
    }

    private fun computeYawPitch(direction: Vector3dc): Pair<Double, Double> {
        val yaw = atan2(-direction.x(), direction.z()) * (180.0 / Math.PI)
        val pitch = asin(direction.y().coerceIn(-1.0, 1.0)) * (180.0 / Math.PI)
        return Pair(yaw, pitch)
    }

    /** 玩家右键座位 Interaction → 坐下 */
    @EventHandler
    fun onPlayerInteractAtSeat(event: PlayerInteractAtEntityEvent) {
        val entity = event.rightClicked
        if (entity !is Interaction) return

        val found = findSeatEntity(entity) ?: return
        val (extension, seatIdx) = found
        if (extension !== this) return
        if (!seatEntities.containsKey(seatIdx)) return

        seatedPlayers[event.player]?.let { oldIdx ->
            if (oldIdx != seatIdx) seatedPlayers.remove(event.player)
        }
        makePlayerSit(event.player, seatIdx)
        event.isCancelled = true
    }

    /** 玩家移动 → 若在座位上且移动距离过大则站起来 */
    @EventHandler
    fun onPlayerMove(event: PlayerMoveEvent) {
        val player = event.player
        val seatIdx = seatedPlayers[player] ?: return
        val from = event.from
        val to = event.to ?: return

        if (from.x == to.x && from.y == to.y && from.z == to.z) return

        val entity = seatEntities[seatIdx] ?: return
        if (!entity.isValid) {
            seatedPlayers.remove(player)
            return
        }

        val seatLoc = entity.location
        if (from.distance(seatLoc) > 0.5) {
            seatedPlayers.remove(player)
        }
    }

    companion object {
        private val allExtensions = mutableListOf<SeatExtension>()

        @JvmStatic
        fun register(extension: SeatExtension) {
            allExtensions.add(extension)
        }

        @JvmStatic
        fun unregister(extension: SeatExtension) {
            allExtensions.remove(extension)
        }

        @JvmStatic
        fun tickAll() {
            allExtensions.forEach { it.tick() }
        }

        private fun findSeatEntity(entity: Interaction): Pair<SeatExtension, Int>? {
            for (ext in allExtensions) {
                for ((idx, e) in ext.seatEntities) {
                    if (e.uniqueId == entity.uniqueId) {
                        return Pair(ext, idx)
                    }
                }
            }
            return null
        }
    }
}
