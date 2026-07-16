package work.art1st.mineRails.bukkit.tool

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemStack
import org.joml.Vector3d
import work.art1st.mineRails.basic.DirectionRestrictionResolver
import work.art1st.mineRails.basic.WayPoint
import work.art1st.mineRails.basic.api.destination.DestinationRole
import work.art1st.mineRails.basic.api.destination.DestinationRoleType
import work.art1st.mineRails.basic.api.destination.OperationalDestination
import work.art1st.mineRails.basic.api.destination.OperationalDestinationService
import work.art1st.mineRails.basic.api.station.DoorSide
import work.art1st.mineRails.basic.api.station.StationId
import work.art1st.mineRails.basic.api.ui.config.ConfigConstraints
import work.art1st.mineRails.basic.api.ui.config.ConfigGroup
import work.art1st.mineRails.basic.api.ui.config.ConfigItemImpl
import work.art1st.mineRails.basic.api.ui.config.ConfigKey
import work.art1st.mineRails.basic.api.ui.config.ConfigValueType
import work.art1st.mineRails.basic.impl.config.ConfigUIRegistry
import work.art1st.mineRails.basic.pathFinding.basicWorldData
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.ui.dialog.DialogRenderer
import work.art1st.mineRails.bukkit.util.BukkitAsyncTasks
import work.art1st.mineRails.bukkit.util.HeavyRailQueries
import work.art1st.mineRails.bukkit.util.PlayerOperationGate
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import java.util.UUID

enum class RailObjectWandMode(
    override val displayName: String,
    override val description: String
) : WandModeSpec {
    WAY_POINT("路径点模式", "右键创建/编辑路径点 | 左键选择并删除 | Shift+F 切换模式"),
    DIRECTION_RESTRICTION("方向限制模式", "右键添加方向限制 | 左键删除 | Shift+F 切换模式")
}

data class RailObjectWandState(
    override var mode: RailObjectWandMode = RailObjectWandMode.WAY_POINT,
    var pendingDeleteWayPoint: String? = null
) : WandState<RailObjectWandMode>

@Serializable
internal data class SavedRailObjectWandStateV1(
    val mode: String = RailObjectWandMode.WAY_POINT.name,
    val pendingDeleteWayPoint: String? = null
)

internal object RailObjectWandStateCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(state: RailObjectWandState): String = json.encodeToString(
        SavedRailObjectWandStateV1(state.mode.name, state.pendingDeleteWayPoint)
    )

    fun decode(encoded: String): RailObjectWandState? = runCatching {
        val saved = json.decodeFromString<SavedRailObjectWandStateV1>(encoded)
        RailObjectWandState(
            mode = RailObjectWandMode.entries.firstOrNull { it.name == saved.mode }
                ?: RailObjectWandMode.WAY_POINT,
            pendingDeleteWayPoint = saved.pendingDeleteWayPoint
        )
    }.getOrNull()
}

internal object DirectionRestrictionParticleLayout {
    fun arcLengths(totalLength: Double, phase: Double, maxPoints: Int = 6): List<Double> {
        if (totalLength <= 0.0 || maxPoints <= 0) return emptyList()
        val spacing = if (totalLength <= 8.0) totalLength else maxOf(8.0, totalLength / maxPoints)
        var arc = ((phase % spacing) + spacing) % spacing
        val result = mutableListOf<Double>()
        while (arc <= totalLength && result.size < maxPoints) {
            result += arc
            arc += spacing
        }
        return result
    }
}

