package work.art1st.mineRails.bukkit.command

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.behavior.TrainBehaviorCallResult
import work.art1st.mineRails.bukkit.config.model.CarControlConfig
import work.art1st.mineRails.bukkit.debug.AbnormalTrainMarker
import work.art1st.mineRails.bukkit.config.model.CarDefinition
import work.art1st.mineRails.bukkit.config.model.TrainControlConfig
import work.art1st.mineRails.bukkit.extension.train.CarExtension
import work.art1st.mineRails.bukkit.extension.train.TrainExtension
import work.art1st.mineRails.bukkit.control.RealisticManualController
import work.art1st.mineRails.bukkit.control.TrainKeyItem
import work.art1st.mineRails.bukkit.extension.train.carRendererOrNull
import work.art1st.mineRails.bukkit.extension.train.renderer
import work.art1st.mineRails.bukkit.render.model.SeatModel
import work.art1st.mineRails.bukkit.util.BukkitAsyncTasks
import work.art1st.mineRails.bukkit.util.HeavyRailQueries
import work.art1st.mineRails.bukkit.util.PlayerOperationGate
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.physics.train.CoreBogie
import work.art1st.mineRails.core.physics.train.CoreCar
import work.art1st.mineRails.core.physics.train.CoreTrain
import work.art1st.mineRails.core.physics.CoreWorld
import java.util.UUID

object TrainCommand {
    /** 玩家 UUID -> 手动控制器 */
    private val playerControllers = mutableMapOf<UUID, RealisticManualController>()
    private val heavyOperationGate = PlayerOperationGate()

    init {
        SeatModel.SeatInteractionListener.addDriverSeatVacatedHandler { player, train ->
            val controller = playerControllers[player.uniqueId] ?: return@addDriverSeatVacatedHandler
            if (controller.controlledTrain === train) {
                controller.coast()
            }
        }
    }

    fun onCommand(sender: CommandSender, args: List<String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage("用法: /mr train <create|key|anim|action|behavior|info|list|delete|sit|stand>")
            return true
        }

