package work.art1st.javarailways.tunnelbridgebuilder.rail

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import work.art1st.javarailways.bukkit.api.operation.OperationResult
import work.art1st.javarailways.bukkit.api.operation.PlayerOperationService
import work.art1st.javarailways.bukkit.api.operation.UndoableOperation
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
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
import work.art1st.javarailways.tunnelbridgebuilder.wand.WandCrossSectionFactory
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import kotlin.math.ceil

class RailSweepService(
    private val plugin: TunnelBridgeBuilderPlugin,
    private val railTargets: RailTargetService,
    private val playerOperations: PlayerOperationService
) {
    private data class StoredBlock(val x: Int, val y: Int, val z: Int, val blockData: String)
    private data class BlockUndo(val worldId: UUID, val blocks: List<StoredBlock>)

    private val operationsInProgress = ConcurrentHashMap.newKeySet<UUID>()

    fun replaceLookedAtRail(player: Player, state: BlockWandState) {
        if (state.primaryRailCell == null) {
            player.sendActionBar(Component.text("请先在 GUI 中设置基准轨道格", NamedTextColor.RED))
            return
        }
        if (!operationsInProgress.add(player.uniqueId)) {
            player.sendActionBar(Component.text("已有隧道/桥梁操作正在执行", NamedTextColor.YELLOW))
            return
        }

        val worldId = player.world.uid
        player.sendActionBar(Component.text("正在查找轨道...", NamedTextColor.GRAY))
        val targetStage = try {
            railTargets.findLookedAtRail(player, TARGET_MAX_DISTANCE, TARGET_HIT_RADIUS)
        } catch (failure: Throwable) {
            operationsInProgress.remove(player.uniqueId)
            plugin.logger.log(Level.WARNING, "Rail targeting failed for ${player.name}", failure)
            player.sendActionBar(Component.text("轨道查找失败", NamedTextColor.RED))
            return
        }

        targetStage.whenComplete { selected, targetingFailure ->
            if (targetingFailure != null) {
                operationsInProgress.remove(player.uniqueId)
                plugin.logger.log(Level.WARNING, "Rail targeting failed for ${player.name}", targetingFailure)
                sendToPlayer(player, Component.text("轨道查找失败", NamedTextColor.RED))
                return@whenComplete
            }
            val target = selected?.orElse(null)
            if (target == null) {
                operationsInProgress.remove(player.uniqueId)
                sendToPlayer(player, Component.text("没有指向轨道", NamedTextColor.RED))
                return@whenComplete
            }

            sendToPlayer(player, Component.text("正在计算横断面扫掠...", NamedTextColor.GRAY))
            plugin.server.asyncScheduler.runNow(plugin) {
                val result = runCatching { plan(target.rail(), state) }
                runForPlayer(
                    player,
                    retired = { operationsInProgress.remove(player.uniqueId) }
                ) { livePlayer ->
                    if (livePlayer.world.uid != worldId) {
                        operationsInProgress.remove(livePlayer.uniqueId)
                        livePlayer.sendActionBar(Component.text("世界已改变，操作已取消", NamedTextColor.YELLOW))
                        return@runForPlayer
                    }
                    result.fold(
                        onSuccess = { changes -> applyChanges(livePlayer, changes) },
                        onFailure = { failure ->
                            operationsInProgress.remove(livePlayer.uniqueId)
                            plugin.logger.log(Level.WARNING, "Rail sweep failed for ${livePlayer.name}", failure)
                            livePlayer.sendActionBar(Component.text("横断面扫掠失败", NamedTextColor.RED))
                        }
                    )
                }
            }
        }
    }

    private fun plan(rail: Rail, state: BlockWandState): List<BlockPositionChange<BlockData>> {
        val section = WandCrossSectionFactory.create(state) { key ->
            val material = requireNotNull(Material.matchMaterial(key)) { "Unknown block material: $key" }
            require(material.isBlock && !material.isAir) { "Material is not a placeable block: $key" }
            material.createBlockData()
        }
        return SweepPlanner.plan(section, sample(rail))
    }

    private fun sample(rail: Rail): SampledCenterLine {
        val length = rail.curve.totalArcLength
        val segments = ceil(length / SAMPLE_SPACING).toInt().coerceAtLeast(1)
        val frames = (0..segments).map { index ->
            val point = rail.curve.getPointAt(length * index / segments)
            SweepFrame(
                position = Vec3(point.position.x(), point.position.y(), point.position.z()),
                tangent = Vec3(point.tangent.x(), point.tangent.y(), point.tangent.z()),
                normal = Vec3(point.normal.x(), point.normal.y(), point.normal.z())
            )
        }
        return SampledCenterLine(frames)
    }

    private fun applyChanges(player: Player, changes: List<BlockPositionChange<BlockData>>) {
        if (changes.isEmpty()) {
            operationsInProgress.remove(player.uniqueId)
            player.sendActionBar(Component.text("模板没有需要替换的方块", NamedTextColor.GRAY))
            return
        }
        val world = player.world
        val groups = changes.groupBy { it.position.x shr 4 to (it.position.z shr 4) }.values.map { group ->
            RegionBlockTask(group.first().position) {
                val snapshots = ArrayList<StoredBlock>(group.size)
                group.forEach { change ->
                    val position = change.position
                    val block = world.getBlockAt(position.x, position.y, position.z)
                    snapshots += StoredBlock(position.x, position.y, position.z, block.blockData.asString)
                    when (val operation = change.operation) {
                        Carve -> block.setType(Material.AIR, false)
                        is Place -> block.setBlockData(operation.block, false)
                        Ignore -> Unit
                    }
                }
                snapshots
            }
        }
        val before = ArrayList<StoredBlock>(changes.size)
        runRegionTasks(
            world = world,
            tasks = groups,
            onGroupApplied = before::addAll,
            onDone = {
                val recordFailure = runCatching {
                    playerOperations.record(
                        player.uniqueId,
                        plugin,
                        BlockUndoOperation(BlockUndo(world.uid, before.toList()))
                    )
                }.exceptionOrNull()
                operationsInProgress.remove(player.uniqueId)
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
                operationsInProgress.remove(player.uniqueId)
                plugin.logger.log(Level.SEVERE, "Block sweep failed after ${before.size} changes", failure)
                sendToPlayer(player, Component.text("方块替换中途失败", NamedTextColor.RED))
            }
        )
    }

    private inner class BlockUndoOperation(
        private val undo: BlockUndo
    ) : UndoableOperation {
        override fun description(): Component = Component.text("隧道/桥梁方块替换")

        override fun undo(player: Player): CompletionStage<OperationResult> {
            if (!operationsInProgress.add(player.uniqueId)) {
                return CompletableFuture.completedFuture(
                    OperationResult.busy(Component.text("已有隧道/桥梁操作正在执行", NamedTextColor.YELLOW))
                )
            }
            if (undo.worldId != player.world.uid) {
                operationsInProgress.remove(player.uniqueId)
                return CompletableFuture.completedFuture(
                    OperationResult.failed(Component.text("请回到执行方块替换的世界后再撤销", NamedTextColor.RED))
                )
            }
            return restoreBlocks(player, undo)
        }
    }

    private fun restoreBlocks(player: Player, undo: BlockUndo): CompletionStage<OperationResult> {
        val result = CompletableFuture<OperationResult>()
        val world = player.world
        val groups = undo.blocks.groupBy { it.x shr 4 to (it.z shr 4) }.values.map { group ->
            RegionBlockTask(BlockPosition(group.first().x, group.first().y, group.first().z)) {
                group.forEach { saved ->
                    world.getBlockAt(saved.x, saved.y, saved.z)
                        .setBlockData(Bukkit.createBlockData(saved.blockData), false)
                }
                emptyList()
            }
        }
        runRegionTasks(
            world = world,
            tasks = groups,
            onGroupApplied = {},
            onDone = {
                operationsInProgress.remove(player.uniqueId)
                result.complete(
                    OperationResult.success(
                        Component.text("已撤销 ${undo.blocks.size} 个方块", NamedTextColor.GREEN)
                    )
                )
            },
            onFailure = { failure ->
                operationsInProgress.remove(player.uniqueId)
                plugin.logger.log(Level.SEVERE, "Block sweep undo failed", failure)
                result.complete(
                    OperationResult.failed(Component.text("方块撤销中途失败", NamedTextColor.RED))
                )
            }
        )
        return result
    }

    private fun runRegionTasks(
        world: World,
        tasks: List<RegionBlockTask>,
        index: Int = 0,
        onGroupApplied: (List<StoredBlock>) -> Unit,
        onDone: () -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
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
                    onGroupApplied(task.action())
                    runRegionTasks(world, tasks, index + 1, onGroupApplied, onDone, onFailure)
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
        player.scheduler.execute(
            plugin,
            Runnable { action(player) },
            Runnable(retired),
            0L
        )
    }

    private data class RegionBlockTask(
        val position: BlockPosition,
        val action: () -> List<StoredBlock>
    )

    private companion object {
        const val SAMPLE_SPACING = 0.5
        const val TARGET_MAX_DISTANCE = 96.0
        const val TARGET_HIT_RADIUS = 1.5
    }
}
