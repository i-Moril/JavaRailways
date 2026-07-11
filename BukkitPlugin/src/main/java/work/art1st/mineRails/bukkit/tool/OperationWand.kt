package work.art1st.mineRails.bukkit.tool

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.Material
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
import work.art1st.mineRails.basic.WayPoint
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
import java.util.UUID

enum class OperationWandMode(
    override val displayName: String,
    override val description: String
) : WandModeSpec {
    CREATE_STOP_POINT("路径点创建", "右键轨道附近创建/编辑路径点 | Shift+F 切换模式"),
    DELETE("删除模式", "左键路径点选择，再次左键确认删除 | Shift+F 切换模式")
}

data class OperationWandState(
    override var mode: OperationWandMode = OperationWandMode.CREATE_STOP_POINT,
    var pendingDeleteStopPoint: String? = null
) : WandState<OperationWandMode>

class OperationWand(plugin: BukkitPlugin) : AbstractWand<OperationWandState, OperationWandMode>(
    plugin = plugin,
    wandKey = "mineRails_operationWand",
    material = Material.BLAZE_ROD,
    itemDisplayName = Component.text("OperationWand", NamedTextColor.AQUA),
    itemLore = listOf(
        Component.text("右键轨道附近创建路径点", NamedTextColor.GRAY),
        Component.text("Shift+F 切换模式", NamedTextColor.GRAY),
        Component.text("Shift+右键 打开设置", NamedTextColor.GRAY)
    ),
    modes = OperationWandMode.entries.toList()
) {
    /** Per-player transient marker entities: player UUID -> (wayPoint id -> entity UUID). */
    private val markerEntities = mutableMapOf<UUID, MutableMap<String, UUID>>()
    private val editingWayPoint = mutableMapOf<UUID, String>()
    private val heavyOperationGate = PlayerOperationGate()
    private val configUI = ConfigUIRegistry()
    private val dialogRenderer = DialogRenderer(configUI)
    private lateinit var editKeys: WayPointEditKeys

    override fun createDefaultState(): OperationWandState = OperationWandState()

    override fun onBeforeRegisterEvents() {
        registerConfig()
    }

    override fun onAfterRegisterEvents() {
        plugin.logger.info("OperationWand initialized")
    }

    private fun registerConfig() {
        editKeys = WayPointEditKeys()
        configUI.register(ConfigGroup(
            id = "operation.waypoint.edit",
            label = "路径点设置",
            description = "路径点是列车寻路和时刻表动作的基准点，不一定要求列车停车。",
            items = mutableListOf(ConfigItemImpl(editKeys.name))
        ))
    }

    class WayPointEditKeys {
        val name = ConfigKey.build<String>("operation.waypoint.name") {
            label = "路径点名称"
            description = "用于 JS 时刻表和寻路动作引用的唯一名称"
            defaultValue("waypoint")
            valueType = ConfigValueType.STRING
            constraints = ConfigConstraints.StringLength(1, 64)
            group = "operation.waypoint.edit"
            order = 0
        }
    }

    override fun handleWandInteract(player: Player, state: OperationWandState, event: PlayerInteractEvent) {
        when (state.mode) {
            OperationWandMode.CREATE_STOP_POINT -> handleCreateStopPoint(player, event)
            OperationWandMode.DELETE -> handleDeleteStopPoint(player, state, event)
        }
    }

    override fun clearSelection(player: Player, state: OperationWandState) {
        state.pendingDeleteStopPoint?.let { setMarkerMaterial(player, it, Material.LIME_CONCRETE) }
        state.pendingDeleteStopPoint = null
    }

    fun removeMarker(worldId: UUID, wayPointId: String) {
        plugin.server.onlinePlayers
            .filter { it.world.uid == worldId }
            .forEach { removeMarkerForPlayer(it, wayPointId) }
    }

    private fun handleCreateStopPoint(player: Player, event: PlayerInteractEvent) {
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
            operation = "OperationWand.handleCreateStopPoint",
            supplier = {
                val existing = HeavyRailQueries.findNearestWayPoint(wayPointSnapshot, railsById, targetPosition)
                val nearestRail = HeavyRailQueries.findNearestRail(railSnapshot, targetPosition)
                CreateStopPointLookup(existing, nearestRail)
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
                val wayPoint = WayPoint(
                    id = id,
                    displayName = id,
                    railId = rail.id,
                    arcLength = nearestRail.arcLength
                )
                store.add(wayPoint)
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
                    "OperationWand heavy lookup failed for player ${player.name}: ${error::class.simpleName}: ${error.message ?: ""}"
                )
                plugin.server.getPlayer(player.uniqueId)?.sendActionBar(
                    Component.text("路径点查找失败，请重试", NamedTextColor.RED)
                )
            }
        )
    }

    private fun handleDeleteStopPoint(player: Player, state: OperationWandState, event: PlayerInteractEvent) {
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

        if (state.pendingDeleteStopPoint == wayPoint.id) {
            coreWorld.basicWorldData.wayPoints.remove(wayPoint.id)
            removeMarker(player.world.uid, wayPoint.id)
            state.pendingDeleteStopPoint = null
            player.sendMessage(Component.text("已删除路径点 ${wayPoint.id}", NamedTextColor.GREEN))
        } else {
            state.pendingDeleteStopPoint?.let { setMarkerMaterial(player, it, Material.LIME_CONCRETE) }
            state.pendingDeleteStopPoint = wayPoint.id
            setMarkerMaterial(player, wayPoint.id, Material.YELLOW_CONCRETE)
            player.sendActionBar(
                Component.text("再次左键确认删除路径点 ${wayPoint.id}", NamedTextColor.YELLOW)
            )
        }
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

    private fun showWayPointDialog(player: Player, wayPointId: String) {
        val coreWorld = getCoreWorld(player) ?: return
        val wayPoint = coreWorld.basicWorldData.wayPoints[wayPointId] ?: return
        editingWayPoint[player.uniqueId] = wayPointId
        val session = configUI.getPlayerSession(player.uniqueId)
        session.setValue(editKeys.name, wayPoint.id)
        dialogRenderer.showConfigDialog(player, "operation.waypoint.edit") { p ->
            applyWayPointDialog(p)
        }
    }

    private fun applyWayPointDialog(player: Player) {
        val oldId = editingWayPoint.remove(player.uniqueId) ?: return
        val coreWorld = getCoreWorld(player) ?: return
        val rawName = configUI.getPlayerSession(player.uniqueId).getValue(editKeys.name) ?: oldId
        val newId = rawName.trim()
        if (newId.isBlank()) {
            player.sendActionBar(Component.text("路径点名称不能为空", NamedTextColor.RED))
            return
        }
        val updated = try {
            coreWorld.basicWorldData.wayPoints.rename(oldId, newId, newId)
        } catch (e: Exception) {
            player.sendActionBar(Component.text("路径点重命名失败: ${e.message}", NamedTextColor.RED))
            return
        }
        renameMarker(oldId, updated.id, updated.displayName)
        player.sendMessage(Component.text("路径点已保存: ${updated.id}", NamedTextColor.GREEN))
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
    fun onOperationWandHeldChange(event: PlayerItemHeldEvent) {
        scheduleMarkerVisibilityRefresh(event.player)
    }

    @EventHandler
    fun onOperationWandSwapHands(event: PlayerSwapHandItemsEvent) {
        scheduleMarkerVisibilityRefresh(event.player)
    }

    @EventHandler
    fun onOperationWandPlayerJoin(event: PlayerJoinEvent) {
        scheduleMarkerVisibilityRefresh(event.player)
    }

    override fun onPlayerQuit(player: Player, state: OperationWandState) {
        clearMarkersForPlayer(player)
        editingWayPoint.remove(player.uniqueId)
        heavyOperationGate.clear(player.uniqueId)
        configUI.removePlayerSession(player.uniqueId)
    }

    private data class CreateStopPointLookup(
        val existingWayPoint: HeavyRailQueries.WayPointMatch?,
        val nearestRail: HeavyRailQueries.NearestRailMatch?
    )

    companion object {
        private const val MAX_PLACE_DISTANCE = 8.0
        private const val MAX_SELECT_DISTANCE = 6.0
    }
}
