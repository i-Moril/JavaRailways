package work.art1st.javarailways.bukkit.command

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.logic.RailVisualBootstrap
import work.art1st.javarailways.bukkit.persistence.RailRenderMetaStore
import work.art1st.javarailways.basic.api.destination.OperationalDestinationService
import work.art1st.javarailways.basic.pathFinding.basicWorldData
import work.art1st.javarailways.basic.rail.RailAutoConnector
import work.art1st.javarailways.basic.rail.SpeedLimitType
import work.art1st.javarailways.basic.rail.infrastructureMetadata as metadata
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import org.joml.Vector3d
import org.joml.Vector3dc
import java.util.UUID
import kotlin.math.floor

object RailCommand {
    fun onCommand(sender: CommandSender, args: List<String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage("用法: /jr rail <create|list|connect|delete>")
            return true
        }

        when (args[0].lowercase()) {
            "create" -> return handleCreate(sender, args.drop(1))
            "list" -> return handleList(sender)
            "connect" -> return handleConnect(sender, args.drop(1))
            "delete" -> return handleDelete(sender, args.drop(1))
            else -> sender.sendMessage("未知子命令: ${args[0]}")
        }
        return true
    }

    private fun handleCreate(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }

        val length = args.firstOrNull()?.toDoubleOrNull() ?: 10.0
        val railType = args.getOrNull(1) ?: "standard"

        val world = getPlayerWorld(sender) ?: return true
        val coreWorld = world as? work.art1st.javarailways.core.physics.CoreWorld ?: run {
            sender.sendMessage("错误: 无法获取 CoreWorld")
            return true
        }

        // 计算朝向
        val yaw = sender.location.yaw
        val direction = Vector3d(
            -kotlin.math.sin(Math.toRadians(yaw.toDouble())),
            0.0,
            kotlin.math.cos(Math.toRadians(yaw.toDouble()))
        )

        // 获取玩家位置
        val startX = sender.location.x
        val startY = sender.location.y
        val startZ = sender.location.z

        // 创建直线曲线
        val curve = createStraightCurve(startX, startY, startZ, direction, length)

        // 创建轨道
        val rail = CoreRail(
            coreWorld,
            curve,
            mutableListOf(),
            mutableListOf()
        )
        val persistedMeta = RailVisualBootstrap.registerRail(rail, railType)
        RailRenderMetaStore.upsertRail(sender.world, persistedMeta)

        sender.sendMessage("创建轨道成功: ${rail.id}")
        sender.sendMessage("长度: ${"%.2f".format(length)} 米")
        sender.sendMessage("渲染分段: ${persistedMeta.segments.size}（每段 8.0m）")

        return true
    }

    private fun handleList(sender: CommandSender): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }

        getPlayerWorld(sender) ?: return true
        val records = RailRenderMetaStore.load(sender.world)

        sender.sendMessage("=== 轨道列表 ===")
        if (records.isEmpty()) {
            sender.sendMessage("当前世界暂无轨道渲染记录")
            return true
        }
        val coreWorld = getPlayerWorld(sender) as? work.art1st.javarailways.core.physics.CoreWorld
        records.forEachIndexed { idx, record ->
            val rail = coreWorld?.getRail(record.railId)
            val nextCount = rail?.let { it.viewAB.nextRails.size + it.viewBA.nextRails.size } ?: 0
            val subgrade = rail?.metadata?.speedLimits?.value(SpeedLimitType.SUBGRADE)
                ?.let { "%.0fkm/h".format(it * 3.6) } ?: "未设置"
            val curve = rail?.metadata?.speedLimits?.value(SpeedLimitType.CURVE)
                ?.let { "%.0fkm/h".format(it * 3.6) } ?: "无"
            val derailSpeed = rail?.metadata?.derailSpeedMetersPerSecond?.let { "%.0fkm/h".format(it * 3.6) } ?: "∞"
            val radius = rail?.metadata?.geometry?.curveRadiusMeters?.let { "%.0fm".format(it) } ?: "自动"
            val parallel = rail?.metadata?.geometry?.parallel?.let { "${it.index + 1}/${it.count}" } ?: "单线"
            sender.sendMessage(
                "${idx + 1}. ${record.railId} 分段=${record.segments.size} 连接=$nextCount " +
                    "SUBGRADE=$subgrade CURVE=$curve 脱轨=$derailSpeed 半径=$radius 并行=$parallel"
            )
        }

        return true
    }

    private fun handleConnect(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }
        val world = getPlayerWorld(sender) ?: return true
        val coreWorld = world as? work.art1st.javarailways.core.physics.CoreWorld ?: run {
            sender.sendMessage("错误: 无法获取 CoreWorld")
            return true
        }
        val tolerance = args.firstOrNull()?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: Curve.TOLERANCE
        val result = connectNearbyEndpoints(coreWorld.allRails.toList(), tolerance)

        sender.sendMessage("轨道自动连接完成: 新增 ${result.connected} 条连接，角度跳过 ${result.skippedByAngle} 条，候选失败 ${result.failed} 条，容差=${"%.4f".format(tolerance)}m")
        if (result.connected == 0) {
            if (result.skippedByAngle > 0) {
                sender.sendMessage("部分端点因切向夹角超过 30° 被跳过；请使用真实过渡曲线/道岔曲线连接侧线。")
            } else {
                sender.sendMessage("如果视觉上贴合但仍未连接，请检查两个端点坐标是否完全重合（/jr rail list 可看连接数）。")
            }
        }
        return true
    }

    private data class ConnectResult(val connected: Int, val skippedByAngle: Int, val failed: Int)
    private data class Endpoint(val rail: CoreRail, val position: Vector3dc)
    private data class CellKey(val x: Long, val y: Long, val z: Long)
    private data class RailPairKey(val first: UUID, val second: UUID)

    /**
     * Connect by spatially bucketing endpoints instead of checking every rail pair.
     * The previous O(n²) scan ran on the Bukkit main thread and could freeze worlds with
     * many persisted rails. Neighbour-cell lookup keeps normal use close to O(n).
     */
    private fun connectNearbyEndpoints(rails: List<CoreRail>, tolerance: Double): ConnectResult {
        val endpoints = rails.flatMap { rail ->
            listOf(
                Endpoint(rail, rail.viewBA.endPoint.position),
                Endpoint(rail, rail.viewAB.endPoint.position)
            )
        }
        val buckets = HashMap<CellKey, MutableList<Endpoint>>()
        val considered = HashSet<RailPairKey>()
        var connected = 0
        var skippedByAngle = 0
        var failed = 0

        val options = RailAutoConnector.Options(endpointTolerance = tolerance)
        for (endpoint in endpoints) {
            for (candidate in nearbyEndpoints(endpoint.position, tolerance, buckets)) {
                if (candidate.rail == endpoint.rail) continue
                val pairKey = railPairKey(endpoint.rail, candidate.rail)
                if (!considered.add(pairKey)) continue
                if (endpoint.position.distance(candidate.position) > tolerance) continue
                when (RailAutoConnector.connectIfAllowed(endpoint.rail, candidate.rail, options).outcome) {
                    RailAutoConnector.Outcome.CONNECTED -> connected++
                    RailAutoConnector.Outcome.ANGLE_TOO_SHARP -> skippedByAngle++
                    RailAutoConnector.Outcome.INVALID_TANGENT,
                    RailAutoConnector.Outcome.TOO_MANY_ENDPOINTS,
                    RailAutoConnector.Outcome.AMBIGUOUS_TURNOUT,
                    RailAutoConnector.Outcome.TURNOUT_ANGLE_TOO_SHARP,
                    RailAutoConnector.Outcome.TURNOUT_INVALID_TANGENT,
                    RailAutoConnector.Outcome.TURNOUT_LOCKING_DISTANCE_UNAVAILABLE -> failed++
                    RailAutoConnector.Outcome.ALREADY_CONNECTED,
                    RailAutoConnector.Outcome.TOO_FAR -> Unit
                }
            }
            buckets.getOrPut(cellKey(endpoint.position, tolerance)) { mutableListOf() }.add(endpoint)
        }
        return ConnectResult(connected, skippedByAngle, failed)
    }

    private fun nearbyEndpoints(
        position: Vector3dc,
        tolerance: Double,
        buckets: Map<CellKey, List<Endpoint>>
    ): Sequence<Endpoint> {
        if (tolerance <= 0.0) {
            return buckets[cellKey(position, tolerance)].orEmpty().asSequence()
        }
        val base = cellKey(position, tolerance)
        return sequence {
            for (dx in -1L..1L) {
                for (dy in -1L..1L) {
                    for (dz in -1L..1L) {
                        buckets[CellKey(base.x + dx, base.y + dy, base.z + dz)]?.let { yieldAll(it) }
                    }
                }
            }
        }
    }

    private fun cellKey(position: Vector3dc, tolerance: Double): CellKey {
        if (tolerance <= 0.0) {
            return CellKey(
                java.lang.Double.doubleToLongBits(position.x()),
                java.lang.Double.doubleToLongBits(position.y()),
                java.lang.Double.doubleToLongBits(position.z())
            )
        }
        return CellKey(
            floor(position.x() / tolerance).toLong(),
            floor(position.y() / tolerance).toLong(),
            floor(position.z() / tolerance).toLong()
        )
    }

    private fun railPairKey(a: CoreRail, b: CoreRail): RailPairKey =
        if (a.id.toString() <= b.id.toString()) RailPairKey(a.id, b.id) else RailPairKey(b.id, a.id)

    private fun handleDelete(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }
        val idArg = args.firstOrNull()
        if (idArg == null) {
            sender.sendMessage("用法: /jr rail delete <railId>")
            return true
        }
        val railId = runCatching { UUID.fromString(idArg) }.getOrNull()
        if (railId == null) {
            sender.sendMessage("railId 格式错误: $idArg")
            return true
        }

        val world = getPlayerWorld(sender) ?: return true
        val coreWorld = world as? work.art1st.javarailways.core.physics.CoreWorld ?: run {
            sender.sendMessage("错误: 无法获取 CoreWorld")
            return true
        }
        var removedDestinations = 0
        coreWorld.getRail(railId)?.let { rail ->
            removedDestinations = OperationalDestinationService(coreWorld)
                .removeAllOnRail(railId).size
            rail.destroy()
            coreWorld.removeRail(rail)
        }
        coreWorld.basicWorldData.railMetadata.remove(railId)
        RailVisualBootstrap.unregisterRail(railId)
        RailRenderMetaStore.removeRail(sender.world, railId)
        sender.sendMessage(
            "删除轨道成功: $railId" +
                if (removedDestinations > 0) "（同步删除 $removedDestinations 个 destination）" else ""
        )
        return true
    }

    private fun getPlayerWorld(player: Player): World? {
        val bukkitWorld = player.world
        val worldManager = BukkitPlugin.INSTANCE.worldManager
        return worldManager.railWorlds[bukkitWorld]
    }

    /**
     * 创建直线曲线（2 点 LINE_START/LINE_END，法线 Gram-Schmidt 正交化）。
     */
    private fun createStraightCurve(
        startX: Double,
        startY: Double,
        startZ: Double,
        direction: Vector3d,
        length: Double
    ): PrimeCurve {
        val tan = Vector3d(direction).normalize()
        val (_, nrm, _) = work.art1st.javarailways.basic.curve.ConstraintSolver.computeFrame(tan)
        val startPos = org.joml.Vector3d(startX, startY, startZ)
        val endPos = org.joml.Vector3d(startX + tan.x() * length, startY + tan.y() * length, startZ + tan.z() * length)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(startPos, Vector3d(tan), Vector3d(nrm), 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(endPos, Vector3d(tan), Vector3d(nrm), length, Curve.SegmentType.LINE_END)
        ))
    }
}