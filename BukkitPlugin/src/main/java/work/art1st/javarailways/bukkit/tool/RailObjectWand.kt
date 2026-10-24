package work.art1st.javarailways.bukkit.tool

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.event.EventHandler
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.inventory.ItemStack
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.basic.rail.DirectionRestrictionResolver
import work.art1st.javarailways.simpleato.api.destination.DestinationRoleType
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.rail.infrastructureMetadata as metadata
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.api.editor.EditorMarkerGlyph
import work.art1st.javarailways.bukkit.api.editor.EditorMarkerLabels
import work.art1st.javarailways.bukkit.api.editor.EditorTextHandle
import work.art1st.javarailways.bukkit.api.editor.EditorTextLabel
import work.art1st.javarailways.bukkit.ui.dialog.DialogRenderer
import work.art1st.javarailways.bukkit.util.BukkitAsyncTasks
import work.art1st.javarailways.bukkit.util.HeavyRailQueries
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge
import work.art1st.javarailways.bukkit.util.PlayerOperationGate
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class RailObjectWandMode(
    override val displayName: String,
    override val description: String
) : WandModeSpec {
    WAY_POINT("路径点模式", "右键创建/编辑路径点 | 左键选择并删除 | F 切换模式"),
    DIRECTION_RESTRICTION("方向限制模式", "右键添加方向限制 | 左键删除 | F 切换模式")
}

data class RailObjectWandState(
    override var mode: RailObjectWandMode = RailObjectWandMode.WAY_POINT,
    var pendingDeleteWayPoint: String? = null,
    var defaultWayPointType: DestinationRoleType = DestinationRoleType.REGULAR
) : WandState<RailObjectWandMode>

@Serializable
internal data class SavedRailObjectWandStateV1(
    val mode: String,
    val pendingDeleteWayPoint: String?,
    val defaultWayPointType: String
)

internal object RailObjectWandStateCodec {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    fun encode(state: RailObjectWandState): String = json.encodeToString(
        SavedRailObjectWandStateV1(
            state.mode.name,
            state.pendingDeleteWayPoint,
            state.defaultWayPointType.name
        )
    )

    fun decode(encoded: String): RailObjectWandState? = runCatching {
        val saved = json.decodeFromString<SavedRailObjectWandStateV1>(encoded)
        RailObjectWandState(
            mode = enumValueOf<RailObjectWandMode>(saved.mode),
            pendingDeleteWayPoint = saved.pendingDeleteWayPoint,
            defaultWayPointType = enumValueOf<DestinationRoleType>(saved.defaultWayPointType)
        )
    }.getOrNull()
}

internal fun isDirectionRestrictionInteraction(action: Action): Boolean =
    action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR ||
        action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR

internal data class DirectionRestrictionTarget(
    val pointA: HeavyRailQueries.NearestRailMatch,
    val pointB: HeavyRailQueries.LookedAtRailMatch
)

internal fun findDirectionRestrictionTarget(
    candidates: Collection<CoreRail>,
    rayOrigin: Vector3dc,
    rayDirection: Vector3dc,
    playerPosition: Vector3dc,
    maxDistance: Double,
    hitRadius: Double
): DirectionRestrictionTarget? {
    val pointB = HeavyRailQueries.findLookedAtRailPoint(
        candidates,
        rayOrigin,
        rayDirection,
        maxDistance,
        hitRadius
    ) ?: return null
    val selectedRail = candidates.firstOrNull { it.id == pointB.railId } ?: return null
    val pointA = HeavyRailQueries.findNearestRail(listOf(selectedRail), playerPosition) ?: return null
    return DirectionRestrictionTarget(pointA, pointB)
}

internal data class RailObjectDirectionSource(
    val rail: CoreRail,
    val direction: ViewDirection,
    val destinationId: String? = null,
    val destinationArcInAb: Double? = null
)

/** One prepared scene per viewer; movement only filters geometry already sampled on a worker. */
internal class RailObjectDirectionDrawing {
    private var sources: List<RailObjectDirectionSource>? = null
    private var prepared = emptyList<PreparedDirection>()

    fun prepare(input: List<RailObjectDirectionSource>, position: Vector3dc): List<EditorTextLabel> {
        if (sources != input) {
            prepared = input.map(::prepareDirection)
            sources = input.toList()
        }
        return prepared.filter { candidate ->
            candidate.probes.any { it.distanceSquared(position) <= 64.0 * 64.0 }
        }.flatMap(PreparedDirection::labels)
    }

