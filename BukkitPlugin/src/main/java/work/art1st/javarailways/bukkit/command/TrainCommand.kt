package work.art1st.javarailways.bukkit.command

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.simpleAtoWorldDataOrNull
import work.art1st.javarailways.basic.control.RealisticManualController
import work.art1st.javarailways.basic.basicWorldDataOrNull
import work.art1st.javarailways.basic.safety.TrainSpeedProfile
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.config.model.CarControlConfig
import work.art1st.javarailways.bukkit.debug.AbnormalTrainMarker
import work.art1st.javarailways.bukkit.config.model.CarDefinition
import work.art1st.javarailways.bukkit.config.model.TrainResourceVersion
import work.art1st.javarailways.bukkit.persistence.TrainPackageSnapshotStore
import work.art1st.javarailways.bukkit.persistence.CarModelSnapshotStore
import work.art1st.javarailways.bukkit.config.model.TrainControlConfig
import work.art1st.javarailways.bukkit.extension.train.CarExtension
import work.art1st.javarailways.bukkit.extension.train.TrainExtension
import work.art1st.javarailways.bukkit.control.TrainKeyItem
import work.art1st.javarailways.bukkit.extension.train.carRendererOrNull
import work.art1st.javarailways.bukkit.extension.train.renderer
import work.art1st.javarailways.bukkit.extension.train.rendererOrNull
import work.art1st.javarailways.bukkit.render.model.SeatModel
import work.art1st.javarailways.bukkit.util.BukkitAsyncTasks
import work.art1st.javarailways.bukkit.util.HeavyRailQueries
import work.art1st.javarailways.bukkit.util.PlayerOperationGate
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.train.CoreCar
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.core.physics.CoreWorld
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

object TrainCommand {
    /** 玩家 UUID -> 手动控制器 */
    private val playerControllers = ConcurrentHashMap<UUID, RealisticManualController>()
    private val heavyOperationGate = PlayerOperationGate()
    // Seat events revoke requests before their owner-side operation runs; no Bukkit entity reads on physics.
    private val pendingAtpAcknowledgements = ConcurrentHashMap<UUID, Any>()

    init {
        SeatModel.SeatInteractionListener.addDriverSeatVacatedHandler { player, train ->
            pendingAtpAcknowledgements.remove(player.uniqueId)
            val controller = playerControllers[player.uniqueId] ?: return@addDriverSeatVacatedHandler
            if (controller.controlledTrain === train) {
                controller.coast()
            }
        }
    }

    fun onCommand(sender: CommandSender, args: List<String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage("用法: /jr train <create|rename|key|anim|action|behavior|info|list|delete|sit|stand>")
            return true
        }

