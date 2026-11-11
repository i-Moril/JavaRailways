package work.art1st.javarailways.tunnelbridgebuilder.rail

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Slab
import org.bukkit.entity.Player
import work.art1st.javarailways.bukkit.api.operation.OperationResult
import work.art1st.javarailways.bukkit.api.operation.UndoableOperation
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.rail.resolveLogicalRailLaneIds
import work.art1st.javarailways.core.api.physics.rail.Rail
import work.art1st.javarailways.tunnelbridgebuilder.TunnelBridgeBuilderPlugin
import work.art1st.javarailways.tunnelbridgebuilder.algorithm.BlockPosition
import work.art1st.javarailways.tunnelbridgebuilder.algorithm.BlockPositionChange
import work.art1st.javarailways.tunnelbridgebuilder.algorithm.Carve
import work.art1st.javarailways.tunnelbridgebuilder.algorithm.Ignore
import work.art1st.javarailways.tunnelbridgebuilder.algorithm.Place
import work.art1st.javarailways.tunnelbridgebuilder.algorithm.SampledCenterLine
import work.art1st.javarailways.tunnelbridgebuilder.algorithm.SweepFrame
import work.art1st.javarailways.tunnelbridgebuilder.algorithm.SweepPlanner
import work.art1st.javarailways.tunnelbridgebuilder.algorithm.Vec3
import work.art1st.javarailways.tunnelbridgebuilder.wand.BlockWandState
import work.art1st.javarailways.tunnelbridgebuilder.wand.PlacementRule
import work.art1st.javarailways.tunnelbridgebuilder.wand.WandCrossSectionFactory
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import kotlin.math.ceil

internal fun railSweepSampleArcLengths(totalArcLength: Double, sampleSpacing: Double): List<Double> {
    require(totalArcLength.isFinite() && totalArcLength >= 0.0)
    require(sampleSpacing.isFinite() && sampleSpacing > 0.0)
    val segments = ceil(totalArcLength / sampleSpacing).toInt().coerceAtLeast(1)
    return (0..segments).map { index ->
        when (index) {
            0 -> 0.0
            segments -> totalArcLength
            else -> totalArcLength * (index.toDouble() / segments)
        }
    }
}

