package work.art1st.mineRails.bukkit.command

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.mineRails.basic.controller.ManualController
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.config.model.CarDefinition
import work.art1st.mineRails.bukkit.extension.train.CarExtension
import work.art1st.mineRails.bukkit.extension.train.renderer
import work.art1st.mineRails.bukkit.extension.renderChunkManager
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.util.annotation.Heavy
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.physics.train.CoreBogie
import work.art1st.mineRails.core.physics.train.CoreCar
import work.art1st.mineRails.core.physics.train.CoreTrain
import work.art1st.mineRails.core.physics.CoreWorld
import java.util.UUID

object TrainCommand {
    /** 玩家 UUID -> 手动控制器 */
    private val playerControllers = mutableMapOf<UUID, ManualController>()


    fun onCommand(sender: CommandSender, args: List<String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage("用法: /mr train <create|start|stop|throttle|reverse|info|list|delete>")
            return true
        }

        when (args[0].lowercase()) {
            "create" -> return handleCreate(sender, args.drop(1))
            "start" -> return handleStart(sender, args.drop(1))
            "stop" -> return handleStop(sender)
            "throttle" -> return handleThrottle(sender, args.drop(1))
            "reverse" -> return handleReverse(sender)
            "info" -> return handleInfo(sender)
            "list" -> return handleList(sender)
            "delete" -> return handleDelete(sender, args.drop(1))
            "sit" -> return handleSit(sender)
            "stand" -> return handleStand(sender)
            else -> sender.sendMessage("未知子命令: ${args[0]}")
        }
        return true
    }

    @OptIn(Heavy::class)
    private fun handleCreate(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }
        val world = getPlayerWorld(sender) ?: return true
        val coreWorld = world as? CoreWorld ?: run {
            sender.sendMessage("错误: 无法获取 CoreWorld")
            return true
        }

        val playerPos = Vector3d(sender.location.x, sender.location.y, sender.location.z)
        val nearestRail = coreWorld.getNearestRail(playerPos)
        if (nearestRail == null) {
            sender.sendMessage("错误: 世界中没有轨道，请先使用 /mr rail create 创建轨道")
            return true
        }

        // 参数: /mr train create <train_type>
        val trainTypeName = args.firstOrNull() ?: "s100"

        val modelManager = BukkitPlugin.INSTANCE.modelManager
            ?: run {
                sender.sendMessage("错误: ModelManager 未初始化")
                return true
            }

        // 查找列车定义
        val trainDef = modelManager.getTrain(trainTypeName)
        if (trainDef == null) {
            sender.sendMessage("错误: 未知列车类型 '$trainTypeName'")
            val availablePkgNames = modelManager.trainPackages.keys
            val availableLegacyNames = modelManager.trainLoader.getAllNames()
            sender.sendMessage("可用类型: ${(availablePkgNames + availableLegacyNames).joinToString(", ")}")
            return true
        }

        val carDefs = trainDef.composition.map { carModelName ->
            modelManager.getCar(carModelName)
                ?: run {
                    sender.sendMessage("错误: 未知车厢模型 '$carModelName'")
                    return true
                }
        }
        if (carDefs.isEmpty()) {
            sender.sendMessage("错误: 列车类型 '$trainTypeName' 没有车厢编组")
            return true
        }
        val requiredBehindHead = estimateTrainLengthBehindHead(carDefs)

        val railViewAB = nearestRail.getView(work.art1st.mineRails.core.api.physics.rail.ViewDirection.AB)
        val nearestPoint = railViewAB.curve.getNearestPoint(playerPos)
        val rawHeadAB = RailLocationWithFacing(railViewAB, nearestPoint.arcLength)
        val headLocation = findPlaceableHeadLocation(rawHeadAB, requiredBehindHead, playerPos)
            ?: run {
                sender.sendMessage("错误: 附近轨道长度不足，无法放置 ${"%.1f".format(requiredBehindHead)}m 的列车")
                sender.sendMessage("请站在更靠轨道中段的位置，或先把斜坡与前后轨道连接起来。")
                return true
            }
        val firstHeadBogieOffset = headBogieToEndPointOffset(carDefs.first())
        val initialBogieLocation = headLocation.forward(-firstHeadBogieOffset)
            ?: run {
                sender.sendMessage("错误: 无法初始化车头转向架位置")
                return true
            }

        val cars = mutableListOf<CoreCar>()

        for ((i, carModelName) in trainDef.composition.withIndex()) {
            val carDef = carDefs[i]

            // 初始时所有转向架先放在一个已验证可用的位置；随后 setLocation() 会按整列车几何重新排布。
            // 这样避免在轨道末端/斜坡底部创建列车时，临时向前摆放 bogie 导致误判或越界。
            val bogieA = CoreBogie.create(initialBogieLocation)
            val bogieB = CoreBogie.create(initialBogieLocation)

            val car = CoreCar.create(
                id = "${sender.name}-car-$i",
                bogieA = bogieA,
                bogieB = bogieB,
                length = carDef.length,
                floorHeight = carDef.floorHeight,
                connectorLengthAtA = carDef.connectorLengthA,
                connectorLengthAtB = carDef.connectorLengthB,
                bogieAOffset = carDef.bogieAOffset,
                bogieBOffset = carDef.bogieBOffset,
                world = coreWorld
            )
            cars.add(car)

            // 注册车厢渲染扩展
            val carExtension = CarExtension()
            car.registerExtensionData(BukkitPlugin.INSTANCE, carExtension)
            carExtension.initialize(car, emptyMap())
            carExtension.carModelName = carModelName
        }

        val trainId = "train-${UUID.randomUUID().toString().take(8)}"
        var createdTrain: CoreTrain? = null
        val train = try {
            val newTrain = CoreTrain.create(
                id = trainId,
                cars = cars,
                world = coreWorld
            )
            createdTrain = newTrain
            newTrain.setLocation(headLocation)
            newTrain
        } catch (e: Exception) {
            createdTrain?.let { failedTrain ->
                failedTrain.destroy()
                coreWorld.removeTrain(failedTrain)
            }
            sender.sendMessage("错误: 创建列车失败: ${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}")
            sender.sendMessage("提示: 斜坡短轨道需要先连接到足够长的前后轨道，否则整列车无法排布。")
            cars.forEach { it.destroy() }
            return true
        }
        train.commit()
        coreWorld.renderChunkManager.render()
        // Force model build for the creating player immediately
        cars.forEach { it.renderer.sendTo(sender) }

        sender.sendMessage("创建列车成功: ${train.id} (类型: ${trainDef.key})")
        sender.sendMessage("车厢数量: ${cars.size}")
        sender.sendMessage("列车头部位置: ${formatVector(train.head.location.position)}")
        sender.sendMessage("列车尾部位置: ${formatVector(train.tail.location.position)}")
        cars.forEachIndexed { index, car ->
            sender.sendMessage(
                "车厢 ${index + 1}: model=${trainDef.composition[index]}, " +
                    "center=${formatVector(car.position)}, " +
                    "bogieA=${formatVector(car.bogieA.position)}, bogieB=${formatVector(car.bogieB.position)}"
            )
        }
        return true
    }

    private fun formatVector(vector: Vector3dc): String =
        "(%.2f, %.2f, %.2f)".format(vector.x(), vector.y(), vector.z())

    private fun estimateTrainLengthBehindHead(carDefs: List<CarDefinition>): Double {
        if (carDefs.isEmpty()) return 0.0
        val carLengths = carDefs.sumOf { it.length }
        val couplers = carDefs.zipWithNext().sumOf { (front, back) ->
            front.connectorLengthB + back.connectorLengthA
        }
        return carLengths + couplers
    }

    private fun headBogieToEndPointOffset(carDef: CarDefinition): Double {
        val bogieAOffset = carDef.bogieAOffset ?: (carDef.length * 0.3)
        return carDef.length / 2.0 - bogieAOffset
    }

    private fun findPlaceableHeadLocation(
        rawHeadAB: RailLocationWithFacing,
        requiredBehindHead: Double,
        playerPos: Vector3dc
    ): RailLocationWithFacing? {
        val candidates = listOf(rawHeadAB, rawHeadAB.reversed).mapNotNull { raw ->
            shiftHeadForwardUntilTailFits(raw, requiredBehindHead)
        }
        return candidates.minByOrNull { it.position.distance(playerPos) }
    }

    private fun shiftHeadForwardUntilTailFits(
        rawHead: RailLocationWithFacing,
        requiredBehindHead: Double
    ): RailLocationWithFacing? {
        if (rawHead.forward(-requiredBehindHead) != null) return rawHead

        var hi = 1.0
        var hiLoc: RailLocationWithFacing? = null
        val maxShift = requiredBehindHead * 2.0 + 1.0
        while (hi <= maxShift) {
            val shifted = rawHead.forward(hi) ?: return null
            if (shifted.forward(-requiredBehindHead) != null) {
                hiLoc = shifted
                break
            }
            hi *= 2.0
        }
        hiLoc ?: return null

        var lo = 0.0
        repeat(40) {
            val mid = (lo + hi) / 2.0
            val shifted = rawHead.forward(mid)
            if (shifted != null && shifted.forward(-requiredBehindHead) != null) {
                hi = mid
                hiLoc = shifted
            } else {
                lo = mid
            }
        }
        return hiLoc
    }

    private fun handleStart(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }
        if (playerControllers.containsKey(sender.uniqueId)) {
            sender.sendMessage("已有控制器运行中，先用 /mr train stop 停止")
            return true
        }

        val world = getPlayerWorld(sender) ?: return true
        val coreWorld = world as? CoreWorld ?: run {
            sender.sendMessage("错误: 无法获取 CoreWorld")
            return true
        }

        val train = findNearestTrain(sender, coreWorld) ?: run {
            sender.sendMessage("错误: 附近没有列车")
            return true
        }

        val controller = ManualController(train, coreWorld)
        train.setController(controller)
        playerControllers[sender.uniqueId] = controller

        val throttle = args.firstOrNull()?.toDoubleOrNull() ?: 0.0
        if (throttle != 0.0) {
            controller.setThrottle(throttle.coerceIn(-1.0, 1.0))
        }

        sender.sendMessage("控制器已连接至列车: ${train.id}")
        sender.sendMessage("使用 /mr train throttle <0~1> 加速")
        sender.sendMessage("使用 /mr train stop 断开连接")
        return true
    }

    private fun handleStop(sender: CommandSender): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }
        val controller = playerControllers.remove(sender.uniqueId)
        if (controller == null) {
            sender.sendMessage("当前没有连接的控制器")
            return true
        }

        (controller.controlledTrain as? CoreTrain)?.setController(null)
        sender.sendMessage("控制器已断开连接")
        return true
    }

    /**
     * 设置加速手柄。
     */
    private fun handleThrottle(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }
        val controller = playerControllers[sender.uniqueId]
        if (controller == null) {
            sender.sendMessage("当前没有连接的控制器，先用 /mr train start")
            return true
        }

        val throttle = args.firstOrNull()?.toDoubleOrNull()
        if (throttle == null) {
            sender.sendMessage("用法: /mr train throttle <-1~1>")
            sender.sendMessage("  正值=加速，负值=制动，0=惰行")
            sender.sendMessage("当前手柄: ${"%.2f".format(controller.throttle)}")
            sender.sendMessage("当前速度: ${"%.2f".format(controller.speed)} m/s")
            return true
        }

        controller.setThrottle(throttle.coerceIn(-1.0, 1.0))
        sender.sendMessage("手柄设置为 ${"%.2f".format(controller.throttle)}，目标速度 ${"%.2f".format(controller.targetSpeed)} m/s")
        return true
    }

    private fun handleReverse(sender: CommandSender): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }
        val controller = playerControllers[sender.uniqueId]
        if (controller == null) {
            sender.sendMessage("当前没有连接的控制器，先用 /mr train start")
            return true
        }

        controller.reverse()
        sender.sendMessage("列车已换向")
        return true
    }

    private fun handleDelete(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }
        val world = getPlayerWorld(sender) ?: return true
        val coreWorld = world as? CoreWorld ?: run {
            sender.sendMessage("错误: 无法获取 CoreWorld")
            return true
        }

        val train = args.firstOrNull()?.let { coreWorld.getTrain(it) as? CoreTrain }
            ?: findNearestTrain(sender, coreWorld)
            ?: run {
                sender.sendMessage("错误: 附近没有列车")
                return true
            }

        playerControllers.entries.removeIf { it.value.controlledTrain == train }
        train.destroy()
        coreWorld.removeTrain(train)
        sender.sendMessage("已删除列车: ${train.id}")
        return true
    }

    private fun handleSit(sender: CommandSender): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }
        val world = getPlayerWorld(sender) ?: return true
        val coreWorld = world as? CoreWorld ?: run {
            sender.sendMessage("错误: 无法获取 CoreWorld")
            return true
        }

        val train = findNearestTrain(sender, coreWorld) ?: run {
            sender.sendMessage("错误: 附近没有列车")
            return true
        }
        sender.sendMessage("座位系统待实现。请直接右键列车座位。")
        return true
    }

    private fun handleStand(sender: CommandSender): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }
        sender.sendMessage("已站立")
        return true
    }

    private fun handleInfo(sender: CommandSender): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }
        val world = getPlayerWorld(sender) ?: return true
        val coreWorld = world as? CoreWorld ?: run {
            sender.sendMessage("错误: 无法获取 CoreWorld")
            return true
        }

        val playerPos = Vector3d(sender.location.x, sender.location.y, sender.location.z)
        val nearestRail = coreWorld.getNearestRail(playerPos)

        sender.sendMessage("=== 附近信息 ===")
        if (nearestRail != null) {
            sender.sendMessage("最近轨道: ${nearestRail.id}")
            sender.sendMessage("轨道长度: ${"%.2f".format(nearestRail.curve.totalArcLength)} 米")
        } else {
            sender.sendMessage("附近没有轨道")
        }

        val nearestTrain = findNearestTrain(sender, coreWorld)
        val controller = playerControllers[sender.uniqueId]
        val train = controller?.controlledTrain ?: nearestTrain
        if (train != null) {
            sender.sendMessage("=== 列车状态 ===")
            sender.sendMessage("列车: ${train.id}")
            if (controller != null) {
                sender.sendMessage("速度: ${"%.2f".format(controller.speed)} m/s")
                sender.sendMessage("目标速度: ${"%.2f".format(controller.targetSpeed)} m/s")
                sender.sendMessage("手柄: ${"%.2f".format(controller.throttle)}")
            } else {
                sender.sendMessage("控制器: 未连接")
            }
            sender.sendMessage("脱轨: ${if (train.isDerailed) "是" else "否"}")
            (train as? CoreTrain)?.let { coreTrain ->
                sender.sendMessage("车头: rail=${coreTrain.head.location.rail.id} s=${"%.2f".format(coreTrain.head.location.arcLength)} pos=${formatVector(coreTrain.head.location.position)}")
                sender.sendMessage("当前 rail 前向连接数: ${coreTrain.head.location.railView.nextRails.size}")
            }
        }

        return true
    }

    private fun handleList(sender: CommandSender): Boolean {
        val world = BukkitPlugin.INSTANCE.worldManager.railWorlds.values.firstOrNull() as? CoreWorld ?: run {
            sender.sendMessage("错误: 无法获取 CoreWorld")
            return true
        }

        sender.sendMessage("=== 列车列表 ===")
        for (train in world.allTrains) {
            sender.sendMessage("  ${train.id} - ${train.length} 节车厢")
        }
        return true
    }

    private fun findNearestTrain(sender: Player, coreWorld: CoreWorld): CoreTrain? {
        val playerPos = Vector3d(sender.location.x, sender.location.y, sender.location.z)
        return coreWorld.allTrains.minByOrNull { train ->
            train.cars.minOfOrNull { car ->
                car.position.distance(playerPos)
            } ?: Double.MAX_VALUE
        }
    }

    private fun getPlayerWorld(player: Player): World? {
        val bukkitWorld = player.world
        val worldManager = BukkitPlugin.INSTANCE.worldManager
        return worldManager.railWorlds[bukkitWorld]
    }
}
