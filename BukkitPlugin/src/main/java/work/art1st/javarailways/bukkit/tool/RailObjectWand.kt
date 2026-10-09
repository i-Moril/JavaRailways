package work.art1st.javarailways.bukkit.tool

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
import org.joml.Vector3dc
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.basic.rail.DirectionRestrictionResolver
import work.art1st.javarailways.simpleato.api.destination.DestinationRoleType
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.rail.infrastructureMetadata as metadata
import work.art1st.javarailways.simpleatobukkit.tool.RailObjectDestinationEditor
import work.art1st.javarailways.simpleatobukkit.tool.WayPointEditKeys
import work.art1st.javarailways.bukkit.BukkitPlugin
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
    WAY_POINT("路径点模式", "右键创建/编辑路径点 | 左键选择并删除 | Shift+F 切换模式"),
    DIRECTION_RESTRICTION("方向限制模式", "右键添加方向限制 | 左键删除 | Shift+F 切换模式")
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

internal object WayPointApproachParticleLayout {
    fun arcLengths(
        destinationArcInView: Double,
        phase: Double,
        previewLength: Double = 24.0,
        spacing: Double = 4.0,
        maxPoints: Int = 6
    ): List<Double> {
        if (destinationArcInView < 0.0 || previewLength <= 0.0 || spacing <= 0.0 || maxPoints <= 0) {
            return emptyList()
        }
        val start = (destinationArcInView - previewLength).coerceAtLeast(0.0)
        var arc = start + ((phase % spacing) + spacing) % spacing
        val result = mutableListOf<Double>()
        while (arc <= destinationArcInView && result.size < maxPoints) {
            result += arc
            arc += spacing
        }
        return result
    }
}