        when (args[0].lowercase()) {
            "create" -> return handleCreate(sender, args.drop(1))
            "key" -> return handleKey(sender)
            "anim", "animation" -> return handleAnim(sender, args.drop(1))
            "action" -> return handleAction(sender, args.drop(1))
            "behavior" -> return handleBehavior(sender, args.drop(1))
            "info" -> return handleInfo(sender)
            "list" -> return handleList(sender)
            "delete" -> return handleDelete(sender, args.drop(1))
            "sit" -> return handleSit(sender)
            "stand" -> return handleStand(sender)
            else -> sender.sendMessage("未知子命令: ${args[0]}")
        }
        return true
    }

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

        val pendingOperation = heavyOperationGate.tryStart(sender.uniqueId, "列车创建轨道查找")
        if (pendingOperation != null) {
            sender.sendMessage("正在执行$pendingOperation，请稍候")
            return true
        }

        val playerPos = Vector3d(sender.location.x, sender.location.y, sender.location.z)
        val worldId = sender.world.uid
        val railSnapshot = coreWorld.allRails.toList()
        if (railSnapshot.isEmpty()) {
            heavyOperationGate.finish(sender.uniqueId)
            sender.sendMessage("错误: 世界中没有轨道，请先使用 /mr rail create 创建轨道")
            return true
        }

        val trainTypeName = args.firstOrNull() ?: "s100"
        val modelManager = BukkitPlugin.INSTANCE.modelManager

        val trainDef = modelManager.getTrain(trainTypeName)
        if (trainDef == null) {
            heavyOperationGate.finish(sender.uniqueId)
            sender.sendMessage("错误: 未知列车类型 '$trainTypeName'")
            val availablePkgNames = modelManager.trainPackages.keys
            val availableLegacyNames = modelManager.trainLoader.getAllNames()
            sender.sendMessage("可用类型: ${(availablePkgNames + availableLegacyNames).joinToString(", ")}")
            return true
        }

        val carDefs = trainDef.composition.map { carModelName ->
            modelManager.getCar(carModelName)
                ?: run {
                    heavyOperationGate.finish(sender.uniqueId)
                    sender.sendMessage("错误: 未知车厢模型 '$carModelName'")
                    return true
                }
        }
        if (carDefs.isEmpty()) {
            heavyOperationGate.finish(sender.uniqueId)
            sender.sendMessage("错误: 列车类型 '$trainTypeName' 没有车厢编组")
            return true
        }

        sender.sendMessage("正在查找可放置轨道，请稍候...")
        BukkitAsyncTasks.supply(
            plugin = BukkitPlugin.INSTANCE,
            operation = "TrainCommand.handleCreate",
            supplier = { HeavyRailQueries.findNearestRail(railSnapshot, playerPos) },
            onSuccess = { nearestRail ->
                heavyOperationGate.finish(sender.uniqueId)
                val livePlayer = BukkitPlugin.INSTANCE.server.getPlayer(sender.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                val liveWorld = getPlayerWorld(livePlayer) as? CoreWorld ?: run {
                    livePlayer.sendMessage("错误: 无法获取 CoreWorld")
                    return@supply
                }
                completeCreateOnMainThread(livePlayer, liveWorld, trainTypeName, trainDef, carDefs, playerPos, nearestRail)
            },
            onFailure = { error ->
                heavyOperationGate.finish(sender.uniqueId)
                BukkitPlugin.INSTANCE.logger.warning(
                    "TrainCommand create lookup failed for player ${sender.name}: ${error::class.simpleName}: ${error.message ?: ""}"
                )
                BukkitPlugin.INSTANCE.server.getPlayer(sender.uniqueId)?.sendMessage("错误: 轨道查找失败，请重试")
            }
        )
        return true
    }

    private fun handleKey(sender: CommandSender): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }
        sender.inventory.addItem(TrainKeyItem.create())
        sender.sendMessage("已给予通用列车钥匙")
        return true
    }

    private fun completeCreateOnMainThread(
        sender: Player,
        coreWorld: CoreWorld,
        trainTypeName: String,
        trainDef: work.art1st.mineRails.bukkit.config.model.TrainDefinition,
        carDefs: List<CarDefinition>,
        playerPos: Vector3dc,
        nearestRail: HeavyRailQueries.NearestRailMatch?
    ) {
        val rail = nearestRail?.let { coreWorld.getRail(it.railId) }
        if (rail == null) {
            sender.sendMessage("错误: 世界中没有轨道，请先使用 /mr rail create 创建轨道")
            return
        }

        val requiredBehindHead = estimateTrainLengthBehindHead(carDefs)
        val railViewAB = rail.getView(work.art1st.mineRails.core.api.physics.rail.ViewDirection.AB)
        val rawHeadAB = RailLocationWithFacing(railViewAB, nearestRail.arcLength)
        val headLocation = findPlaceableHeadLocation(rawHeadAB, requiredBehindHead, playerPos)
            ?: run {
                sender.sendMessage("错误: 附近轨道长度不足，无法放置 ${"%.1f".format(requiredBehindHead)}m 的列车")
                sender.sendMessage("请站在更靠轨道中段的位置，或先把斜坡与前后轨道连接起来。")
                return
            }
        val blockingTrainId = findBlockingTrainForPlacement(headLocation, requiredBehindHead)
        if (blockingTrainId != null) {
            sender.sendMessage("错误: 放置区域已有列车 $blockingTrainId，请先移走或删除后再创建")
            return
        }
        val firstHeadBogieOffset = headBogieToEndPointOffset(carDefs.first())
        val initialBogieLocation = headLocation.forward(-firstHeadBogieOffset)
            ?: run {
                sender.sendMessage("错误: 无法初始化车头转向架位置")
                return
            }

        val cars = mutableListOf<CoreCar>()
        for ((i, carModelName) in trainDef.composition.withIndex()) {
            val carDef = carDefs[i]
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
            return
        }
        val trainExtension = TrainExtension()
        train.registerExtensionData(BukkitPlugin.INSTANCE, trainExtension)
        trainExtension.initialize(train, emptyMap())

        train.commit()
        cars.forEach { it.renderer.sendTo(sender) }

        sender.sendMessage("创建列车成功: ${train.id} (类型: $trainTypeName)")
        sender.sendMessage("手持通用列车钥匙坐在驾驶座上驾驶，可用 /mr train key 获取。")
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
    }

    private fun formatVector(vector: Vector3dc): String =
        "(%.2f, %.2f, %.2f)".format(vector.x(), vector.y(), vector.z())

    private fun findBlockingTrainForPlacement(headLocation: RailLocationWithFacing, requiredBehindHead: Double): String? {
        val margin = 5.0
        val nearbyEnds = headLocation.peekTrains(-(requiredBehindHead + margin)) + headLocation.peekTrains(margin)
        return nearbyEnds.firstNotNullOfOrNull { end ->
            end.trainOrNull?.id ?: run {
                end.destroy()
                null
            }
        }
    }

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

        val train = findDrivenTrain(sender, coreWorld) ?: run {
            sender.sendMessage("错误: 请先坐在驾驶座上再连接控制器")
            return true
        }

        val (controlConfig, carControls) = try {
            resolveControlProfile(train)
        } catch (e: IllegalArgumentException) {
            sender.sendMessage("错误: ${e.message}")
            return true
        }
        val controller = RealisticManualController(train, coreWorld, controlConfig, carControls)
        train.setController(controller)
        playerControllers[sender.uniqueId] = controller

        val initialHandle = args.firstOrNull()
        if (initialHandle != null) {
            val throttle = initialHandle.toDoubleOrNull()
            if (throttle != null) {
                controller.setThrottle(throttle)
            } else if (!controller.setHandle(initialHandle)) {
                sender.sendMessage("未知手柄档位: $initialHandle")
                sender.sendMessage("可用档位: ${availableHandles(controlConfig)}")
            }
        }

        sender.sendMessage("控制器已连接至列车: ${train.id}")
        sender.sendMessage("使用 /mr train handle <P1..P5|N|B1..B7|EB> 设置手柄")
        sender.sendMessage("使用 /mr train throttle <-1~1> 兼容旧式比例控制")
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

    private fun handleHandle(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }
        val controller = playerControllers[sender.uniqueId]
        if (controller == null) {
            sender.sendMessage("当前没有连接的控制器，先用 /mr train start")
            return true
        }
        if (!isDrivingControlledTrain(sender, controller)) {
            sender.sendMessage("手柄无效：请坐在当前列车的驾驶座上")
            return true
        }

        val handle = args.firstOrNull()
        if (handle == null) {
            sender.sendMessage("用法: /mr train handle <P1..P5|N|B1..B7|EB>")
            sender.sendMessage("当前手柄: ${controller.handle}")
            sender.sendMessage("当前速度: ${"%.2f".format(controller.speed)} m/s")
            sender.sendMessage("当前加速度: ${"%.3f".format(controller.acceleration)} m/s²")
            return true
        }

        if (!controller.setHandle(handle)) {
            sender.sendMessage("未知手柄档位: $handle")
            return true
        }
        sender.sendMessage("手柄设置为 ${controller.handle}")
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

        if (!isDrivingControlledTrain(sender, controller)) {
            sender.sendMessage("手柄无效：请坐在当前列车的驾驶座上")
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

        if (!isDrivingControlledTrain(sender, controller)) {
            sender.sendMessage("换向无效：请坐在当前列车的驾驶座上")
            return true
        }

        controller.reverse()
        sender.sendMessage("列车已换向")
        return true
    }

    private fun handleAnim(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }
        val animationArg = args.firstOrNull()
        if (animationArg == null) {
            sender.sendMessage("用法: /mr train anim <animationName> [sceneName]")
            sender.sendMessage("  也可使用: /mr train anim <animationName>:<sceneName>")
            return true
        }

        val (animationName, inlineScene) = parseAnimationTarget(animationArg)
        val sceneName = args.getOrNull(1) ?: inlineScene
        val world = getPlayerWorld(sender) ?: return true
        val coreWorld = world as? CoreWorld ?: run {
            sender.sendMessage("错误: 无法获取 CoreWorld")
            return true
        }
        val train = (playerControllers[sender.uniqueId]?.controlledTrain as? CoreTrain)
            ?: findNearestTrain(sender, coreWorld)
            ?: run {
                sender.sendMessage("错误: 附近没有列车")
                return true
            }

        val applied = if (sceneName == null) {
            train.renderer.applyAnimation(animationName)
        } else {
            train.renderer.applyAnimation(animationName, sceneName)
        }
        if (applied) {
            sender.sendMessage(
                if (sceneName == null) {
                    "已触发列车动画: $animationName"
                } else {
                    "已触发列车动画: $animationName -> $sceneName"
                }
            )
        } else {
            sender.sendMessage(
                if (sceneName == null) {
                    "没有部件响应动画: $animationName"
                } else {
                    "没有部件响应动画: $animationName -> $sceneName"
                }
            )
        }
        return true
    }

    private fun parseAnimationTarget(target: String): Pair<String, String?> {
        val separator = target.indexOf(':')
        if (separator <= 0 || separator == target.lastIndex) return target to null
        return target.substring(0, separator) to target.substring(separator + 1)
    }

    private fun handleAction(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }
        val actionName = args.firstOrNull()
        if (actionName == null) {
            sender.sendMessage("用法: /mr train action <actionName>")
            return true
        }

        val world = getPlayerWorld(sender) ?: return true
        val coreWorld = world as? CoreWorld ?: run {
            sender.sendMessage("错误: 无法获取 CoreWorld")
            return true
        }
        val train = (playerControllers[sender.uniqueId]?.controlledTrain as? CoreTrain)
            ?: findNearestTrain(sender, coreWorld)
            ?: run {
                sender.sendMessage("错误: 附近没有列车")
                return true
            }

        val applied = train.renderer.applyAction(actionName)
        if (applied) {
            sender.sendMessage("已触发列车动作: $actionName")
        } else {
            sender.sendMessage("没有可执行的列车动作: $actionName")
        }
        return true
    }

    private fun handleBehavior(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }
        val methodName = args.firstOrNull()
        if (methodName == null) {
            sender.sendMessage("用法: /mr train behavior <method> [args...]")
            sender.sendMessage("示例: /mr train behavior onStation 0 left")
            return true
        }

        val world = getPlayerWorld(sender) ?: return true
        val coreWorld = world as? CoreWorld ?: run {
            sender.sendMessage("错误: 无法获取 CoreWorld")
            return true
        }
        val train = (playerControllers[sender.uniqueId]?.controlledTrain as? CoreTrain)
            ?: findNearestTrain(sender, coreWorld)
            ?: run {
                sender.sendMessage("错误: 附近没有列车")
                return true
            }

        when (val result = train.renderer.callBehavior(methodName, args.drop(1).map(::parseBehaviorArg))) {
            is TrainBehaviorCallResult.Success -> {
                val suffix = result.returnValue?.let { " -> $it" } ?: ""
                sender.sendMessage("已调用列车行为: ${result.method}$suffix")
            }
            is TrainBehaviorCallResult.Failure -> {
                sender.sendMessage("列车行为调用失败: ${result.message}")
            }
        }
        return true
    }

    private fun parseBehaviorArg(raw: String): Any? {
        return when (raw.lowercase()) {
            "true" -> true
            "false" -> false
            "null" -> null
            else -> raw.toIntOrNull() ?: raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: raw
        }
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
        BukkitPlugin.INSTANCE.trainControlManager.releaseTrain(train)
        AbnormalTrainMarker.clearTrain(sender.world, train.id)
        train.destroy()
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

        val pendingOperation = heavyOperationGate.tryStart(sender.uniqueId, "附近轨道查询")
        if (pendingOperation != null) {
            sender.sendMessage("正在执行$pendingOperation，请稍候")
            return true
        }

        val playerPos = Vector3d(sender.location.x, sender.location.y, sender.location.z)
        val worldId = sender.world.uid
        val railSnapshot = coreWorld.allRails.toList()
        sender.sendMessage("正在查询附近轨道，请稍候...")
        BukkitAsyncTasks.supply(
            plugin = BukkitPlugin.INSTANCE,
            operation = "TrainCommand.handleInfo",
            supplier = { HeavyRailQueries.findNearestRail(railSnapshot, playerPos) },
            onSuccess = { nearestRail ->
                heavyOperationGate.finish(sender.uniqueId)
                val livePlayer = BukkitPlugin.INSTANCE.server.getPlayer(sender.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                val liveWorld = getPlayerWorld(livePlayer) as? CoreWorld ?: run {
                    livePlayer.sendMessage("错误: 无法获取 CoreWorld")
                    return@supply
                }
                sendInfoOnMainThread(livePlayer, liveWorld, nearestRail)
            },
            onFailure = { error ->
                heavyOperationGate.finish(sender.uniqueId)
                BukkitPlugin.INSTANCE.logger.warning(
                    "TrainCommand info lookup failed for player ${sender.name}: ${error::class.simpleName}: ${error.message ?: ""}"
                )
                BukkitPlugin.INSTANCE.server.getPlayer(sender.uniqueId)?.sendMessage("错误: 附近轨道查询失败，请重试")
            }
        )
        return true
    }

    private fun sendInfoOnMainThread(
        sender: Player,
        coreWorld: CoreWorld,
        nearestRail: HeavyRailQueries.NearestRailMatch?
    ) {
        sender.sendMessage("=== 附近信息 ===")
        val rail = nearestRail?.let { coreWorld.getRail(it.railId) }
        if (rail != null) {
            sender.sendMessage("最近轨道: ${rail.id}")
            sender.sendMessage("轨道长度: ${"%.2f".format(rail.curve.totalArcLength)} 米")
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
                sender.sendMessage("加速度: ${"%.3f".format(controller.acceleration)} m/s²")
                sender.sendMessage("手柄: ${controller.handle} (${"%.2f".format(controller.throttle)})")
                sender.sendMessage("制动建立: ${"%.2f".format(controller.brakeScale)}")
                sender.sendMessage(
                    "力: 牵引=${"%.0f".format(controller.tractionForce)}N " +
                        "制动=${"%.0f".format(controller.brakeForce)}N " +
                        "阻力=${"%.0f".format(controller.resistanceForce)}N " +
                        "坡度=${"%.0f".format(controller.gradeForce)}N"
                )
            } else {
                sender.sendMessage("控制器: 未连接")
            }
            sender.sendMessage("脱轨: ${if (train.isDerailed) "是" else "否"}")
            (train as? CoreTrain)?.let { coreTrain ->
                sender.sendMessage("车头: rail=${coreTrain.head.location.rail.id} s=${"%.2f".format(coreTrain.head.location.arcLength)} pos=${formatVector(coreTrain.head.location.position)}")
                sender.sendMessage("当前 rail 前向连接数: ${coreTrain.head.location.railView.nextRails.size}")
            }
        }
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

    private fun resolveControlProfile(train: CoreTrain): Pair<TrainControlConfig, List<CarControlConfig>> {
        val modelManager = BukkitPlugin.INSTANCE.modelManager
        val carModelNames = train.cars.mapIndexed { index, car ->
            car.carRendererOrNull?.carModelName
                ?: throw IllegalArgumentException("列车 ${train.id} 的第 ${index + 1} 节车厢缺少 carModelName，无法解析控制配置")
        }
        val packageName = carModelNames.firstNotNullOfOrNull { modelName ->
            modelManager.getCar(modelName)?.packageName
        } ?: throw IllegalArgumentException("列车 ${train.id} 的车厢模型未关联到任何 train package")
        val trainControl = modelManager.getTrainControl(packageName)
        val carControls = carModelNames.mapIndexed { index, modelName ->
            modelManager.getCar(modelName)?.control
                ?: throw IllegalArgumentException("列车 ${train.id} 的第 ${index + 1} 节车厢模型 '$modelName' 没有控制配置")
        }
        return trainControl to carControls
    }

    private fun availableHandles(controlConfig: TrainControlConfig): String {
        return (controlConfig.powerNotches + listOf(RealisticManualController.NEUTRAL_HANDLE) + controlConfig.brakeNotches)
            .joinToString("|")
    }

    private fun findDrivenTrain(player: Player, coreWorld: CoreWorld): CoreTrain? {
        return coreWorld.allTrains.firstOrNull { train ->
            SeatModel.SeatInteractionListener.isPlayerDrivingTrain(player, train)
        }
    }

    fun controllerFor(player: Player): RealisticManualController? = playerControllers[player.uniqueId]

    fun controllerForKeyedDriver(player: Player): RealisticManualController? {
        val train = SeatModel.SeatInteractionListener.drivingTrainFor(player) as? CoreTrain ?: return null
        if (!TrainKeyItem.playerHasKey(player)) return null

        playerControllers[player.uniqueId]?.let { existing ->
            if (existing.controlledTrain === train) return existing
            existing.coast()
        }

        val existingTrainController = train.controller as? RealisticManualController
        val controller = existingTrainController ?: run {
            val (controlConfig, carControls) = try {
                resolveControlProfile(train)
            } catch (e: IllegalArgumentException) {
                BukkitPlugin.INSTANCE.logger.warning("Failed to resolve control profile for train ${train.id}: ${e.message}")
                return null
            }
            RealisticManualController(train, train.world, controlConfig, carControls).also { train.setController(it) }
        }
        playerControllers[player.uniqueId] = controller
        return controller
    }

    fun isDrivingControlledTrain(player: Player, controller: RealisticManualController): Boolean {
        val train = controller.controlledTrain as? CoreTrain ?: return false
        return SeatModel.SeatInteractionListener.isPlayerDrivingTrain(player, train)
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
