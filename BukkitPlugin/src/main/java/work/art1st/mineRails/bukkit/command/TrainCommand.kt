package work.art1st.mineRails.bukkit.command

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.joml.Vector3d
import work.art1st.mineRails.basic.controller.ManualController
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.physics.train.CoreBogie
import work.art1st.mineRails.core.physics.train.CoreCar
import work.art1st.mineRails.core.physics.train.CoreTrain
import work.art1st.mineRails.core.physics.CoreWorld
import java.util.UUID

object TrainCommand {
    /**
     * 玩家 -> 当前手动控制器
     */
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

        val playerPos = Vector3d(sender.location.x, sender.location.y, sender.location.z)
        val nearestRail = coreWorld.getNearestRail(playerPos)

        if (nearestRail == null) {
            sender.sendMessage("错误: 世界中没有轨道，请先使用 /mr rail create 创建轨道")
            return true
        }

        val direction = work.art1st.mineRails.core.physics.rail.RailView.ViewDirection.AB
        val railView = nearestRail.getView(direction)
        val headLocation = RailLocationWithFacing(railView, 0)

        val carCount = args.firstOrNull()?.toIntOrNull() ?: 1
        val carLength = args.getOrNull(1)?.toDoubleOrNull() ?: 2.0

        var currentLocation = headLocation
        val cars = mutableListOf<CoreCar>()

        repeat(carCount) { i ->
            val bogieA = CoreBogie.create(currentLocation)
            val nextLocation = currentLocation.forward(carLength) ?: currentLocation
            val bogieB = CoreBogie.create(nextLocation)

            val car = CoreCar.create(
                id = "${sender.name}-car-$i",
                bogieA = bogieA,
                bogieB = bogieB,
                length = carLength,
                world = coreWorld
            )
            cars.add(car)
            currentLocation = nextLocation
        }

        val trainId = "train-${UUID.randomUUID().toString().take(8)}"
        val train = CoreTrain.create(
            id = trainId,
            cars = cars,
            world = coreWorld
        )

        sender.sendMessage("创建列车成功: ${train.id}")
        sender.sendMessage("车厢数量: ${cars.size}，长度: ${"%.2f".format(carLength)} 米")
        sender.sendMessage("使用 /mr train start 启动列车")
        return true
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

        controller.controlledTrain.setController(null)
        sender.sendMessage("控制器已断开连接")
        return true
    }

    /**
     * 设置加速手柄。
     * @param args[0] 手柄位置，范围 [-1, 1]
     *   正值：加速（0=惰行，0.5=半速，1=全速）
     *   负值：制动（-1=紧急制动）
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
        val world = BukkitPlugin.worldManager.railWorlds.values.firstOrNull() as? CoreWorld ?: run {
            sender.sendMessage("错误: 无法获取 CoreWorld")
            return true
        }

        sender.sendMessage("=== 列车列表 ===")
        return true
    }

    /**
     * 查找玩家最近的列车。
     * 优先返回有控制器的列车，否则按距离查找。
     */
    private fun findNearestTrain(sender: Player, coreWorld: CoreWorld): CoreTrain? {
        // 优先使用玩家创建的最后一节列车
        // 这里简化为查找所有列车中距离最近的
        val playerPos = Vector3d(sender.location.x, sender.location.y, sender.location.z)

        return coreWorld.allTrains.minByOrNull { train ->
            train.cars.minOfOrNull { car ->
                car.position.distance(playerPos)
            } ?: Double.MAX_VALUE
        }
    }

    private fun getPlayerWorld(player: Player): World? {
        val bukkitWorld = player.world
        val worldManager = BukkitPlugin.worldManager
        return worldManager.railWorlds[bukkitWorld]
    }
}
