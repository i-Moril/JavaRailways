package work.art1st.mineRails.bukkit.command

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.mineRails.basic.controller.ManualController
import work.art1st.mineRails.bukkit.BukkitPlugin
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
            sender.sendMessage("用法: /mr train <create|start|stop|throttle|reverse|info|list>")
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

        val direction = work.art1st.mineRails.core.api.physics.rail.ViewDirection.AB
        val railView = nearestRail.getView(direction)
        val nearestPoint = railView.curve.getNearestPoint(playerPos)
        val headLocation = RailLocationWithFacing(railView, nearestPoint.arcLength)

        // 参数: /mr train create <train_type>
        val trainTypeName = args.firstOrNull() ?: "s100_4car"

        val modelManager = BukkitPlugin.INSTANCE.modelManager
            ?: run {
                sender.sendMessage("错误: ModelManager 未初始化")
                return true
            }

        // 查找列车定义
        val trainDef = modelManager.getTrain(trainTypeName)
        if (trainDef == null) {
            sender.sendMessage("错误: 未知列车类型 '$trainTypeName'")
            sender.sendMessage("可用类型: ${modelManager.trainLoader.getAllNames().joinToString(", ")}")
            return true
        }

        var currentLocation = headLocation
        val cars = mutableListOf<CoreCar>()

        for ((i, carModelName) in trainDef.composition.withIndex()) {
            val carDef = modelManager.getCar(carModelName)
                ?: run {
                    sender.sendMessage("错误: 未知车厢模型 '$carModelName'")
                    return true
                }

            val carLength = carDef.length

            val bogieA = CoreBogie.create(currentLocation)
            val nextLocation = currentLocation.forward(carLength) ?: currentLocation
            val bogieB = CoreBogie.create(nextLocation)

            val car = CoreCar.create(
                id = "${sender.name}-car-$i",
                bogieA = bogieA,
                bogieB = bogieB,
                length = carLength,
                floorHeight = carDef.floorHeight,
                connectorLengthAtA = carDef.connectorLengthA,
                connectorLengthAtB = carDef.connectorLengthB,
                bogieAOffset = carDef.bogieAOffset,
                bogieBOffset = carDef.bogieBOffset,
                world = coreWorld
            )
            cars.add(car)
            currentLocation = nextLocation

            // 注册车厢渲染扩展
            val carExtension = CarExtension()
            car.registerExtensionData(BukkitPlugin.INSTANCE, carExtension)
            carExtension.initialize(car, emptyMap())
            carExtension.carModelName = carModelName
        }

        val trainId = "train-${UUID.randomUUID().toString().take(8)}"
        val train = CoreTrain.create(
            id = trainId,
            cars = cars,
            world = coreWorld
        )

        train.setLocation(headLocation)
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

        val controller = playerControllers[sender.uniqueId]
        if (controller != null) {
            val train = controller.controlledTrain
            sender.sendMessage("=== 列车状态 ===")
            sender.sendMessage("列车: ${train.id}")
            sender.sendMessage("速度: ${"%.2f".format(controller.speed)} m/s")
            sender.sendMessage("目标速度: ${"%.2f".format(controller.targetSpeed)} m/s")
            sender.sendMessage("手柄: ${"%.2f".format(controller.throttle)}")
            sender.sendMessage("脱轨: ${if (train.isDerailed) "是" else "否"}")
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