        when (args[0].lowercase()) {
            "create" -> return handleCreate(sender, args.drop(1))
            "rename" -> return handleRename(sender, args.drop(1))
            "key" -> return handleKey(sender)
            "anim", "animation" -> return handleAnim(sender, args.drop(1))
            "action" -> return handleAction(sender, args.drop(1))
            "behavior" -> return handleBehavior(sender, args.drop(1))
            "atp" -> return handleAtp(sender, args.drop(1))
            "info" -> return handleInfo(sender)
            "list" -> return handleList(sender)
            "delete" -> return handleDelete(sender, args.drop(1))
            "sit" -> return handleSit(sender)
            "stand" -> return handleStand(sender)
            else -> sender.sendMessage("未知子命令: ${args[0]}")
        }
        return true
    }

    internal fun revokePendingAtpAcknowledgement(playerId: UUID) {
        pendingAtpAcknowledgements.remove(playerId)
    }

    private fun handleAtp(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) { sender.sendMessage("此命令需要玩家执行"); return true }
        val world = getPlayerWorld(sender) ?: return true
        val train = findDrivenTrain(sender, world as? CoreWorld ?: return true) ?: run {
            sender.sendMessage("请先坐在驾驶座上"); return true
        }
        val runtime = world.simpleAtoWorldDataOrNull?.controlRuntime ?: return true
        when (args.firstOrNull()?.lowercase() ?: "status") {
            "status" -> sender.sendMessage(if (runtime.isAcknowledgementRequired(train)) "ATP 制动保持中" else "ATP 正常")
            "release" -> {
                val manager = BukkitPlugin.INSTANCE.trainControlManager
                val session = manager.sessionFor(sender)
                val controller = session?.controller ?: playerControllers[sender.uniqueId]
                if (controller == null || controller.controlledTrain !== train) {
                    sender.sendMessage("请先取得本列车的手动驾驶控制权")
                    return true
                }
                val playerId = sender.uniqueId
                val request = Any()
                pendingAtpAcknowledgements[playerId] = request
                reportControllerOperation(sender, (world as CoreWorld).physicsCoordinator.submitBetweenFrames {
                    try {
                        check(pendingAtpAcknowledgements[playerId] === request &&
                            world.getTrain(train.id) === train && train.controller === controller &&
                            (if (session != null) manager.sessionFor(train) === session && session.playerId == playerId
                             else playerControllers[playerId] === controller)) { "驾驶员或控制器已变化，请重新请求" }
                        if (runtime.acknowledge(train)) listOf("已请求解除 ATP；下一次安全检查通过且停车后解除")
                        else listOf("没有可解除的 ATP 干预")
                    } finally { pendingAtpAcknowledgements.remove(playerId, request) }
                })
            }
            else -> sender.sendMessage("用法: /jr train atp <status|release>")
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
            sender.sendMessage("错误: 世界中没有轨道，请先使用 /jr wand rail 获取 RailWand 并铺设轨道")
            return true
        }

        if (args.size < 2) {
            heavyOperationGate.finish(sender.uniqueId)
            sender.sendMessage("用法: /jr train create <type> <name>")
            return true
        }
        val trainTypeName = args[0]
        val trainName = args[1]
        if (trainName.isBlank() || trainName != trainName.trim() || trainName.any(Char::isISOControl)) {
            heavyOperationGate.finish(sender.uniqueId)
            sender.sendMessage("错误: 列车名必须是非空、无控制字符且首尾无空格的文本")
            return true
        }
        if (coreWorld.allTrains.any { it.name == trainName }) {
            heavyOperationGate.finish(sender.uniqueId)
            sender.sendMessage("错误: 当前世界已存在列车名 '$trainName'")
            return true
        }
        val modelManager = BukkitPlugin.INSTANCE.modelManager

        if (modelManager.getTrain(trainTypeName) == null) {
            heavyOperationGate.finish(sender.uniqueId)
            sender.sendMessage("错误: 未知列车类型 '$trainTypeName'")
            sender.sendMessage("可用类型: ${modelManager.trainPackages.keys.joinToString(", ")}")
            return true
        }

        val resourceVersion = try {
            modelManager.resourceVersion(trainTypeName)
        } catch (failure: Exception) {
            heavyOperationGate.finish(sender.uniqueId)
            sender.sendMessage("错误: 列车资源无效: ${failure.message}")
            return true
        }
        val carDefs = resourceVersion.composition.map { resourceVersion.models.getValue(it).car }
        if (carDefs.isEmpty()) {
            heavyOperationGate.finish(sender.uniqueId)
            sender.sendMessage("错误: 列车类型 '$trainTypeName' 没有车厢编组")
            return true
        }

        sender.sendMessage("正在查找可放置轨道，请稍候...")
        BukkitAsyncTasks.supply(
            plugin = BukkitPlugin.INSTANCE,
            completionEntity = sender,
            operation = "TrainCommand.handleCreate",
            supplier = {
                TrainPackageSnapshotStore.save(resourceVersion.packageSnapshot)
                resourceVersion.carPackages.values.distinctBy { it.hash }.forEach(TrainPackageSnapshotStore::save)
                resourceVersion.models.values.forEach(CarModelSnapshotStore::save)
                HeavyRailQueries.findNearestRail(railSnapshot, playerPos)
            },
            onSuccess = { nearestRail ->
                heavyOperationGate.finish(sender.uniqueId)
                val livePlayer = BukkitPlugin.INSTANCE.server.getPlayer(sender.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                val liveWorld = getPlayerWorld(livePlayer) as? CoreWorld ?: run {
                    livePlayer.sendMessage("错误: 无法获取 CoreWorld")
                    return@supply
                }
                completeCreateOnMainThread(
                    livePlayer, liveWorld, trainTypeName, trainName, resourceVersion, carDefs, nearestRail
                )
            },
            onFailure = { error ->
                heavyOperationGate.finish(sender.uniqueId)
                BukkitPlugin.INSTANCE.logger.warning(
                    "TrainCommand create lookup failed for player ${sender.name}: ${error::class.simpleName}: ${error.message ?: ""}"
                )
                BukkitPlugin.INSTANCE.server.getPlayer(sender.uniqueId)?.sendMessage("错误: 创建准备失败: ${error.message}")
            },
            onRetired = { heavyOperationGate.finish(sender.uniqueId) }
        )
        return true
    }

    private fun handleRename(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }
        if (args.size != 2) {
            sender.sendMessage("用法: /jr train rename <current-name> <new-name>")
            return true
        }
        val world = getPlayerWorld(sender) as? CoreWorld ?: return true
        val train = world.getTrain(args[0]) ?: run {
            sender.sendMessage("错误: 找不到列车 '${args[0]}'")
            return true
        }
        val newName = args[1]
        return try {
            world.physicsCoordinator.submitBetweenFrames {
                require(train.controller == null) {
                    "列车 ${train.name} 正被控制或分配给 service，不能重命名"
                }
                require(world.simpleAtoWorldDataOrNull?.depots?.allFixedUnitAssignments()
                    .orEmpty().none { it.trainId == train.id }) {
                    "列车 ${train.name} 的实际单元仍由运行任务占用，不能重命名"
                }
                val oldName = train.name
                world.renameTrain(train, newName)
                "列车已重命名: $oldName -> ${train.name}"
            }.whenComplete { message, failure ->
                BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(sender, action = {
                    sender.sendMessage(if (failure == null) message else "错误: ${failure.message}")
                })
            }
            true
        } catch (failure: IllegalStateException) {
            sender.sendMessage("错误: ${failure.message}")
            true
        }
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
        trainName: String,
        resourceVersion: TrainResourceVersion,
        carDefs: List<CarDefinition>,
        nearestRail: HeavyRailQueries.NearestRailMatch?
    ) {
        val selectedRail = nearestRail?.let { coreWorld.getRail(it.railId) }
        coreWorld.physicsCoordinator.submitBetweenFrames {
            check(BukkitPlugin.INSTANCE.modelManager.resourceVersion(trainTypeName) === resourceVersion) {
                "列车资源已变化，请重新创建"
            }
            val match = requireNotNull(nearestRail) { "世界中没有可放置的轨道" }
            val rail = requireNotNull(selectedRail) { "世界中没有可放置的轨道" }
            check(coreWorld.getRail(rail.id) === rail) { "轨道已变化，请重新创建" }
            coreWorld.validateDefaultTrainCreationIdentity(trainName)
            val requiredBehindHead = estimateTrainLengthBehindHead(carDefs)
            val railViewAB = rail.getView(work.art1st.javarailways.core.api.physics.rail.ViewDirection.AB)
            val rawHeadAB = RailLocationWithFacing(railViewAB, match.arcLength)
            val headLocation = findPlaceableHeadLocation(rawHeadAB, requiredBehindHead)
                ?: error("所选位置两侧轨道长度不足，无法放置 ${"%.1f".format(requiredBehindHead)}m 的列车")
            val blockingTrainId = findBlockingTrainForPlacement(headLocation, requiredBehindHead)
            check(blockingTrainId == null) { "放置区域已有列车 $blockingTrainId，请先移走或删除后再创建" }
            val firstHeadBogieOffset = headBogieToEndPointOffset(carDefs.first())
            val initialBogieLocation = headLocation.forward(-firstHeadBogieOffset)
                ?: error("无法初始化车头转向架位置")

            val cars = mutableListOf<CoreCar>()
            val trainId = trainName
            CoreTrain.createOwned(coreWorld) {
                for (i in resourceVersion.composition.indices) {
                    val carDef = carDefs[i]
                    val bogieA = bogie(initialBogieLocation)
                    val bogieB = bogie(initialBogieLocation)

                    val car = car(
                        id = carIdForTrain(trainId, i),
                        bogieA = bogieA,
                        bogieB = bogieB,
                        length = carDef.length,
                        floorHeight = carDef.floorHeight,
                        connectorLengthAtA = carDef.connectorLengthA,
                        connectorLengthAtB = carDef.connectorLengthB,
                        bogieAOffset = carDef.bogieAOffset,
                        bogieBOffset = carDef.bogieBOffset
                    )
                    cars.add(car)

                    val carExtension = CarExtension()
                    car.registerExtensionData(BukkitPlugin.INSTANCE, carExtension)
                    carExtension.initialize(car, emptyMap())
                    carExtension.bindCreatedResources(trainTypeName, resourceVersion.composition[i], resourceVersion)
                }

                val train = train(
                    id = trainId,
                    cars = cars
                )
                train.setLocation(headLocation)
                val trainExtension = TrainExtension()
                train.registerExtensionData(BukkitPlugin.INSTANCE, trainExtension)
                trainExtension.initialize(train, emptyMap())
                trainExtension.bindCreatedResources(trainTypeName, resourceVersion)
                coreWorld.basicWorldDataOrNull?.trainSpeedProfiles?.put(
                    train.id,
                    TrainSpeedProfile(trainExtension.resolveControl().curveSpeedLimitOffset)
                )

                onFailure { coreWorld.basicWorldDataOrNull?.trainSpeedProfiles?.remove(train.id) }
                publish()
                val messages = buildList {
                    add("创建列车成功: ${trainIdentityLabel(train)} (类型: $trainTypeName)")
                    add("手持通用列车钥匙坐在驾驶座上驾驶，可用 /jr train key 获取。")
                    add("车厢数量: ${cars.size}")
                    add("列车头部位置: ${formatVector(train.head.location.position)}")
                    add("列车尾部位置: ${formatVector(train.tail.location.position)}")
                    cars.forEachIndexed { index, car ->
                        add(
                            "车厢 ${index + 1}: model=${resourceVersion.composition[index]}, " +
                                "center=${formatVector(car.position)}, " +
                                "bogieA=${formatVector(car.bogieA.position)}, bogieB=${formatVector(car.bogieB.position)}"
                        )
                    }
                }
                train to messages
            }
        }.whenComplete { created, failure ->
            BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(sender, action = {
                if (failure != null) {
                    sender.sendMessage("错误: 创建列车失败: ${failure.message}")
                } else {
                    created.first.cars.forEach { it.renderer.sendTo(sender) }
                    created.second.forEach(sender::sendMessage)
                }
            })
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

    private fun handleStart(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }
        if (playerControllers.containsKey(sender.uniqueId)) {
            sender.sendMessage("已有控制器运行中，先用 /jr train stop 停止")
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

        val playerId = sender.uniqueId
        val expectedController = train.controller
        val initialHandle = args.firstOrNull()
        reportControllerOperation(sender, coreWorld.physicsCoordinator.submitBetweenFrames {
            check(coreWorld.getTrain(train.id) === train && train.controller === expectedController &&
                playerControllers[playerId] == null) { "列车或驾驶员控制状态已变化，请重试" }
            val (controlConfig, carControls) = resolveControlProfile(train)
            val controller = RealisticManualController(
                train, controlConfig, carControls,
                BukkitPlugin.INSTANCE.physicsConfig.track.gravityMetersPerSecondSquared,
                profileResolver = { car -> car.carRendererOrNull?.resolveCarControl() },
                initialDeviceSource = train.controller as? work.art1st.javarailways.basic.api.ato.DynamicsProfileSource
            )
            train.setController(controller)
            playerControllers[playerId] = controller
            buildList {
                if (initialHandle != null && !controller.setHandle(initialHandle)) {
                    add("未知手柄档位: $initialHandle")
                    add("可用档位: ${availableHandles(controlConfig)}")
                }
                add("控制器已连接至列车: ${train.id}")
                add("使用 /jr train handle <P1..P5|N|B1..B7|EB> 设置手柄")
                add("使用 /jr train stop 断开连接")
            }
        })
        return true
    }

    private fun handleStop(sender: CommandSender): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }
        val playerId = sender.uniqueId
        val controller = playerControllers[playerId]
        if (controller == null) {
            sender.sendMessage("当前没有连接的控制器")
            return true
        }

        val train = controller.controlledTrain as CoreTrain
        reportControllerOperation(sender, train.world.physicsCoordinator.submitBetweenFrames {
            check(playerControllers[playerId] === controller && train.world.getTrain(train.id) === train &&
                train.controller === controller) { "控制器已变化，请重试" }
            train.setController(null)
            playerControllers.remove(playerId, controller)
            listOf("控制器已断开连接")
        })
        return true
    }

    private fun reportControllerOperation(player: Player, result: CompletableFuture<List<String>>) {
        result.whenComplete { messages, failure ->
            BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(player, action = {
                if (failure != null) player.sendMessage("操作失败: ${failure.message}")
                else messages.forEach(player::sendMessage)
            })
        }
    }

    private fun handleHandle(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }
        val controller = playerControllers[sender.uniqueId]
        if (controller == null) {
            sender.sendMessage("当前没有连接的控制器，先用 /jr train start")
            return true
        }
        if (!isDrivingControlledTrain(sender, controller)) {
            sender.sendMessage("手柄无效：请坐在当前列车的驾驶座上")
            return true
        }

        val handle = args.firstOrNull()
        if (handle == null) {
            sender.sendMessage("用法: /jr train handle <P1..P5|N|B1..B7|EB>")
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

    private fun handleReverse(sender: CommandSender): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }
        val controller = playerControllers[sender.uniqueId]
        if (controller == null) {
            sender.sendMessage("当前没有连接的控制器，先用 /jr train start")
            return true
        }

        if (!isDrivingControlledTrain(sender, controller)) {
            sender.sendMessage("换向无效：请坐在当前列车的驾驶座上")
            return true
        }

        controller.reverse().whenComplete { _, failure ->
            BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(sender, action = {
                if (failure == null) {
                    sender.sendMessage("列车已换向")
                } else {
                    sender.sendMessage("换向失败: ${failure.cause?.message ?: failure.message}")
                }
            })
        }
        return true
    }

    private fun handleAnim(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }
        val animationArg = args.firstOrNull()
        if (animationArg == null) {
            sender.sendMessage("用法: /jr train anim <animationName> [sceneName]")
            sender.sendMessage("  也可使用: /jr train anim <animationName>:<sceneName>")
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

        train.renderer.applyAnimation(animationName, sceneName).whenComplete { _, failure ->
            BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(sender, action = {
                sender.sendMessage(if (failure == null) "已触发列车动画: $animationName" else "动画失败: ${failure.message}")
            })
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
            sender.sendMessage("用法: /jr train action <actionName>")
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

        train.renderer.executeModelAction(actionName).whenComplete { _, failure ->
            BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(sender, action = {
                sender.sendMessage(if (failure == null) "已完成列车动作: $actionName" else "动作失败: ${failure.message}")
            })
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
            sender.sendMessage("用法: /jr train behavior <method> [args...]")
            sender.sendMessage("示例: /jr train behavior onStation 0 left")
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

        train.renderer.callBehavior(methodName, args.drop(1).map(::parseBehaviorArg)).whenComplete { _, failure ->
            BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(sender, action = {
                sender.sendMessage(if (failure == null) "已调用每车行为: $methodName" else "行为调用失败: ${failure.message}")
            })
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

        val requestedId = args.firstOrNull()
        val train = selectTrainForDeletion(coreWorld, requestedId) { findNearestTrain(sender, coreWorld) }
            ?: run {
                sender.sendMessage(if (requestedId == null) "错误: 附近没有列车" else "错误: 列车不存在: $requestedId")
                return true
            }

        val platformWorld = sender.world
        val deletion = coreWorld.physicsCoordinator.submitBetweenFrames {
            check(coreWorld.getTrain(train.id) === train) { "列车已变化，请重试" }
            playerControllers.entries.removeIf { it.value.controlledTrain === train }
            BukkitPlugin.INSTANCE.trainControlManager.releaseTrain(train)
            coreWorld.basicWorldDataOrNull?.trainSpeedProfiles?.remove(train.id)
            train.destroy()
        }.thenCompose { cleanup -> cleanup.thenApply { listOf("已删除列车: ${train.id}") } }
        deletion.thenRun {
            BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(sender, action = {
                AbnormalTrainMarker.clearTrain(platformWorld, train.id)
            })
        }
        reportControllerOperation(sender, deletion)
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
            completionEntity = sender,
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
            },
            onRetired = { heavyOperationGate.finish(sender.uniqueId) }
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
            sender.sendMessage("列车: ${trainIdentityLabel(train)}")
            if (controller != null) {
                sender.sendMessage("速度: ${"%.2f".format(controller.speed)} m/s")
                sender.sendMessage("加速度: ${"%.3f".format(controller.acceleration)} m/s²")
                sender.sendMessage("手柄: ${controller.handle}")
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

        return work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge.runInSimulationContext(world) {
        sender.sendMessage("=== 列车列表 ===")
        for (train in world.allTrains) {
            sender.sendMessage("  ${trainIdentityLabel(train)} - ${train.length} 节车厢")
        }
        true
        }
    }

    private fun resolveControlProfile(train: CoreTrain): Pair<TrainControlConfig, List<CarControlConfig>> {
        val renderer = train.rendererOrNull
            ?: throw IllegalArgumentException("列车 ${train.id} 没有渲染扩展，无法解析控制配置")
        val trainControl = renderer.resolveControl()
        val carControls = train.cars.mapIndexed { index, car ->
            val carRenderer = car.carRendererOrNull
            val modelName = carRenderer?.carModelName
                ?: throw IllegalArgumentException("列车 ${train.id} 的第 ${index + 1} 节车厢缺少 carModelName，无法解析控制配置")
            carRenderer.resolveCarControl()
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

    data class AtoNavigationControlPreparation(
        val releasedController: TrainController? = null,
        val failureReason: String? = null
    )

    /** Release this driver's manual controller before an ad-hoc ATO navigation starts. */
    fun prepareForAtoNavigation(
        playerId: UUID,
        train: CoreTrain
    ): AtoNavigationControlPreparation {
        train.world.physicsCoordinator.ensureBetweenFrames("TrainCommand.prepareForAtoNavigation")
        val controller = playerControllers[playerId]
        if (controller != null) {
            val controlledTrain = controller.controlledTrain as? CoreTrain
            if (controlledTrain !== train) {
                return AtoNavigationControlPreparation(
                    failureReason =
                        "驾驶员连接的是另一列车: ${controlledTrain?.id ?: "unknown"}"
                )
            }
            controller.coast()
            playerControllers.remove(playerId)
            if (train.controller === controller) train.setController(null)
        }
        val activeController = train.controller
        if (activeController is SimpleATO) {
            return AtoNavigationControlPreparation(releasedController = controller)
        }
        if (activeController is RealisticManualController) {
            activeController.coast()
            train.setController(null)
            return AtoNavigationControlPreparation(releasedController = activeController)
        }
        if (activeController != null) {
            train.setController(null)
            return AtoNavigationControlPreparation(releasedController = activeController)
        }
        return AtoNavigationControlPreparation(releasedController = controller)
    }

    fun restoreAfterFailedAtoNavigation(
        playerId: UUID,
        train: CoreTrain,
        controller: TrainController?
    ) {
        train.world.physicsCoordinator.ensureBetweenFrames("TrainCommand.restoreAfterFailedAtoNavigation")
        if (controller == null || train.controller != null) return
        train.setController(controller)
        if (controller is RealisticManualController) {
            playerControllers[playerId] = controller
        }
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

internal fun carIdForTrain(trainId: String, index: Int): String = "$trainId-car-$index"

internal fun trainIdentityLabel(train: Train): String =
    if (train.name == train.id) train.name else "${train.name} [${train.id}]"

/** An explicit ID is authoritative: a typo must never target a nearby train. */
internal fun selectTrainForDeletion(
    world: CoreWorld,
    requestedId: String?,
    nearestTrain: () -> CoreTrain?
): CoreTrain? = if (requestedId == null) nearestTrain() else world.getTrain(requestedId)

/** Keep the selected head point; only choose which direction has room for the whole consist. */
internal fun findPlaceableHeadLocation(
    rawHeadAB: RailLocationWithFacing,
    requiredBehindHead: Double
): RailLocationWithFacing? {
    if (rawHeadAB.forward(-requiredBehindHead) != null) return rawHeadAB
    return rawHeadAB.reversed.takeIf { it.forward(-requiredBehindHead) != null }
}