class RailSweepService internal constructor(
    private val plugin: TunnelBridgeBuilderPlugin,
    private val services: JavaRailwaysServices
) {
    private data class BlockUndo(val worldId: UUID, val blocks: List<FullBlockSnapshot>)
    private data class BlockPlacement(val blockData: BlockData, val rule: PlacementRule)

    private val operationsInProgress = ConcurrentHashMap<UUID, ServiceGeneration<JavaRailwaysServices.Services>.Lease>()

    private fun beginOperation(playerId: UUID, lease: ServiceGeneration<JavaRailwaysServices.Services>.Lease): Boolean {
        var accepted = false
        operationsInProgress.compute(playerId) { _, previous ->
            if (previous == null || !previous.isCurrent()) {
                accepted = true
                lease
            } else previous
        }
        return accepted
    }

    fun replaceLookedAtRail(player: Player, state: BlockWandState) {
        if (state.primaryRailCell == null) {
            player.sendActionBar(Component.text("请先在 GUI 中设置基准轨道格", NamedTextColor.RED))
            return
        }
        val lease = services.acquire()
        if (lease == null) {
            player.sendActionBar(Component.text("JavaRailways 服务暂不可用", NamedTextColor.YELLOW))
            return
        }
        if (!beginOperation(player.uniqueId, lease)) {
            player.sendActionBar(Component.text("已有隧道/桥梁操作正在执行", NamedTextColor.YELLOW))
            return
        }

        val worldId = player.world.uid
        player.sendActionBar(Component.text("正在查找轨道...", NamedTextColor.GRAY))
        val targetStage = try {
            lease.use { it.railTargets.findLookedAtRail(player, TARGET_MAX_DISTANCE, TARGET_HIT_RADIUS) }
        } catch (failure: Throwable) {
            operationsInProgress.remove(player.uniqueId, lease)
            plugin.logger.log(Level.WARNING, "Rail targeting failed for ${player.name}", failure)
            player.sendActionBar(Component.text("轨道查找失败", NamedTextColor.RED))
            return
        }

        targetStage.whenComplete { selected, targetingFailure ->
            if (!lease.isCurrent()) {
                operationsInProgress.remove(player.uniqueId, lease)
                return@whenComplete
            }
            if (targetingFailure != null) {
                operationsInProgress.remove(player.uniqueId, lease)
                plugin.logger.log(Level.WARNING, "Rail targeting failed for ${player.name}", targetingFailure)
                sendToPlayer(player, Component.text("轨道查找失败", NamedTextColor.RED))
                return@whenComplete
            }
            val target = selected?.orElse(null)
            if (target == null) {
                operationsInProgress.remove(player.uniqueId, lease)
                sendToPlayer(player, Component.text("没有指向轨道", NamedTextColor.RED))
                return@whenComplete
            }

            sendToPlayer(player, Component.text("正在计算逻辑段横断面扫掠...", NamedTextColor.GRAY))
            try {
                plugin.server.asyncScheduler.runNow(plugin) {
                    val result = runCatching { lease.use { plan(resolveLogicalRails(target.rail()), state) } }
                    runForPlayer(
                        player,
                        retired = { operationsInProgress.remove(player.uniqueId, lease) }
                    ) { livePlayer ->
                        if (!lease.isCurrent()) {
                            operationsInProgress.remove(livePlayer.uniqueId, lease)
                            return@runForPlayer
                        }
                        if (livePlayer.world.uid != worldId) {
                            operationsInProgress.remove(livePlayer.uniqueId, lease)
                            livePlayer.sendActionBar(Component.text("世界已改变，操作已取消", NamedTextColor.YELLOW))
                            return@runForPlayer
                        }
                        result.fold(
                            onSuccess = { changes -> applyChanges(livePlayer, changes, lease) },
                            onFailure = { failure ->
                                operationsInProgress.remove(livePlayer.uniqueId, lease)
                                plugin.logger.log(Level.WARNING, "Rail sweep failed for ${livePlayer.name}", failure)
                                livePlayer.sendActionBar(Component.text("横断面扫掠失败", NamedTextColor.RED))
                            }
                        )
                    }
                }
            } catch (failure: Throwable) {
                operationsInProgress.remove(player.uniqueId, lease)
                plugin.logger.log(Level.WARNING, "Could not schedule rail sweep", failure)
            }
        }
    }

    private fun resolveLogicalRails(selected: Rail): List<Rail> {
        val metadata = selected.world.basicWorldData.railMetadata.snapshot()
        return resolveLogicalRailLaneIds(selected.id, metadata).map { railId ->
            requireNotNull(selected.world.getRail(railId)) {
                "Logical rail segment $railId is no longer loaded"
            }
        }
    }

    private fun plan(rails: List<Rail>, state: BlockWandState): List<BlockPositionChange<BlockData>> {
        require(rails.isNotEmpty()) { "A logical rail segment must contain at least one physical rail" }
        val section = WandCrossSectionFactory.create(state) { cell ->
            val material = requireNotNull(Material.matchMaterial(cell.key)) {
                "Unknown block material: ${cell.key}"
            }
            require(material.isBlock && !material.isAir) {
                "Material is not a placeable block: ${cell.key}"
            }
            val blockData = material.createBlockData()
            require(cell.placementRule == PlacementRule.REGULAR || blockData is Slab) {
                "Placement rule ${cell.placementRule} is only supported for slabs: ${cell.key}"
            }
            BlockPlacement(blockData, cell.placementRule)
        }
        return resolvePlacements(rails.flatMap { rail -> SweepPlanner.plan(section, sample(rail)) })
    }

    private fun resolvePlacements(
        planned: List<BlockPositionChange<BlockPlacement>>
    ): List<BlockPositionChange<BlockData>> {
        val resolved = LinkedHashMap<BlockPosition, BlockPositionChange<BlockData>>()
        planned.forEach { change ->
            when (val operation = change.operation) {
                Carve -> mergeResolved(
                    resolved,
                    BlockPositionChange(change.position, Carve, change.cellCenter)
                )
                is Place -> {
                    val placement = operation.block
                    if (placement.rule == PlacementRule.REGULAR) {
                        mergeResolved(
                            resolved,
                            BlockPositionChange(
                                change.position,
                                Place(placement.blockData),
                                change.cellCenter
                            )
                        )
                    } else {
                        val matched = HalfBlockMatcher.match(change.cellCenter.y, placement.rule)
                        val targetPosition = change.position.copy(y = matched.blockY)
                        if (targetPosition != change.position) {
                            mergeResolved(
                                resolved,
                                BlockPositionChange(change.position, Carve, change.cellCenter)
                            )
                        }
                        val slab = placement.blockData.clone() as Slab
                        slab.type = when (matched.half) {
                            HalfBlockHalf.BOTTOM -> Slab.Type.BOTTOM
                            HalfBlockHalf.TOP -> Slab.Type.TOP
                            HalfBlockHalf.DOUBLE -> Slab.Type.DOUBLE
                        }
                        mergeResolved(
                            resolved,
                            BlockPositionChange(targetPosition, Place(slab), change.cellCenter)
                        )
                    }
                }
                Ignore -> Unit
            }
        }
        return resolved.values.toList()
    }

    private fun mergeResolved(
        changes: MutableMap<BlockPosition, BlockPositionChange<BlockData>>,
        candidate: BlockPositionChange<BlockData>
    ) {
        val existing = changes[candidate.position]
        if (existing == null || existing.operation !is Place || candidate.operation is Place) {
            changes[candidate.position] = candidate
        }
    }

    private fun sample(rail: Rail): SampledCenterLine {
        val length = rail.curve.totalArcLength
        val frames = railSweepSampleArcLengths(length, SAMPLE_SPACING).map { arcLength ->
            val point = rail.curve.getPointAt(arcLength)
            // Curve normals include rail superelevation. Tunnel and bridge structures stay
            // upright, while the projected world-up frame still follows the rail gradient.
            SweepFrame.upright(
                position = Vec3(point.position.x(), point.position.y(), point.position.z()),
                tangent = Vec3(point.tangent.x(), point.tangent.y(), point.tangent.z())
            )
        }
        return SampledCenterLine(frames)
    }

    private fun applyChanges(
        player: Player,
        changes: List<BlockPositionChange<BlockData>>,
        lease: ServiceGeneration<JavaRailwaysServices.Services>.Lease
    ) {
        if (changes.isEmpty()) {
            operationsInProgress.remove(player.uniqueId, lease)
            player.sendActionBar(Component.text("模板没有需要替换的方块", NamedTextColor.GRAY))
            return
        }
        val world = player.world
        val groups = changes.groupBy { it.position.x shr 4 to (it.position.z shr 4) }.values.map { group ->
            RegionBlockTask(group.first().position) {
                val snapshots = ArrayList<FullBlockSnapshot>(group.size)
                group.forEach { change ->
                    val position = change.position
                    val block = world.getBlockAt(position.x, position.y, position.z)
                    snapshots += FullBlockSnapshot.capture(block)
                    when (val operation = change.operation) {
                        Carve -> block.setType(Material.AIR, false)
                        is Place -> block.setBlockData(operation.block, false)
                        Ignore -> Unit
                    }
                }
                snapshots
            }
        }
        val before = ArrayList<FullBlockSnapshot>(changes.size)
        runRegionTasks(
            lease = lease,
            world = world,
            tasks = groups,
            onGroupApplied = before::addAll,
            onDone = {
                val recordFailure = runCatching {
                    lease.use { bound ->
                        bound.playerOperations.record(
                            player.uniqueId,
                            plugin,
                            BlockUndoOperation(BlockUndo(world.uid, before.toList()), lease)
                        )
                    }
                }.exceptionOrNull()
                operationsInProgress.remove(player.uniqueId, lease)
                if (recordFailure == null) {
                    sendToPlayer(
                        player,
                        Component.text("已替换 ${before.size} 个方块", NamedTextColor.GREEN)
                    )
                } else {
                    plugin.logger.log(Level.SEVERE, "Could not record block sweep undo", recordFailure)
                    sendToPlayer(
                        player,
                        Component.text("方块已替换，但无法记录撤销", NamedTextColor.RED)
                    )
                }
            },
            onFailure = { failure ->
                operationsInProgress.remove(player.uniqueId, lease)
                plugin.logger.log(Level.SEVERE, "Block sweep failed after ${before.size} changes", failure)
                sendToPlayer(player, Component.text("方块替换中途失败", NamedTextColor.RED))
            }
        )
    }

    private inner class BlockUndoOperation(
        private val undo: BlockUndo,
        private val lease: ServiceGeneration<JavaRailwaysServices.Services>.Lease
    ) : UndoableOperation {
        override fun description(): Component = Component.text("隧道/桥梁方块替换")

        override fun undo(player: Player): CompletionStage<OperationResult> {
            if (!lease.isCurrent()) {
                return CompletableFuture.completedFuture(
                    OperationResult.failed(Component.text("JavaRailways 服务已重启，无法执行旧撤销", NamedTextColor.RED))
                )
            }
            if (!beginOperation(player.uniqueId, lease)) {
                return CompletableFuture.completedFuture(
                    OperationResult.busy(Component.text("已有隧道/桥梁操作正在执行", NamedTextColor.YELLOW))
                )
            }
            if (undo.worldId != player.world.uid) {
                operationsInProgress.remove(player.uniqueId, lease)
                return CompletableFuture.completedFuture(
                    OperationResult.failed(Component.text("请回到执行方块替换的世界后再撤销", NamedTextColor.RED))
                )
            }
            return restoreBlocks(player, undo, lease)
        }
    }

    private fun restoreBlocks(
        player: Player,
        undo: BlockUndo,
        lease: ServiceGeneration<JavaRailwaysServices.Services>.Lease
    ): CompletionStage<OperationResult> {
        val result = CompletableFuture<OperationResult>()
        val world = player.world
        val groups = undo.blocks.groupBy { it.x shr 4 to (it.z shr 4) }.values.map { group ->
            RegionBlockTask(BlockPosition(group.first().x, group.first().y, group.first().z)) {
                group.forEach(FullBlockSnapshot::restore)
                emptyList()
            }
        }
        runRegionTasks(
            lease = lease,
            world = world,
            tasks = groups,
            onGroupApplied = {},
            onDone = {
                operationsInProgress.remove(player.uniqueId, lease)
                result.complete(
                    OperationResult.success(
                        Component.text("已撤销 ${undo.blocks.size} 个方块", NamedTextColor.GREEN)
                    )
                )
            },
            onFailure = { failure ->
                operationsInProgress.remove(player.uniqueId, lease)
                plugin.logger.log(Level.SEVERE, "Block sweep undo failed", failure)
                result.complete(
                    OperationResult.failed(Component.text("方块撤销中途失败", NamedTextColor.RED))
                )
            }
        )
        return result
    }

    private fun runRegionTasks(
        lease: ServiceGeneration<JavaRailwaysServices.Services>.Lease,
        world: World,
        tasks: List<RegionBlockTask>,
        index: Int = 0,
        onGroupApplied: (List<FullBlockSnapshot>) -> Unit,
        onDone: () -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        if (!lease.isCurrent()) {
            onFailure(IllegalStateException("JavaRailways services stopped during block operation"))
            return
        }
        if (index >= tasks.size) {
            onDone()
            return
        }
        val task = tasks[index]
        val position = task.position
        val location = org.bukkit.Location(world, position.x.toDouble(), position.y.toDouble(), position.z.toDouble())
        try {
            plugin.server.regionScheduler.execute(plugin, location) {
                try {
                    onGroupApplied(lease.use { task.action() })
                    runRegionTasks(lease, world, tasks, index + 1, onGroupApplied, onDone, onFailure)
                } catch (failure: Throwable) {
                    onFailure(failure)
                }
            }
        } catch (failure: Throwable) {
            onFailure(failure)
        }
    }

    private fun sendToPlayer(player: Player, message: Component) {
        runForPlayer(player, retired = {}) { it.sendActionBar(message) }
    }

    private fun runForPlayer(player: Player, retired: () -> Unit, action: (Player) -> Unit) {
        if (!plugin.isEnabled) {
            retired()
            return
        }
        try {
            val scheduled = player.scheduler.execute(
                plugin,
                Runnable { if (plugin.isEnabled) action(player) else retired() },
                Runnable(retired),
                0L
            )
            if (!scheduled) retired()
        } catch (failure: Throwable) {
            retired()
            plugin.logger.log(Level.WARNING, "Could not schedule player callback", failure)
        }
    }

    private data class RegionBlockTask(
        val position: BlockPosition,
        val action: () -> List<FullBlockSnapshot>
    )

    private companion object {
        const val SAMPLE_SPACING = 0.5
        const val TARGET_MAX_DISTANCE = 96.0
        const val TARGET_HIT_RADIUS = 1.5
    }
}
