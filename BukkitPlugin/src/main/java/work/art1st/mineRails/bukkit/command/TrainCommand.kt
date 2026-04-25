package work.art1st.mineRails.bukkit.command

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.joml.Vector3d
import work.art1st.mineRails.basic.controller.ManualController
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.extension.train.BogieExtension
import work.art1st.mineRails.bukkit.extension.train.CarExtension
import work.art1st.mineRails.bukkit.extension.train.SeatExtension
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.physics.train.CoreBogie
import work.art1st.mineRails.core.physics.train.CoreCar
import work.art1st.mineRails.core.physics.train.CoreTrain
import work.art1st.mineRails.core.physics.CoreWorld
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object TrainCommand {
    /** 玩家 UUID -> 手动控制器 */
    private val playerControllers = mutableMapOf<UUID, ManualController>()

    /** 列车 ID -> 车厢座位扩展（用于销毁） */
    private val trainSeatExtensions = ConcurrentHashMap<String, SeatExtension>()

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
        val seatsPerCar = args.getOrNull(2)?.toIntOrNull() ?: 2

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

            // 注册车厢渲染扩展
            val carExtension = CarExtension()
            car.registerExtensionData(BukkitPlugin, carExtension)

            // 注册转向架渲染扩展
            val bogieAExtension = BogieExtension()
            bogieA.registerExtensionData(BukkitPlugin, bogieAExtension)
            val bogieBExtension = BogieExtension()
            bogieB.registerExtensionData(BukkitPlugin, bogieBExtension)

            // 为车厢添座位
            val seatExtension = SeatExtension(car)
            repeat(seatsPerCar) { seatExtension.onSeatAdded(car.addSeat()) }
            // 记录用于后续销毁
            trainSeatExtensions[car.id] = seatExtension
        }

        val trainId = "train-${UUID.randomUUID().toString().take(8)}"
        val train = CoreTrain.create(
            id = trainId,
            cars = cars,
            world = coreWorld
        )

        sender.sendMessage("创建列车成功: ${train.id}")
        sender.sendMessage("车厢数量: ${cars.size}，每节长度: ${"%.2f".format(carLength)} 米")
        sender.sendMessage("每节车厢座位数: $seatsPerCar")
        sender.sendMessage("右键点击座位（透明小方块）坐下，使用 /mr train stand 站立")

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

        // 查找最近车厢的座位
        val train = findNearestTrain(sender, coreWorld) ?: run {
            sender.sendMessage("错误: 附近没有列车")
            return true
        }

        val nearestCar = train.cars.minByOrNull { car ->
            car.position.distance(Vector3d(sender.location.x, sender.location.y, sender.location.z))
        } ?: run {
            sender.sendMessage("错误: 找不到车厢")
            return true
        }

        val seatExtension = trainSeatExtensions[nearestCar.id]
        if (seatExtension != null) {
            // 坐到第一个空座位
            val seatEntity = seatExtension.getFirstFreeSeat() ?: run {
                sender.sendMessage("该车厢座位已满")
                return true
            }
            seatExtension.makePlayerSit(sender, seatEntity)
            sender.sendMessage("已坐下")
        } else {
            sender.sendMessage("座位系统未初始化")
        }
        return true
    }

    private fun handleStand(sender: CommandSender): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }
        // 遍历所有座位扩展让该玩家站起来
        for (ext in trainSeatExtensions.values) {
            ext.makePlayerStand(sender)
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
        val world = BukkitPlugin.worldManager.railWorlds.values.firstOrNull() as? CoreWorld ?: run {
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
        val worldManager = BukkitPlugin.worldManager
        return worldManager.railWorlds[bukkitWorld]
    }
}