    private fun prepareDirection(source: RailObjectDirectionSource): PreparedDirection {
        val view = source.rail.getView(source.direction)
        val destinationArc = source.destinationArcInAb?.let {
            if (source.direction == ViewDirection.AB) it else view.curve.totalArcLength - it
        }
        val endArc = destinationArc ?: view.curve.totalArcLength
        val startArc = if (destinationArc == null) 0.0 else (endArc - 24.0).coerceAtLeast(0.0)
        val span = endArc - startArc
        val spacing = if (destinationArc == null) 8.0 else 4.0
        val count = kotlin.math.ceil(span / spacing).toInt().coerceIn(1, 6)
        val prefix = source.destinationId?.let { "approach:$it" }
            ?: "direction:${source.rail.id}"
        val color = if (destinationArc == null) NamedTextColor.GREEN else NamedTextColor.AQUA
        val labels = (0 until count).map { index ->
            val point = view.curve.getPointAt(startArc + span * (index + 0.5) / count)
            EditorMarkerLabels.at(
                "$prefix:arrow:$index", EditorMarkerGlyph.DIRECTION,
                point.position, point.tangent, point.normal, 0.7, 1.5, color
            )
        }.toMutableList()
        val end = view.curve.getPointAt(endArc)
        labels += EditorMarkerLabels.at(
            "$prefix:end", EditorMarkerGlyph.BOUNDARY,
            end.position, end.tangent, end.normal, 1.6, 0.25,
            if (destinationArc == null) NamedTextColor.RED else NamedTextColor.AQUA
        )
        val probes = if (destinationArc != null) listOf(Vector3d(end.position)) else
            listOf(0.0, 0.25, 0.5, 0.75, 1.0).map { ratio ->
                Vector3d(view.curve.getPointAt(view.curve.totalArcLength * ratio).position)
            }
        return PreparedDirection(probes, labels)
    }

    private data class PreparedDirection(val probes: List<Vector3d>, val labels: List<EditorTextLabel>)
}

