package work.art1st.mineRails.bukkit.command

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.logic.RailVisualBootstrap
import work.art1st.mineRails.bukkit.persistence.RailRenderMetaStore
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.physics.rail.CoreRail
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import org.joml.Vector3d
import org.joml.Vector3dc
import java.util.UUID
import kotlin.math.floor

object RailCommand {
    fun onCommand(sender: CommandSender, args: List<String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage("用法: /mr rail <create|list|connect|delete>")
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
        val coreWorld = world as? work.art1st.mineRails.core.physics.CoreWorld ?: run {
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
        val coreWorld = getPlayerWorld(sender) as? work.art1st.mineRails.core.physics.CoreWorld
        records.forEachIndexed { idx, record ->
            val rail = coreWorld?.getRail(record.railId)
            val nextCount = rail?.let { it.viewAB.nextRails.size + it.viewBA.nextRails.size } ?: 0
            sender.sendMessage("${idx + 1}. ${record.railId} 分段=${record.segments.size} 连接=${nextCount}")
        }

        return true
    }

    private fun handleConnect(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }
        val world = getPlayerWorld(sender) ?: return true
        val coreWorld = world as? work.art1st.mineRails.core.physics.CoreWorld ?: run {
            sender.sendMessage("错误: 无法获取 CoreWorld")
            return true
        }
        val tolerance = args.firstOrNull()?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: Curve.TOLERANCE
        val rails = RailRenderMetaStore.load(sender.world)
            .mapNotNull { coreWorld.getRail(it.railId) }

        val result = connectNearbyEndpoints(rails, tolerance)

        sender.sendMessage("轨道自动连接完成: 新增 ${result.connected} 条连接，候选失败 ${result.failed} 条，容差=${"%.4f".format(tolerance)}m")
        if (result.connected == 0) {
            sender.sendMessage("如果视觉上贴合但仍未连接，请检查两个端点坐标是否完全重合（/mr rail list 可看连接数）。")
        }
        return true
    }

    private fun areConnected(a: CoreRail, b: CoreRail): Boolean {
        return a.viewAB.nextRails.any { it.rail == b } ||
            a.viewBA.nextRails.any { it.rail == b } ||
            b.viewAB.nextRails.any { it.rail == a } ||
            b.viewBA.nextRails.any { it.rail == a }
    }

    private data class ConnectResult(val connected: Int, val failed: Int)
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
        var failed = 0

        for (endpoint in endpoints) {
            for (candidate in nearbyEndpoints(endpoint.position, tolerance, buckets)) {
                if (candidate.rail == endpoint.rail) continue
                val pairKey = railPairKey(endpoint.rail, candidate.rail)
                if (!considered.add(pairKey)) continue
                if (endpoint.position.distance(candidate.position) > tolerance) continue
                if (areConnected(endpoint.rail, candidate.rail)) continue
                runCatching {
                    CoreRail.connect(endpoint.rail, candidate.rail)
                    connected++
                }.onFailure {
                    failed++
                }
            }
            buckets.getOrPut(cellKey(endpoint.position, tolerance)) { mutableListOf() }.add(endpoint)
        }
        return ConnectResult(connected, failed)
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
            sender.sendMessage("用法: /mr rail delete <railId>")
            return true
        }
        val railId = runCatching { UUID.fromString(idArg) }.getOrNull()
        if (railId == null) {
            sender.sendMessage("railId 格式错误: $idArg")
            return true
        }

        val world = getPlayerWorld(sender) ?: return true
        val coreWorld = world as? work.art1st.mineRails.core.physics.CoreWorld ?: run {
            sender.sendMessage("错误: 无法获取 CoreWorld")
            return true
        }
        coreWorld.getRail(railId)?.let { rail ->
            rail.destroy()
            coreWorld.removeRail(rail)
        }
        RailVisualBootstrap.unregisterRail(railId)
        RailRenderMetaStore.removeRail(sender.world, railId)
        sender.sendMessage("删除轨道成功: $railId")
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
        val (_, nrm, _) = work.art1st.mineRails.basic.curve.ConstraintSolver.computeFrame(tan)
        val startPos = org.joml.Vector3d(startX, startY, startZ)
        val endPos = org.joml.Vector3d(startX + tan.x() * length, startY + tan.y() * length, startZ + tan.z() * length)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(startPos, Vector3d(tan), Vector3d(nrm), 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(endPos, Vector3d(tan), Vector3d(nrm), length, Curve.SegmentType.LINE_END)
        ))
    }
}