class RailObjectWand(plugin: BukkitPlugin) : AbstractWand<RailObjectWandState, RailObjectWandMode>(
    plugin = plugin,
    wandKey = "mineRails_railObjectWand",
    material = Material.BLAZE_ROD,
    itemDisplayName = Component.text("RailObjectWand", NamedTextColor.AQUA),
    itemLore = listOf(
        Component.text("路径点：右键添加，左键删除", NamedTextColor.GRAY),
        Component.text("方向限制：右键添加，左键删除", NamedTextColor.GRAY),
        Component.text("Shift+F 切换模式", NamedTextColor.GRAY),
        Component.text("Shift+右键 打开设置", NamedTextColor.GRAY)
    ),
    modes = RailObjectWandMode.entries.toList()
) {
    /** Per-player transient marker entities: player UUID -> (wayPoint id -> entity UUID). */
    private val markerEntities = mutableMapOf<UUID, MutableMap<String, UUID>>()
    private val editingWayPoint = mutableMapOf<UUID, String>()
    private val heavyOperationGate = PlayerOperationGate()
    private val visibleDirectionRails = mutableMapOf<UUID, MutableSet<UUID>>()
    private var directionParticleFrame: Long = 0L
    private val configUI = ConfigUIRegistry()
    private val dialogRenderer = DialogRenderer(configUI)
    private lateinit var editKeys: WayPointEditKeys

    override fun createDefaultState(): RailObjectWandState = RailObjectWandState()

    override fun encodeState(state: RailObjectWandState): String = RailObjectWandStateCodec.encode(state)

    override fun decodeState(encoded: String): RailObjectWandState? = RailObjectWandStateCodec.decode(encoded)

    override fun onBeforeRegisterEvents() {
        registerConfig()
    }

    override fun onAfterRegisterEvents() {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            directionParticleFrame++
            plugin.server.onlinePlayers.forEach(::renderDirectionRestrictionParticles)
        }, PARTICLE_REFRESH_TICKS, PARTICLE_REFRESH_TICKS)
        plugin.logger.info("RailObjectWand initialized")
    }

    private fun registerConfig() {
        editKeys = WayPointEditKeys()
        configUI.register(ConfigGroup(
            id = "railObject.waypoint.edit",
            label = "路径点设置",
            description = "Destination 是 directed 路由锚点，可选择普通、车站或车辆段角色。",
            items = mutableListOf(
                ConfigItemImpl(editKeys.name),
                ConfigItemImpl(editKeys.role),
                ConfigItemImpl(editKeys.approachDirection),
                ConfigItemImpl(editKeys.stationId),
                ConfigItemImpl(editKeys.berthId),
                ConfigItemImpl(editKeys.doorSide),
                ConfigItemImpl(editKeys.depotId),
                ConfigItemImpl(editKeys.stablingPointId)
            )
        ))
    }

    class WayPointEditKeys {
        val name = ConfigKey.build<String>("railObject.waypoint.name") {
            label = "Destination ID"
            description = "用于 JS 时刻表和寻路动作引用的唯一名称"
            defaultValue("destination")
            valueType = ConfigValueType.STRING
            constraints = ConfigConstraints.StringLength(1, 64)
            group = "railObject.waypoint.edit"
            order = 0
        }
        val role = ConfigKey.build<DestinationRoleType>("railObject.waypoint.role") {
            label = "运营角色"
            description = "普通目的地、车站停车位或车辆段停车点"
            defaultValue(DestinationRoleType.REGULAR)
            valueType = ConfigValueType.ENUM
            constraints = ConfigConstraints.EnumOptions(
                DestinationRoleType.entries,
                DestinationRoleType.entries.associateWith { it.displayName }
            )
            group = "railObject.waypoint.edit"
            order = 1
        }
        val approachDirection = ConfigKey.build<ViewDirection>("railObject.waypoint.direction") {
            label = "接近方向"
            description = "列车到达该 Destination 时的严格 RailView 方向"
            defaultValue(ViewDirection.AB)
            valueType = ConfigValueType.ENUM
            constraints = ConfigConstraints.EnumOptions(
                ViewDirection.entries,
                ViewDirection.entries.associateWith { it.name }
            )
            group = "railObject.waypoint.edit"
            order = 2
        }
        val stationId = stringKey("railObject.waypoint.stationId", "Station ID", 3)
        val berthId = stringKey("railObject.waypoint.berthId", "Berth ID", 4)
        val doorSide = ConfigKey.build<DoorSide>("railObject.waypoint.doorSide") {
            label = "开门侧"
            description = "相对接近方向；Station Berth 不允许 NONE"
            defaultValue(DoorSide.LEFT)
            valueType = ConfigValueType.ENUM
            constraints = ConfigConstraints.EnumOptions(
                DoorSide.entries,
                DoorSide.entries.associateWith { it.name.lowercase() }
            )
            group = "railObject.waypoint.edit"
            order = 5
        }
        val depotId = stringKey("railObject.waypoint.depotId", "Depot ID", 6)
        val stablingPointId = stringKey(
            "railObject.waypoint.stablingPointId",
            "Stabling Point ID",
            7
        )

        companion object {
            private fun stringKey(id: String, text: String, itemOrder: Int) =
                ConfigKey.build<String>(id) {
                    label = text
                    defaultValue("")
                    valueType = ConfigValueType.STRING
                    constraints = ConfigConstraints.StringLength(0, 64)
                    group = "railObject.waypoint.edit"
                    order = itemOrder
                }
        }
    }

    override fun handleWandInteract(player: Player, state: RailObjectWandState, event: PlayerInteractEvent) {
        when (state.mode) {
            RailObjectWandMode.WAY_POINT -> when (event.action) {
                Action.RIGHT_CLICK_BLOCK, Action.RIGHT_CLICK_AIR -> handleCreateWayPoint(player, event)
                Action.LEFT_CLICK_BLOCK, Action.LEFT_CLICK_AIR -> handleDeleteWayPoint(player, state, event)
                else -> Unit
            }
            RailObjectWandMode.DIRECTION_RESTRICTION -> handleDirectionRestriction(player, event)
        }
    }

    override fun clearSelection(player: Player, state: RailObjectWandState) {
        state.pendingDeleteWayPoint?.let { setMarkerMaterial(player, it, Material.LIME_CONCRETE) }
        state.pendingDeleteWayPoint = null
    }

    fun removeMarker(worldId: UUID, wayPointId: String) {
        plugin.server.onlinePlayers
            .filter { it.world.uid == worldId }
            .forEach { removeMarkerForPlayer(it, wayPointId) }
    }

    private fun handleCreateWayPoint(player: Player, event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.RIGHT_CLICK_AIR) {
            player.sendActionBar(Component.text("右键轨道附近创建或编辑路径点", NamedTextColor.YELLOW))
            return
        }

        val coreWorld = getCoreWorld(player) ?: run {
            player.sendActionBar(Component.text("错误: 无法获取 MineRails 世界", NamedTextColor.RED))
            return
        }

        val targetPosition = getTargetPosition(player, event) ?: run {
            player.sendActionBar(Component.text("请右键轨道附近方块", NamedTextColor.RED))
            return
        }

        val pendingOperation = heavyOperationGate.tryStart(player.uniqueId, "路径点查找")
        if (pendingOperation != null) {
            player.sendActionBar(Component.text("正在执行$pendingOperation，请稍候", NamedTextColor.YELLOW))
            return
        }

        val worldId = player.world.uid
        val railSnapshot = coreWorld.allRails.toList()
        val railsById = railSnapshot.associateBy { it.id }
        val wayPointSnapshot = coreWorld.basicWorldData.wayPoints.all()
        player.sendActionBar(Component.text("正在查找最近轨道...", NamedTextColor.GRAY))

        BukkitAsyncTasks.supply(
            plugin = plugin,
            operation = "RailObjectWand.handleCreateWayPoint",
            supplier = {
                val existing = HeavyRailQueries.findNearestWayPoint(wayPointSnapshot, railsById, targetPosition)
                val nearestRail = HeavyRailQueries.findNearestRail(railSnapshot, targetPosition)
                CreateWayPointLookup(existing, nearestRail)
            },
            onSuccess = { result ->
                heavyOperationGate.finish(player.uniqueId)
                val livePlayer = plugin.server.getPlayer(player.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                val liveCoreWorld = getCoreWorld(livePlayer) ?: run {
                    livePlayer.sendActionBar(Component.text("错误: 无法获取 MineRails 世界", NamedTextColor.RED))
                    return@supply
                }

                val existing = result.existingWayPoint
                if (existing != null && existing.distance <= MAX_SELECT_DISTANCE) {
                    showWayPointDialog(livePlayer, existing.wayPoint.id)
                    return@supply
                }

                val nearestRail = result.nearestRail ?: run {
                    livePlayer.sendActionBar(Component.text("世界中没有轨道", NamedTextColor.RED))
                    return@supply
                }
                if (nearestRail.distance > MAX_PLACE_DISTANCE) {
                    livePlayer.sendActionBar(
                        Component.text("离最近轨道太远: ${"%.2f".format(nearestRail.distance)}m", NamedTextColor.RED)
                    )
                    return@supply
                }
                val rail = liveCoreWorld.getRail(nearestRail.railId) ?: run {
                    livePlayer.sendActionBar(Component.text("轨道已变化，请重试", NamedTextColor.RED))
                    return@supply
                }

                val store = liveCoreWorld.basicWorldData.wayPoints
                val id = store.nextId()
                val destination = OperationalDestination(
                    id = id,
                    displayName = id,
                    railId = rail.id,
                    arcLengthInAbMeters = nearestRail.arcLength,
                    approachDirection = ViewDirection.AB,
                    role = DestinationRole.Regular
                )
                try {
                    OperationalDestinationService(liveCoreWorld).register(destination)
                } catch (failure: Exception) {
                    livePlayer.sendActionBar(
                        Component.text(
                            "Destination 创建失败: ${failure.message ?: failure::class.java.simpleName}",
                            NamedTextColor.RED
                        )
                    )
                    return@supply
                }
                val wayPoint = destination.toWayPoint()
                spawnOrReplaceMarker(livePlayer, wayPoint)

                livePlayer.sendMessage(
                    Component.text(
                        "已创建路径点 $id: rail=${rail.id}, s=${"%.2f".format(nearestRail.arcLength)}",
                        NamedTextColor.GREEN
                    )
                )
                showWayPointDialog(livePlayer, id)
            },
            onFailure = { error ->
                heavyOperationGate.finish(player.uniqueId)
                plugin.logger.warning(
                    "RailObjectWand heavy lookup failed for player ${player.name}: ${error::class.simpleName}: ${error.message ?: ""}"
                )
                plugin.server.getPlayer(player.uniqueId)?.sendActionBar(
                    Component.text("路径点查找失败，请重试", NamedTextColor.RED)
                )
            }
        )
    }

    private fun handleDeleteWayPoint(player: Player, state: RailObjectWandState, event: PlayerInteractEvent) {
        if (event.action != Action.LEFT_CLICK_BLOCK && event.action != Action.LEFT_CLICK_AIR) {
            player.sendActionBar(Component.text("左键路径点选择，再次左键确认删除", NamedTextColor.YELLOW))
            return
        }

        val coreWorld = getCoreWorld(player) ?: run {
            player.sendActionBar(Component.text("错误: 无法获取 MineRails 世界", NamedTextColor.RED))
            return
        }

        val targetPosition = getTargetPosition(player, event) ?: run {
            player.sendActionBar(Component.text("请看向路径点附近并左键", NamedTextColor.RED))
            return
        }

        val (wayPoint, distance) = findNearestWayPoint(coreWorld, targetPosition) ?: run {
            player.sendActionBar(Component.text("附近没有路径点", NamedTextColor.RED))
            return
        }
        if (distance > MAX_SELECT_DISTANCE) {
            player.sendActionBar(
                Component.text("离最近路径点太远: ${"%.2f".format(distance)}m", NamedTextColor.RED)
            )
            return
        }

        if (state.pendingDeleteWayPoint == wayPoint.id) {
            if (coreWorld.basicWorldData.destinations[wayPoint.id] != null) {
                OperationalDestinationService(coreWorld).remove(wayPoint.id)
            } else {
                coreWorld.basicWorldData.wayPoints.remove(wayPoint.id)
            }
            removeMarker(player.world.uid, wayPoint.id)
            state.pendingDeleteWayPoint = null
            player.sendMessage(Component.text("已删除路径点 ${wayPoint.id}", NamedTextColor.GREEN))
        } else {
            state.pendingDeleteWayPoint?.let { setMarkerMaterial(player, it, Material.LIME_CONCRETE) }
            state.pendingDeleteWayPoint = wayPoint.id
            setMarkerMaterial(player, wayPoint.id, Material.YELLOW_CONCRETE)
            player.sendActionBar(
                Component.text("再次左键确认删除路径点 ${wayPoint.id}", NamedTextColor.YELLOW)
            )
        }
    }

    private fun handleDirectionRestriction(player: Player, event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.RIGHT_CLICK_AIR &&
            event.action != Action.LEFT_CLICK_BLOCK && event.action != Action.LEFT_CLICK_AIR
        ) return
        val coreWorld = getCoreWorld(player) ?: return
        val targetPosition = getTargetPosition(player, event) ?: run {
            player.sendActionBar(Component.text("请点击轨道附近", NamedTextColor.RED))
            return
        }
        val pendingOperation = heavyOperationGate.tryStart(player.uniqueId, "方向限制查找")
        if (pendingOperation != null) {
            player.sendActionBar(Component.text("正在执行$pendingOperation，请稍候", NamedTextColor.YELLOW))
            return
        }

        val worldId = player.world.uid
        val delete = event.action == Action.LEFT_CLICK_BLOCK || event.action == Action.LEFT_CLICK_AIR
        val playerPosition = Vector3d(player.location.x, player.location.y, player.location.z)
        val rails = coreWorld.allRails.toList()
        BukkitAsyncTasks.supply(
            plugin = plugin,
            operation = "RailObjectWand.handleDirectionRestriction",
            supplier = {
                val pointB = HeavyRailQueries.findNearestRail(rails, targetPosition)
                    ?: return@supply null
                val selectedRail = rails.firstOrNull { it.id == pointB.railId }
                    ?: return@supply null
                val pointA = HeavyRailQueries.findNearestRail(listOf(selectedRail), playerPosition)
                    ?: return@supply null
                DirectionRestrictionLookup(pointA, pointB)
            },
            onSuccess = { lookup ->
                heavyOperationGate.finish(player.uniqueId)
                val livePlayer = plugin.server.getPlayer(player.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                if (lookup == null || lookup.pointB.distance > MAX_PLACE_DISTANCE) {
                    livePlayer.sendActionBar(Component.text("点击位置附近没有轨道", NamedTextColor.RED))
                    return@supply
                }
                val liveWorld = getCoreWorld(livePlayer) ?: return@supply
                val rail = liveWorld.getRail(lookup.pointB.railId) ?: run {
                    livePlayer.sendActionBar(Component.text("轨道已变化，请重试", NamedTextColor.RED))
                    return@supply
                }
                if (delete) {
                    if (rail.metadata.allowedDirection == null) {
                        livePlayer.sendActionBar(Component.text("该轨道没有方向限制", NamedTextColor.GRAY))
                    } else {
                        rail.metadata = rail.metadata.copy(allowedDirection = null)
                        livePlayer.sendMessage(Component.text("已删除轨道 ${rail.id} 的方向限制", NamedTextColor.GREEN))
                    }
                    return@supply
                }

                val allowedDirection = DirectionRestrictionResolver.fromArcPoints(
                    lookup.pointA.arcLength,
                    lookup.pointB.arcLength,
                    MIN_DIRECTION_ARC_DIFFERENCE
                ) ?: run {
                    livePlayer.sendActionBar(Component.text("玩家位置与点击位置太近，无法确定方向", NamedTextColor.RED))
                    return@supply
                }
                rail.metadata = rail.metadata.copy(allowedDirection = allowedDirection)
                livePlayer.sendMessage(Component.text(
                    "已限制轨道 ${rail.id} 只允许 ${allowedDirection.name} 方向行驶",
                    NamedTextColor.GREEN
                ))
            },
            onFailure = { error ->
                heavyOperationGate.finish(player.uniqueId)
                plugin.logger.warning("RailObjectWand direction lookup failed: ${error.message ?: error::class.simpleName}")
                plugin.server.getPlayer(player.uniqueId)?.sendActionBar(
                    Component.text("方向限制操作失败", NamedTextColor.RED)
                )
            }
        )
    }

    private fun findNearestWayPoint(
        coreWorld: work.art1st.mineRails.core.physics.CoreWorld,
        targetPosition: Vector3d
    ): Pair<WayPoint, Double>? {
        return coreWorld.basicWorldData.wayPoints.all()
            .mapNotNull { wayPoint ->
                val rail = coreWorld.getRail(wayPoint.railId) ?: return@mapNotNull null
                val point = rail.curve.getPointAt(wayPoint.arcLength)
                wayPoint to point.position.distance(targetPosition)
            }
            .minByOrNull { it.second }
    }

    private fun getTargetPosition(player: Player, event: PlayerInteractEvent): Vector3d? {
        val block = event.clickedBlock ?: player.getTargetBlockExact(32) ?: return null
        return Vector3d(block.x + 0.5, block.y + 0.5, block.z + 0.5)
    }

    private fun renderDirectionRestrictionParticles(player: Player) {
        if ((!isWandItem(player.inventory.itemInMainHand) && !isWandItem(player.inventory.itemInOffHand)) ||
            getState(player).mode != RailObjectWandMode.DIRECTION_RESTRICTION
        ) {
            visibleDirectionRails.remove(player.uniqueId)
            return
        }
        val coreWorld = getCoreWorld(player) ?: return
        val playerPosition = Vector3d(player.location.x, player.location.y, player.location.z)
        val previousVisible = visibleDirectionRails.getOrPut(player.uniqueId) { mutableSetOf() }
        val candidates = coreWorld.allRails.mapNotNull { rail ->
            val allowedDirection = rail.metadata.allowedDirection ?: return@mapNotNull null
            val distanceSquared = approximateDistanceSquared(rail.curve, playerPosition)
            val radius = if (rail.id in previousVisible) PARTICLE_EXIT_RADIUS else PARTICLE_ENTER_RADIUS
            if (distanceSquared > radius * radius) return@mapNotNull null
            Triple(rail, allowedDirection, distanceSquared)
        }.sortedBy { it.third }.take(MAX_VISIBLE_DIRECTION_RAILS)

        previousVisible.clear()
        previousVisible.addAll(candidates.map { it.first.id })
        var remainingParticles = MAX_PARTICLES_PER_REFRESH
        val flowPhase = directionParticleFrame * FLOW_METERS_PER_REFRESH
        for ((rail, allowedDirection, _) in candidates) {
            if (remainingParticles <= 0) break
            val view = rail.getView(allowedDirection)
            for (arc in DirectionRestrictionParticleLayout.arcLengths(view.curve.totalArcLength, flowPhase)) {
                if (remainingParticles <= 0) break
                val point = view.curve.getPointAt(arc)
                val position = Vector3d(point.position).fma(0.35, point.normal)
                spawnDust(player, position, ALLOWED_DIRECTION_COLOR, 0.9f)
                remainingParticles--
            }
            if (remainingParticles > 0) {
                val end = view.endPoint
                spawnDust(player, Vector3d(end.position).fma(0.4, end.normal), FORBIDDEN_ENTRY_COLOR, 1.3f)
                remainingParticles--
            }
            if (remainingParticles > 0) {
                val end = view.endPoint
                spawnDust(player, Vector3d(end.position).fma(0.9, end.normal), FORBIDDEN_ENTRY_COLOR, 1.0f)
                remainingParticles--
            }
        }
    }

    private fun approximateDistanceSquared(
        curve: work.art1st.mineRails.core.api.physics.curve.PrimeCurve,
        position: Vector3d
    ): Double = DISTANCE_PROBES.minOf { ratio ->
        curve.getPointAt(curve.totalArcLength * ratio).position.distanceSquared(position)
    }

    private fun spawnDust(player: Player, position: Vector3d, color: Color, size: Float) {
        player.spawnParticle(
            Particle.DUST,
            Location(player.world, position.x(), position.y(), position.z()),
            1,
            0.0,
            0.0,
            0.0,
            0.0,
            Particle.DustOptions(color, size)
        )
    }

    private fun showWayPointDialog(player: Player, wayPointId: String) {
        val coreWorld = getCoreWorld(player) ?: return
        val wayPoint = coreWorld.basicWorldData.wayPoints[wayPointId] ?: return
        editingWayPoint[player.uniqueId] = wayPointId
        val destination = coreWorld.basicWorldData.destinations[wayPointId]
        val role = destination?.role ?: DestinationRole.Regular
        val session = configUI.getPlayerSession(player.uniqueId)
        session.setValue(editKeys.name, wayPoint.id)
        session.setValue(editKeys.role, role.type)
        session.setValue(
            editKeys.approachDirection,
            destination?.approachDirection ?: ViewDirection.AB
        )
        val stationRole = role as? DestinationRole.StationBerthRole
        session.setValue(editKeys.stationId, stationRole?.stationId?.value.orEmpty())
        session.setValue(editKeys.berthId, stationRole?.berthId.orEmpty())
        session.setValue(editKeys.doorSide, stationRole?.doorSide ?: DoorSide.LEFT)
        val depotRole = role as? DestinationRole.DepotStablingPointRole
        session.setValue(editKeys.depotId, depotRole?.depotId.orEmpty())
        session.setValue(editKeys.stablingPointId, depotRole?.stablingPointId.orEmpty())
        dialogRenderer.showConfigDialog(player, "railObject.waypoint.edit") { p ->
            applyWayPointDialog(p)
        }
    }

    private fun applyWayPointDialog(player: Player) {
        val oldId = editingWayPoint.remove(player.uniqueId) ?: return
        val coreWorld = getCoreWorld(player) ?: return
        val session = configUI.getPlayerSession(player.uniqueId)
        val rawName = session.getValue(editKeys.name) ?: oldId
        val newId = rawName.trim()
        if (newId.isBlank()) {
            player.sendActionBar(Component.text("Destination ID 不能为空", NamedTextColor.RED))
            return
        }
        val legacyWayPoint = coreWorld.basicWorldData.wayPoints[oldId] ?: return
        val roleType = session.getValue(editKeys.role) ?: DestinationRoleType.REGULAR
        val direction = session.getValue(editKeys.approachDirection) ?: ViewDirection.AB
        val role = try {
            when (roleType) {
                DestinationRoleType.REGULAR -> DestinationRole.Regular
                DestinationRoleType.STATION_BERTH -> DestinationRole.StationBerthRole(
                    stationId = StationId(session.getValue(editKeys.stationId).orEmpty().trim()),
                    berthId = session.getValue(editKeys.berthId).orEmpty().trim(),
                    doorSide = session.getValue(editKeys.doorSide) ?: DoorSide.LEFT
                )
                DestinationRoleType.DEPOT_STABLING_POINT ->
                    DestinationRole.DepotStablingPointRole(
                        depotId = session.getValue(editKeys.depotId).orEmpty().trim(),
                        stablingPointId = session.getValue(editKeys.stablingPointId).orEmpty().trim()
                    )
            }
        } catch (failure: Exception) {
            player.sendActionBar(
                Component.text("Destination 配置无效: ${failure.message}", NamedTextColor.RED)
            )
            return
        }
        val updated = OperationalDestination(
            id = newId,
            displayName = newId,
            railId = legacyWayPoint.railId,
            arcLengthInAbMeters = legacyWayPoint.arcLength,
            approachDirection = direction,
            role = role
        )
        try {
            val destinationService = OperationalDestinationService(coreWorld)
            if (coreWorld.basicWorldData.destinations[oldId] != null) {
                destinationService.replace(oldId, updated)
            } else {
                coreWorld.basicWorldData.wayPoints.remove(oldId)
                try {
                    destinationService.register(updated)
                } catch (failure: Exception) {
                    coreWorld.basicWorldData.wayPoints.add(legacyWayPoint)
                    throw failure
                }
            }
        } catch (failure: Exception) {
            player.sendActionBar(
                Component.text("Destination 保存失败: ${failure.message}", NamedTextColor.RED)
            )
            return
        }
        renameMarker(oldId, updated.id, updated.displayName)
        player.sendMessage(
            Component.text(
                "Destination 已保存: ${updated.id} role=${updated.role.type}",
                NamedTextColor.GREEN
            )
        )
    }

    private fun spawnOrReplaceMarker(player: Player, wayPoint: WayPoint) {
        removeMarkerForPlayer(player, wayPoint.id)
        val coreWorld = getCoreWorld(player) ?: return
        val rail = coreWorld.getRail(wayPoint.railId) ?: return
        val position = rail.curve.getPointAt(wayPoint.arcLength).position
        val location = Location(player.world, position.x(), position.y() + 1.0, position.z())
        val entity = player.world.spawn(location, ItemDisplay::class.java)
        entity.setItemStack(ItemStack(Material.LIME_CONCRETE))
        entity.isPersistent = false
        entity.isVisibleByDefault = false
        entity.isGlowing = true
        entity.customName(Component.text(wayPoint.id, NamedTextColor.GREEN))
        entity.isCustomNameVisible = true
        markerEntities.getOrPut(player.uniqueId) { mutableMapOf() }[wayPoint.id] = entity.uniqueId
        player.showEntity(plugin, entity)
    }

    private fun removeMarkerForPlayer(player: Player, wayPointId: String) {
        val entityId = markerEntities[player.uniqueId]?.remove(wayPointId) ?: return
        player.world.getEntity(entityId)?.remove()
    }

    private fun clearMarkersForPlayer(player: Player) {
        val markers = markerEntities.remove(player.uniqueId) ?: return
        markers.values.forEach { entityId -> player.world.getEntity(entityId)?.remove() }
    }

    private fun renameMarker(oldId: String, newId: String, displayName: String) {
        markerEntities.forEach { (playerId, playerMarkers) ->
            val entityId = playerMarkers.remove(oldId) ?: return@forEach
            playerMarkers[newId] = entityId
            val player = plugin.server.getPlayer(playerId) ?: return@forEach
            val entity = player.world.getEntity(entityId) as? ItemDisplay ?: return@forEach
            entity.customName(Component.text(displayName, NamedTextColor.GREEN))
        }
    }

    private fun setMarkerMaterial(player: Player, wayPointId: String, material: Material) {
        val entityId = markerEntities[player.uniqueId]?.get(wayPointId) ?: return
        val entity = player.world.getEntity(entityId) as? ItemDisplay ?: return
        entity.setItemStack(ItemStack(material))
    }

    private fun refreshMarkersForPlayer(player: Player) {
        clearMarkersForPlayer(player)
        if (!isWandItem(player.inventory.itemInMainHand) && !isWandItem(player.inventory.itemInOffHand)) return
        val coreWorld = getCoreWorld(player) ?: return
        coreWorld.basicWorldData.wayPoints.all().forEach { spawnOrReplaceMarker(player, it) }
    }

    private fun scheduleMarkerVisibilityRefresh(player: Player) {
        plugin.server.scheduler.runTask(plugin, Runnable { refreshMarkersForPlayer(player) })
    }

    @EventHandler
    fun onRailObjectWandHeldChange(event: PlayerItemHeldEvent) {
        scheduleMarkerVisibilityRefresh(event.player)
    }

    @EventHandler
    fun onRailObjectWandSwapHands(event: PlayerSwapHandItemsEvent) {
        scheduleMarkerVisibilityRefresh(event.player)
    }

    @EventHandler
    fun onRailObjectWandPlayerJoin(event: PlayerJoinEvent) {
        scheduleMarkerVisibilityRefresh(event.player)
    }

    override fun onPlayerQuit(player: Player, state: RailObjectWandState) {
        clearMarkersForPlayer(player)
        editingWayPoint.remove(player.uniqueId)
        heavyOperationGate.clear(player.uniqueId)
        visibleDirectionRails.remove(player.uniqueId)
        configUI.removePlayerSession(player.uniqueId)
    }

    private data class DirectionRestrictionLookup(
        val pointA: HeavyRailQueries.NearestRailMatch,
        val pointB: HeavyRailQueries.NearestRailMatch
    )

    private data class CreateWayPointLookup(
        val existingWayPoint: HeavyRailQueries.WayPointMatch?,
        val nearestRail: HeavyRailQueries.NearestRailMatch?
    )

    companion object {
        private const val MAX_PLACE_DISTANCE = 8.0
        private const val MAX_SELECT_DISTANCE = 6.0
        private const val MIN_DIRECTION_ARC_DIFFERENCE = 0.25
        private const val PARTICLE_REFRESH_TICKS = 4L
        private const val PARTICLE_ENTER_RADIUS = 64.0
        private const val PARTICLE_EXIT_RADIUS = 72.0
        private const val MAX_VISIBLE_DIRECTION_RAILS = 16
        private const val MAX_PARTICLES_PER_REFRESH = 128
        private const val FLOW_METERS_PER_REFRESH = 1.25
        private val DISTANCE_PROBES = doubleArrayOf(0.0, 0.25, 0.5, 0.75, 1.0)
        private val ALLOWED_DIRECTION_COLOR = Color.fromRGB(64, 255, 96)
        private val FORBIDDEN_ENTRY_COLOR = Color.fromRGB(255, 48, 48)
    }
}