class RailObjectWand(plugin: BukkitPlugin) : AbstractWand<RailObjectWandState, RailObjectWandMode>(
    plugin = plugin,
    wandKey = "javarailways_rail_object_wand",
    material = Material.BLAZE_ROD,
    itemDisplayName = Component.text("RailObjectWand", NamedTextColor.AQUA),
    itemLore = listOf(
        Component.text("路径点：右键添加，左键删除", NamedTextColor.GRAY),
        Component.text("方向限制：右键添加，左键删除", NamedTextColor.GRAY),
        Component.text("F 切换模式", NamedTextColor.GRAY),
        Component.text("Shift+右键 打开设置", NamedTextColor.GRAY)
    ),
    modes = RailObjectWandMode.entries.toList()
) {
    /** Per-player transient marker entities: player UUID -> (wayPoint id -> entity UUID). */
    private val markerEntities =
        ConcurrentHashMap<UUID, ConcurrentHashMap<String, MarkerHandle>>()
    internal class DestinationEdit(
        val world: World,
        val coreWorld: CoreWorld,
        val wayPoint: WayPoint,
        val destination: work.art1st.javarailways.simpleato.api.destination.OperationalDestination?,
        val pending: Boolean,
        val configSession: work.art1st.javarailways.bukkit.api.wand.config.PlayerConfigSession
    ) {
        fun isCurrent(
            activeEdit: DestinationEdit?,
            currentWorld: World,
            currentCoreWorld: CoreWorld?,
            currentSession: work.art1st.javarailways.bukkit.api.wand.config.PlayerConfigSession,
            currentWayPoint: WayPoint?,
            currentDestination: work.art1st.javarailways.simpleato.api.destination.OperationalDestination?
        ): Boolean = activeEdit === this && currentWorld === world && currentCoreWorld === coreWorld &&
            currentSession === configSession && currentWayPoint === wayPoint && currentDestination === destination
    }
    private val editingWayPoint = mutableMapOf<UUID, DestinationEdit>()
    /** Unsaved clicks stay private to the dialog and are never registered or rendered. */
    private val pendingWayPoints = mutableMapOf<UUID, WayPoint>()
    private val heavyOperationGate = PlayerOperationGate()
    private val directionOverlays = ConcurrentHashMap<UUID, DirectionOverlaySession>()
    private val markerViewers = ConcurrentHashMap.newKeySet<UUID>()
    private val destinationEditor = RailObjectDestinationEditor()
    private val dialogRenderer = DialogRenderer(destinationEditor.configUI)

    override fun createDefaultState(): RailObjectWandState = RailObjectWandState()

    override fun encodeState(state: RailObjectWandState): String = RailObjectWandStateCodec.encode(state)

    override fun decodeState(encoded: String): RailObjectWandState? = RailObjectWandStateCodec.decode(encoded)

    override fun onAfterRegisterEvents() {
        plugin.platformDispatcher.runGlobalAtFixedRate(DIRECTION_REFRESH_TICKS, DIRECTION_REFRESH_TICKS) {
            markerViewers.forEach { playerId ->
                val player = plugin.server.getPlayer(playerId)
                if (player == null) {
                    markerViewers.remove(playerId)
                    closeDirectionOverlay(playerId)
                } else {
                    plugin.platformDispatcher.runForEntity(player, action = {
                        BukkitThreadGuardBridge.runInPlayerWorldContext(player) runInSimulationContext@ {
                            refreshDirectionOverlay(player)
                        }
                    })
                }
            }
        }
        plugin.logger.info("RailObjectWand initialized")
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
        editingWayPoint.remove(player.uniqueId)
        pendingWayPoints.remove(player.uniqueId)
        closeDirectionOverlay(player.uniqueId)
        state.pendingDeleteWayPoint?.let { setMarkerMaterial(player, it, Material.LIME_CONCRETE) }
        state.pendingDeleteWayPoint = null
    }

    override fun showSettings(player: Player, state: RailObjectWandState) {
        if (state.mode != RailObjectWandMode.WAY_POINT) {
            player.sendActionBar(Component.text("该模式没有额外设置", NamedTextColor.GRAY))
            return
        }
        val entries = DestinationRoleType.entries.map { type ->
            DialogRenderer.MenuEntry(RailObjectWandI18n.format(player, roleTranslationKey(type))) { selectedPlayer ->
                val selectedState = getState(selectedPlayer)
                selectedState.defaultWayPointType = type
                persistState(selectedPlayer, selectedState)
                selectedPlayer.sendActionBar(
                    RailObjectWandI18n.component(
                        selectedPlayer,
                        "wand.settings.selected",
                        RailObjectWandI18n.format(selectedPlayer, roleTranslationKey(type))
                    ).color(NamedTextColor.GREEN)
                )
            }
        }
        dialogRenderer.showMenu(
            player,
            RailObjectWandI18n.format(player, "wand.settings.title"),
            RailObjectWandI18n.format(player, "wand.settings.description"),
            entries
        )
    }

    private fun roleTranslationKey(type: DestinationRoleType): String = when (type) {
        DestinationRoleType.REGULAR -> "waypoint.type.regular"
        DestinationRoleType.STATION_BERTH -> "waypoint.type.station"
        DestinationRoleType.DEPOT_STABLING_POINT -> "waypoint.type.depot"
    }

    fun removeMarker(worldId: UUID, wayPointId: String) {
        markerViewers.forEach { playerId ->
            val player = plugin.server.getPlayer(playerId)
            if (player == null) {
                markerViewers.remove(playerId)
            } else {
                plugin.platformDispatcher.runForEntity(player, action = {
                    if (player.world.uid == worldId) removeMarkerForPlayer(player, wayPointId)
                })
            }
        }
    }

    private fun handleCreateWayPoint(player: Player, event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.RIGHT_CLICK_AIR) {
            player.sendActionBar(Component.text("右键轨道附近创建或编辑路径点", NamedTextColor.YELLOW))
            return
        }

        val coreWorld = getCoreWorld(player) ?: run {
            player.sendActionBar(Component.text("错误: 无法获取 JavaRailways 世界", NamedTextColor.RED))
            return
        }

        val eye = player.eyeLocation
        val rayOrigin = Vector3d(eye.x, eye.y, eye.z)
        val rayDirection = Vector3d(eye.direction.x, eye.direction.y, eye.direction.z)

        val pendingOperation = heavyOperationGate.tryStart(player.uniqueId, "路径点查找")
        if (pendingOperation != null) {
            player.sendActionBar(Component.text("正在执行$pendingOperation，请稍候", NamedTextColor.YELLOW))
            return
        }

        val worldId = player.world.uid
        val railSnapshot = coreWorld.getRailsAlongRay(
            rayOrigin,
            rayDirection,
            HeavyRailQueries.DEFAULT_TARGET_MAX_DISTANCE,
            HeavyRailQueries.DEFAULT_TARGET_HIT_RADIUS
        ).toList()
        val railsById = railSnapshot.associateBy { it.id }
        val wayPointSnapshot = coreWorld.basicWorldData.wayPoints.all()
        player.sendActionBar(Component.text("正在查找指向的轨道点...", NamedTextColor.GRAY))

        BukkitAsyncTasks.supply(
            plugin = plugin,
            completionEntity = player,
            operation = "RailObjectWand.handleCreateWayPoint",
            supplier = {
                val pointedRail = HeavyRailQueries.findLookedAtRailPoint(
                    railSnapshot,
                    rayOrigin,
                    rayDirection,
                    HeavyRailQueries.DEFAULT_TARGET_MAX_DISTANCE,
                    HeavyRailQueries.DEFAULT_TARGET_HIT_RADIUS
                )
                val existing = pointedRail?.let { target ->
                    HeavyRailQueries.findNearestWayPoint(
                        wayPointSnapshot,
                        railsById,
                        target.position,
                        requiredRailId = target.railId
                    )
                }
                CreateWayPointLookup(pointedRail, existing)
            },
            onSuccess = { result ->
                heavyOperationGate.finish(player.uniqueId)
                val livePlayer = plugin.server.getPlayer(player.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                val liveCoreWorld = getCoreWorld(livePlayer) ?: run {
                    livePlayer.sendActionBar(Component.text("错误: 无法获取 JavaRailways 世界", NamedTextColor.RED))
                    return@supply
                }

                val pointedRail = result.pointedRail ?: run {
                    livePlayer.sendActionBar(Component.text("尚未指向可创建路径点的轨道", NamedTextColor.RED))
                    return@supply
                }
                val existing = result.existingWayPoint
                if (existing != null && existing.distance <= MAX_SELECT_DISTANCE) {
                    showWayPointDialog(livePlayer, existing.wayPoint.id)
                    return@supply
                }

                val rail = liveCoreWorld.getRail(pointedRail.railId) ?: run {
                    livePlayer.sendActionBar(Component.text("轨道已变化，请重试", NamedTextColor.RED))
                    return@supply
                }

                val pendingId = "pending:${livePlayer.uniqueId}:${System.nanoTime()}"
                val wayPoint = WayPoint(
                    id = pendingId,
                    displayName = "",
                    railId = rail.id,
                    arcLength = pointedRail.arcLength
                )
                pendingWayPoints[livePlayer.uniqueId] = wayPoint
                showWayPointDialog(livePlayer, pendingId, getState(livePlayer).defaultWayPointType)
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
            player.sendActionBar(Component.text("错误: 无法获取 JavaRailways 世界", NamedTextColor.RED))
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
            if (!mutateRailScoped(player, coreWorld, setOf(wayPoint.railId)) {
                    destinationEditor.remove(coreWorld, wayPoint)
                }
            ) {
                return
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
        if (!isDirectionRestrictionInteraction(event.action)) return
        val coreWorld = getCoreWorld(player) ?: return
        val pendingOperation = heavyOperationGate.tryStart(player.uniqueId, "方向限制查找")
        if (pendingOperation != null) {
            player.sendActionBar(Component.text("正在执行$pendingOperation，请稍候", NamedTextColor.YELLOW))
            return
        }

        val worldId = player.world.uid
        val delete = event.action == Action.LEFT_CLICK_BLOCK || event.action == Action.LEFT_CLICK_AIR
        val eye = player.eyeLocation
        val rayOrigin = Vector3d(eye.x, eye.y, eye.z)
        val rayDirection = Vector3d(eye.direction.x, eye.direction.y, eye.direction.z)
        val playerPosition = Vector3d(player.location.x, player.location.y, player.location.z)
        val rails = coreWorld.getRailsAlongRay(
            rayOrigin,
            rayDirection,
            HeavyRailQueries.DEFAULT_TARGET_MAX_DISTANCE,
            HeavyRailQueries.DEFAULT_TARGET_HIT_RADIUS
        ).toList()
        BukkitAsyncTasks.supply(
            plugin = plugin,
            completionEntity = player,
            operation = "RailObjectWand.handleDirectionRestriction",
            supplier = {
                findDirectionRestrictionTarget(
                    candidates = rails,
                    rayOrigin = rayOrigin,
                    rayDirection = rayDirection,
                    playerPosition = playerPosition,
                    maxDistance = HeavyRailQueries.DEFAULT_TARGET_MAX_DISTANCE,
                    hitRadius = HeavyRailQueries.DEFAULT_TARGET_HIT_RADIUS
                )
            },
            onSuccess = { lookup ->
                heavyOperationGate.finish(player.uniqueId)
                val livePlayer = plugin.server.getPlayer(player.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                if (lookup == null) {
                    livePlayer.sendActionBar(Component.text("没有指向轨道", NamedTextColor.RED))
                    return@supply
                }
                val liveWorld = getCoreWorld(livePlayer) ?: return@supply
                val rail = liveWorld.getRail(lookup.pointB.railId) ?: run {
                    livePlayer.sendActionBar(Component.text("轨道已变化，请重试", NamedTextColor.RED))
                    return@supply
                }
                val directions = liveWorld.basicWorldData.operatingDirections
                if (delete) {
                    if (directions[rail.id] == null) {
                        livePlayer.sendActionBar(Component.text("该轨道没有方向限制", NamedTextColor.GRAY))
                    } else {
                        if (mutateRailScoped(livePlayer, liveWorld, setOf(rail.id)) {
                                directions.put(rail.id, null)
                            }
                        ) {
                            livePlayer.sendMessage(Component.text(
                                "已删除该轨道的方向限制",
                                NamedTextColor.GREEN
                            ))
                        }
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
                if (mutateRailScoped(livePlayer, liveWorld, setOf(rail.id)) {
                        directions.put(rail.id, allowedDirection)
                    }
                ) {
                    livePlayer.sendMessage(Component.text(
                        "已限制该轨道沿箭头方向行驶",
                        NamedTextColor.GREEN
                    ))
                }
            },
            onFailure = { error ->
                heavyOperationGate.finish(player.uniqueId)
                plugin.logger.log(java.util.logging.Level.WARNING, "RailObjectWand direction lookup failed", error)
                plugin.server.getPlayer(player.uniqueId)?.sendActionBar(
                    Component.text("方向限制操作失败", NamedTextColor.RED)
                )
            }
        )
    }

    private fun findNearestWayPoint(
        coreWorld: work.art1st.javarailways.core.physics.CoreWorld,
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

    private fun refreshDirectionOverlay(player: Player) {
        val playerId = player.uniqueId
        if (!isWandItem(player.inventory.itemInMainHand) && !isWandItem(player.inventory.itemInOffHand)) {
            markerViewers.remove(playerId)
            closeDirectionOverlay(playerId)
            return
        }
        val coreWorld = getCoreWorld(player) ?: run {
            closeDirectionOverlay(playerId)
            return
        }
        val mode = getState(player).mode
        var session = directionOverlays[playerId]
        if (session == null || !session.matches(player.world, mode)) {
            closeDirectionOverlay(playerId)
            session = DirectionOverlaySession(
                player.world, mode, plugin.editorTextService.create(plugin, player, "rail-object-directions")
            )
            directionOverlays[playerId] = session
        }
        if (session.busy) return
        session.busy = true
        val capturedSession = session
        val location = player.location
        val position = Vector3d(location.x, location.y, location.z)
        BukkitAsyncTasks.supply(
            plugin, player, "RailObjectWand.directionOverlay",
            supplier = {
                val sources = when (mode) {
                    RailObjectWandMode.DIRECTION_RESTRICTION ->
                        coreWorld.basicWorldData.operatingDirections.entries().mapNotNull { (railId, direction) ->
                            coreWorld.getRail(railId)?.let { RailObjectDirectionSource(it, direction) }
                        }
                    RailObjectWandMode.WAY_POINT ->
                        coreWorld.simpleAtoWorldData.destinations.all().mapNotNull { destination ->
                            val direction = destination.approachDirection ?: return@mapNotNull null
                            val rail = coreWorld.getRail(destination.railId) ?: return@mapNotNull null
                            RailObjectDirectionSource(
                                rail, direction, destination.id, destination.arcLengthInAbMeters
                            )
                        }
                }
                capturedSession.drawing.prepare(sources, position)
            },
            onSuccess = { labels ->
                capturedSession.busy = false
                if (directionOverlays[playerId] !== capturedSession) return@supply
                if (player.world !== capturedSession.world || getCoreWorld(player) !== coreWorld ||
                    getState(player).mode != mode ||
                    (!isWandItem(player.inventory.itemInMainHand) &&
                        !isWandItem(player.inventory.itemInOffHand))
                ) {
                    closeDirectionOverlay(playerId)
                    return@supply
                }
                capturedSession.publish(labels)
            },
            onFailure = { error ->
                capturedSession.busy = false
                if (directionOverlays.remove(playerId, capturedSession)) capturedSession.close()
                plugin.logger.log(java.util.logging.Level.WARNING, "RailObjectWand direction display failed", error)
            },
            onRetired = {
                if (directionOverlays.remove(playerId, capturedSession)) capturedSession.close()
            }
        )
    }

    private fun closeDirectionOverlay(playerId: UUID) {
        directionOverlays.remove(playerId)?.close()
    }

    private fun showWayPointDialog(
        player: Player,
        wayPointId: String,
        preferredRole: DestinationRoleType? = null
    ) {
        val coreWorld = getCoreWorld(player) ?: return
        val pending = pendingWayPoints[player.uniqueId]?.takeIf { it.id == wayPointId }
        val wayPoint = coreWorld.basicWorldData.wayPoints[wayPointId] ?: pending ?: return
        val destination = coreWorld.simpleAtoWorldData.destinations[wayPointId]
        destinationEditor.seedSession(
            player.uniqueId, wayPoint, destination, pending != null, preferredRole
        )
        val edit = DestinationEdit(player.world, coreWorld, wayPoint, destination, pending != null,
            destinationEditor.configUI.getPlayerSession(player.uniqueId))
        editingWayPoint[player.uniqueId] = edit
        dialogRenderer.showConfigDialog(
            player,
            WayPointEditKeys.GROUP_ID,
            textResolver = RailObjectWandI18n.resolver(player),
            isCurrent = { isCurrentDestinationEdit(it, edit) }
        ) { p ->
            applyWayPointDialog(p, edit)
        }
    }

    private fun isCurrentDestinationEdit(player: Player, edit: DestinationEdit): Boolean {
        if (editingWayPoint[player.uniqueId] !== edit || player.world !== edit.world ||
            getCoreWorld(player) !== edit.coreWorld) return false
        return edit.isCurrent(
            editingWayPoint[player.uniqueId], player.world, getCoreWorld(player),
            destinationEditor.configUI.getPlayerSession(player.uniqueId),
            if (edit.pending) pendingWayPoints[player.uniqueId] else
                edit.coreWorld.basicWorldData.wayPoints[edit.wayPoint.id],
            edit.coreWorld.simpleAtoWorldData.destinations[edit.wayPoint.id]
        )
    }

    private fun applyWayPointDialog(player: Player, edit: DestinationEdit) {
        if (!isCurrentDestinationEdit(player, edit)) return
        val oldId = edit.wayPoint.id
        val coreWorld = edit.coreWorld
        val pending = edit.wayPoint.takeIf { edit.pending }
        val wayPoint = edit.wayPoint
        val original = edit.destination
        val updated = try {
            destinationEditor.readEdit(player.uniqueId, wayPoint, original)
        } catch (failure: Exception) {
            player.sendActionBar(
                Component.text("Destination 配置无效: ${failure.message}", NamedTextColor.RED)
            )
            return
        }
        try {
            if (!mutateRailScoped(player, coreWorld, setOf(wayPoint.railId)) {
                    check(isCurrentDestinationEdit(player, edit)) { "Destination edit is no longer current" }
                    destinationEditor.save(coreWorld, wayPoint, updated, pending != null)
                    if (pending != null) pendingWayPoints.remove(player.uniqueId, pending)
                    editingWayPoint.remove(player.uniqueId, edit)
                }
            ) {
                return
            }
        } catch (failure: Exception) {
            player.sendActionBar(
                Component.text("Destination 保存失败: ${failure.message}", NamedTextColor.RED)
            )
            return
        }
        if (pending == null) renameMarker(player.world.uid, oldId, updated.id, updated.id)
        spawnOrReplaceMarker(player, updated.toWayPoint())
        player.sendMessage(
            RailObjectWandI18n.component(
                player,
                "waypoint.saved",
                updated.id,
                RailObjectWandI18n.format(player, roleTranslationKey(updated.role.type))
            ).color(NamedTextColor.GREEN)
        )
        if (updated.approachDirection != null) {
            player.sendActionBar(
                RailObjectWandI18n.component(player, "waypoint.direction_hint")
                    .color(NamedTextColor.GREEN)
            )
        }
    }

    private fun mutateRailScoped(
        player: Player,
        world: CoreWorld,
        railIds: Collection<UUID>,
        mutation: () -> Unit
    ): Boolean {
        val plan = RailWandTopologyEditPlan.captureRailScopedMutation(world, railIds)
        return when (val acquired = plan.acquire(plugin, player.world.uid)) {
            is RailWandEditPermitAcquisition.Denied -> {
                player.sendMessage(Component.text(
                    "基础设施编辑被拒绝: ${acquired.decision.status.name} ${acquired.decision.detail}",
                    NamedTextColor.RED
                ))
                false
            }
            is RailWandEditPermitAcquisition.Granted -> {
                acquired.use {
                    plan.requireCurrent(world)
                    mutation()
                }
                true
            }
        }
    }

    private fun spawnOrReplaceMarker(player: Player, wayPoint: WayPoint) {
        removeMarkerForPlayer(player, wayPoint.id)
        val coreWorld = getCoreWorld(player) ?: return
        val rail = coreWorld.getRail(wayPoint.railId) ?: return
        val position = rail.curve.getPointAt(wayPoint.arcLength).position
        val world = player.world
        val location = Location(world, position.x(), position.y() + 1.0, position.z())
        val playerId = player.uniqueId
        val playerMarkers = markerEntities.computeIfAbsent(playerId) { ConcurrentHashMap() }
        val handle = MarkerHandle(world.uid, wayPoint.id, wayPoint.id)
        playerMarkers[wayPoint.id] = handle

        plugin.platformDispatcher.runAt(location) markerTask@{
            if (markerEntities[playerId]?.get(handle.id) !== handle) return@markerTask
            val entity = world.spawn(location, ItemDisplay::class.java)
            entity.setItemStack(ItemStack(handle.material))
            entity.isPersistent = false
            entity.isVisibleByDefault = false
            entity.isGlowing = true
            entity.customName(Component.text(handle.displayName, NamedTextColor.GREEN))
            entity.isCustomNameVisible = true
            handle.entity = entity
            plugin.platformDispatcher.runForEntity(
                entity = player,
                action = {
                    if (markerEntities[playerId]?.get(handle.id) === handle) {
                        player.showEntity(plugin, entity)
                    } else {
                        removeMarkerEntity(entity)
                    }
                },
                retired = { removeMarkerEntity(entity) }
            )
        }
    }

    private fun removeMarkerForPlayer(player: Player, wayPointId: String) {
        markerEntities[player.uniqueId]?.remove(wayPointId)?.entity?.let(::removeMarkerEntity)
    }

    private fun clearMarkersForPlayer(player: Player) {
        markerEntities.remove(player.uniqueId)?.values?.forEach { handle ->
            handle.entity?.let(::removeMarkerEntity)
        }
    }

    private fun removeMarkerEntity(entity: ItemDisplay) {
        plugin.platformDispatcher.runForEntity(entity, action = { entity.remove() })
    }

    private fun renameMarker(worldId: UUID, oldId: String, newId: String, displayName: String) {
        renameMarkers(markerEntities.values, worldId, oldId, newId, displayName) { handle ->
            handle.entity?.let { entity ->
                plugin.platformDispatcher.runForEntity(entity, action = {
                    entity.customName(Component.text(displayName, NamedTextColor.GREEN))
                })
            }
        }
    }

    private fun setMarkerMaterial(player: Player, wayPointId: String, material: Material) {
        val handle = markerEntities[player.uniqueId]?.get(wayPointId) ?: return
        handle.material = material
        handle.entity?.let { entity ->
            plugin.platformDispatcher.runForEntity(entity, action = {
                entity.setItemStack(ItemStack(material))
            })
        }
    }

    private fun refreshMarkersForPlayer(player: Player) {
        clearMarkersForPlayer(player)
        if (!isWandItem(player.inventory.itemInMainHand) && !isWandItem(player.inventory.itemInOffHand)) {
            markerViewers.remove(player.uniqueId)
            closeDirectionOverlay(player.uniqueId)
            return
        }
        markerViewers.add(player.uniqueId)
        val coreWorld = getCoreWorld(player) ?: return
        coreWorld.basicWorldData.wayPoints.all().forEach { spawnOrReplaceMarker(player, it) }
        refreshDirectionOverlay(player)
    }

    private fun scheduleMarkerVisibilityRefresh(player: Player) {
        plugin.platformDispatcher.runForEntityLater(player, 1L, action = {
            BukkitThreadGuardBridge.runInPlayerWorldContext(player) runInSimulationContext@ {
                refreshMarkersForPlayer(player)
            }
        })
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

    @EventHandler
    fun onRailObjectWandWorldChanged(event: PlayerChangedWorldEvent) {
        closeDirectionOverlay(event.player.uniqueId)
        editingWayPoint.remove(event.player.uniqueId)
        pendingWayPoints.remove(event.player.uniqueId)
        scheduleMarkerVisibilityRefresh(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onRailObjectWandWorldUnload(event: WorldUnloadEvent) {
        editingWayPoint.entries.removeIf { (playerId, edit) ->
            (edit.world === event.world).also { if (it) pendingWayPoints.remove(playerId) }
        }
        directionOverlays.forEach { (playerId, session) ->
            if (session.world === event.world && directionOverlays.remove(playerId, session)) session.close()
        }
    }

    override fun onPlayerQuit(player: Player, state: RailObjectWandState) {
        clearMarkersForPlayer(player)
        editingWayPoint.remove(player.uniqueId)
        pendingWayPoints.remove(player.uniqueId)
        heavyOperationGate.clear(player.uniqueId)
        closeDirectionOverlay(player.uniqueId)
        markerViewers.remove(player.uniqueId)
        destinationEditor.configUI.removePlayerSession(player.uniqueId)
    }

    internal class MarkerHandle(
        val worldId: UUID,
        @Volatile var id: String,
        @Volatile var displayName: String,
        @Volatile var material: Material = Material.LIME_CONCRETE,
        @Volatile var entity: ItemDisplay? = null
    )

    internal class DirectionOverlaySession(
        val world: World,
        val mode: RailObjectWandMode,
        private val handle: EditorTextHandle
    ) {
        val drawing = RailObjectDirectionDrawing()
        var busy = false
        private var revision = 0L
        @Volatile private var closed = false

        fun matches(world: World, mode: RailObjectWandMode): Boolean =
            !closed && this.world === world && this.mode == mode

        fun publish(labels: List<EditorTextLabel>) {
            if (!closed) handle.submit(revision++, labels)
        }

        fun close() {
            if (closed) return
            closed = true
            handle.close()
        }
    }

    private data class CreateWayPointLookup(
        val pointedRail: HeavyRailQueries.LookedAtRailMatch?,
        val existingWayPoint: HeavyRailQueries.WayPointMatch?
    )

    companion object {
        internal fun renameMarkers(
            playerMarkers: Collection<ConcurrentHashMap<String, MarkerHandle>>,
            worldId: UUID,
            oldId: String,
            newId: String,
            displayName: String,
            updateEntity: (MarkerHandle) -> Unit
        ) {
            playerMarkers.forEach { markers ->
                val handle = markers[oldId] ?: return@forEach
                if (handle.worldId != worldId || !markers.remove(oldId, handle)) return@forEach
                handle.id = newId
                handle.displayName = displayName
                markers[newId] = handle
                updateEntity(handle)
            }
        }

        private const val MAX_SELECT_DISTANCE = 6.0
        private const val MIN_DIRECTION_ARC_DIFFERENCE = 0.25
        private const val DIRECTION_REFRESH_TICKS = 10L
    }
}