class RailObjectWand(plugin: BukkitPlugin) : AbstractWand<RailObjectWandState, RailObjectWandMode>(
    plugin = plugin,
    wandKey = "javarailways_rail_object_wand",
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
    private val markerEntities =
        ConcurrentHashMap<UUID, ConcurrentHashMap<String, MarkerHandle>>()
    private val editingWayPoint = mutableMapOf<UUID, String>()
    /** Unsaved clicks stay private to the dialog and are never registered or rendered. */
    private val pendingWayPoints = mutableMapOf<UUID, WayPoint>()
    private val heavyOperationGate = PlayerOperationGate()
    private val visibleDirectionRails = ConcurrentHashMap<UUID, MutableSet<UUID>>()
    private val visibleApproachWayPoints = ConcurrentHashMap<UUID, MutableSet<String>>()
    private val particleViewers = ConcurrentHashMap.newKeySet<UUID>()
    @Volatile private var directionParticleFrame: Long = 0L
    private val destinationEditor = RailObjectDestinationEditor()
    private val dialogRenderer = DialogRenderer(destinationEditor.configUI)

    override fun createDefaultState(): RailObjectWandState = RailObjectWandState()

    override fun encodeState(state: RailObjectWandState): String = RailObjectWandStateCodec.encode(state)

    override fun decodeState(encoded: String): RailObjectWandState? = RailObjectWandStateCodec.decode(encoded)

    override fun onAfterRegisterEvents() {
        plugin.platformDispatcher.runGlobalAtFixedRate(PARTICLE_REFRESH_TICKS, PARTICLE_REFRESH_TICKS) {
            directionParticleFrame++
            particleViewers.forEach { playerId ->
                val player = plugin.server.getPlayer(playerId)
                if (player == null) {
                    particleViewers.remove(playerId)
                } else {
                    plugin.platformDispatcher.runForEntity(player, action = {
                        BukkitThreadGuardBridge.runInSimulationContext {
                            renderDirectionRestrictionParticles(player)
                            renderWayPointApproachParticles(player)
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
        particleViewers.forEach { playerId ->
            val player = plugin.server.getPlayer(playerId)
            if (player == null) {
                particleViewers.remove(playerId)
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
                                "已删除轨道 ${rail.id} 的方向限制",
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
                        "已限制轨道 ${rail.id} 只允许 ${allowedDirection.name} 方向行驶",
                        NamedTextColor.GREEN
                    ))
                }
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

    private fun renderDirectionRestrictionParticles(player: Player) {
        if ((!isWandItem(player.inventory.itemInMainHand) && !isWandItem(player.inventory.itemInOffHand)) ||
            getState(player).mode != RailObjectWandMode.DIRECTION_RESTRICTION
        ) {
            visibleDirectionRails.remove(player.uniqueId)
            return
        }
        val coreWorld = getCoreWorld(player) ?: return
        val directions = coreWorld.basicWorldData.operatingDirections
        val playerPosition = Vector3d(player.location.x, player.location.y, player.location.z)
        val previousVisible = visibleDirectionRails.getOrPut(player.uniqueId) { mutableSetOf() }
        val candidates = coreWorld.allRails.mapNotNull { rail ->
            val allowedDirection = directions[rail.id] ?: return@mapNotNull null
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

    private fun renderWayPointApproachParticles(player: Player) {
        if ((!isWandItem(player.inventory.itemInMainHand) && !isWandItem(player.inventory.itemInOffHand)) ||
            getState(player).mode != RailObjectWandMode.WAY_POINT
        ) {
            visibleApproachWayPoints.remove(player.uniqueId)
            return
        }
        val coreWorld = getCoreWorld(player) ?: return
        val playerPosition = Vector3d(player.location.x, player.location.y, player.location.z)
        val previousVisible = visibleApproachWayPoints.getOrPut(player.uniqueId) { mutableSetOf() }
        val candidates = coreWorld.simpleAtoWorldData.destinations.all().mapNotNull { destination ->
            val direction = destination.approachDirection ?: return@mapNotNull null
            val rail = coreWorld.getRail(destination.railId) ?: return@mapNotNull null
            val target = rail.curve.getPointAt(destination.arcLengthInAbMeters)
            val distanceSquared = target.position.distanceSquared(playerPosition)
            val radius = if (destination.id in previousVisible) PARTICLE_EXIT_RADIUS else PARTICLE_ENTER_RADIUS
            if (distanceSquared > radius * radius) return@mapNotNull null
            ApproachParticleTarget(destination.id, rail, direction, destination.arcLengthInAbMeters, distanceSquared)
        }.sortedBy { it.distanceSquared }.take(MAX_VISIBLE_APPROACH_WAYPOINTS)

        previousVisible.clear()
        previousVisible.addAll(candidates.map(ApproachParticleTarget::wayPointId))
        var remainingParticles = MAX_PARTICLES_PER_REFRESH
        val flowPhase = directionParticleFrame * FLOW_METERS_PER_REFRESH
        for (candidate in candidates) {
            if (remainingParticles <= 0) break
            val view = candidate.rail.getView(candidate.direction)
            val arcInView = when (candidate.direction) {
                ViewDirection.AB -> candidate.arcLengthInAb
                ViewDirection.BA -> view.curve.totalArcLength - candidate.arcLengthInAb
            }
            for (arc in WayPointApproachParticleLayout.arcLengths(arcInView, flowPhase)) {
                if (remainingParticles <= 0) break
                val point = view.curve.getPointAt(arc)
                spawnDust(
                    player,
                    Vector3d(point.position).fma(0.5, point.normal),
                    WAYPOINT_APPROACH_COLOR,
                    1.0f
                )
                remainingParticles--
            }
            if (remainingParticles > 0) {
                val target = view.curve.getPointAt(arcInView)
                spawnDust(
                    player,
                    Vector3d(target.position).fma(0.75, target.normal),
                    WAYPOINT_TARGET_COLOR,
                    1.35f
                )
                remainingParticles--
            }
        }
    }

    private fun approximateDistanceSquared(
        curve: work.art1st.javarailways.core.api.physics.curve.PrimeCurve,
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

    private fun showWayPointDialog(
        player: Player,
        wayPointId: String,
        preferredRole: DestinationRoleType? = null
    ) {
        val coreWorld = getCoreWorld(player) ?: return
        val pending = pendingWayPoints[player.uniqueId]?.takeIf { it.id == wayPointId }
        val wayPoint = coreWorld.basicWorldData.wayPoints[wayPointId] ?: pending ?: return
        editingWayPoint[player.uniqueId] = wayPointId
        val destination = coreWorld.simpleAtoWorldData.destinations[wayPointId]
        destinationEditor.seedSession(
            player.uniqueId, wayPoint, destination, pending != null, preferredRole
        )
        dialogRenderer.showConfigDialog(
            player,
            WayPointEditKeys.GROUP_ID,
            textResolver = RailObjectWandI18n.resolver(player)
        ) { p ->
            applyWayPointDialog(p)
        }
    }

    private fun applyWayPointDialog(player: Player) {
        val oldId = editingWayPoint.remove(player.uniqueId) ?: return
        val coreWorld = getCoreWorld(player) ?: return
        val pending = pendingWayPoints[player.uniqueId]?.takeIf { it.id == oldId }
        val wayPoint = coreWorld.basicWorldData.wayPoints[oldId] ?: pending ?: return
        val original = coreWorld.simpleAtoWorldData.destinations[oldId]
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
                    if (pending != null) pendingWayPoints.remove(player.uniqueId)
                    destinationEditor.save(coreWorld, wayPoint, updated, pending != null)
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
        if (pending == null) renameMarker(oldId, updated.id, updated.id)
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
        val handle = MarkerHandle(wayPoint.id, wayPoint.id)
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

    private fun renameMarker(oldId: String, newId: String, displayName: String) {
        markerEntities.forEach { (_, playerMarkers) ->
            val handle = playerMarkers.remove(oldId) ?: return@forEach
            handle.id = newId
            handle.displayName = displayName
            playerMarkers[newId] = handle
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
            particleViewers.remove(player.uniqueId)
            return
        }
        particleViewers.add(player.uniqueId)
        val coreWorld = getCoreWorld(player) ?: return
        coreWorld.basicWorldData.wayPoints.all().forEach { spawnOrReplaceMarker(player, it) }
    }

    private fun scheduleMarkerVisibilityRefresh(player: Player) {
        plugin.platformDispatcher.runForEntityLater(player, 1L, action = {
            BukkitThreadGuardBridge.runInSimulationContext {
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

    override fun onPlayerQuit(player: Player, state: RailObjectWandState) {
        clearMarkersForPlayer(player)
        editingWayPoint.remove(player.uniqueId)
        pendingWayPoints.remove(player.uniqueId)
        heavyOperationGate.clear(player.uniqueId)
        visibleDirectionRails.remove(player.uniqueId)
        visibleApproachWayPoints.remove(player.uniqueId)
        particleViewers.remove(player.uniqueId)
        destinationEditor.configUI.removePlayerSession(player.uniqueId)
    }

    private class MarkerHandle(
        @Volatile var id: String,
        @Volatile var displayName: String,
        @Volatile var material: Material = Material.LIME_CONCRETE,
        @Volatile var entity: ItemDisplay? = null
    )

    private data class ApproachParticleTarget(
        val wayPointId: String,
        val rail: work.art1st.javarailways.core.physics.rail.CoreRail,
        val direction: ViewDirection,
        val arcLengthInAb: Double,
        val distanceSquared: Double
    )

    private data class CreateWayPointLookup(
        val pointedRail: HeavyRailQueries.LookedAtRailMatch?,
        val existingWayPoint: HeavyRailQueries.WayPointMatch?
    )

    companion object {
        private const val MAX_SELECT_DISTANCE = 6.0
        private const val MIN_DIRECTION_ARC_DIFFERENCE = 0.25
        private const val PARTICLE_REFRESH_TICKS = 4L
        private const val PARTICLE_ENTER_RADIUS = 64.0
        private const val PARTICLE_EXIT_RADIUS = 72.0
        private const val MAX_VISIBLE_DIRECTION_RAILS = 16
        private const val MAX_VISIBLE_APPROACH_WAYPOINTS = 12
        private const val MAX_PARTICLES_PER_REFRESH = 128
        private const val FLOW_METERS_PER_REFRESH = 1.25
        private val DISTANCE_PROBES = doubleArrayOf(0.0, 0.25, 0.5, 0.75, 1.0)
        private val ALLOWED_DIRECTION_COLOR = Color.fromRGB(64, 255, 96)
        private val WAYPOINT_APPROACH_COLOR = Color.fromRGB(64, 255, 160)
        private val WAYPOINT_TARGET_COLOR = Color.fromRGB(64, 200, 255)
        private val FORBIDDEN_ENTRY_COLOR = Color.fromRGB(255, 48, 48)
    }
}
