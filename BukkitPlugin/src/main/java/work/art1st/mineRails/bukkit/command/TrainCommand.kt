package work.art1st.mineRails.bukkit.command

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.joml.Vector3d
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.physics.rail.RailView
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.physics.train.CoreBogie
import work.art1st.mineRails.core.physics.train.CoreCar
import work.art1st.mineRails.core.physics.train.CoreTrain
import work.art1st.mineRails.core.physics.CoreWorld
import java.util.UUID

object TrainCommand {
    fun onCommand(sender: CommandSender, args: List<String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage("用法: /mr train <create|start|stop|info|list>")
            return true
        }

        when (args[0].lowercase()) {
            "create" -> return handleCreate(sender, args.drop(1))
            "start" -> return handleStart(sender)
            "stop" -> return handleStop(sender)
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

        // 查找最近的轨道
        val playerPos = Vector3d(sender.location.x, sender.location.y, sender.location.z)
        val nearestRail = coreWorld.getNearestRail(playerPos)

        if (nearestRail == null) {
            sender.sendMessage("错误: 世界中没有轨道，请先使用 /mr rail create 创建轨道")
            return true
        }

        // 获取轨道视图方向（玩家朝向决定）
        val yaw = sender.location.yaw
        val direction = RailView.ViewDirection.AB

        // 在轨道起始点创建位置
        val railView = nearestRail.getView(direction)
        val headLocation = RailLocationWithFacing(railView, 0)

        // 解析参数
        val carCount = args.firstOrNull()?.toIntOrNull() ?: 1
        val carLength = args.getOrNull(1)?.toDoubleOrNull() ?: 2.0

        // 为每节车厢创建转向架和车厢
        var currentLocation = headLocation
        val cars = mutableListOf<CoreCar>()

        repeat(carCount) { i ->
            val bogieA = CoreBogie.create(currentLocation)
            // 第二个转向架在车厢后方
            val nextLocation = currentLocation.forward(carLength) ?: currentLocation
            val bogieB = CoreBogie.create(nextLocation)

            val car = CoreCar.create(
                id = "car-$i",
                bogieA = bogieA,
                bogieB = bogieB,
                length = carLength,
                world = coreWorld
            )
            cars.add(car)

            // 下一节车厢从本节车厢尾部开始
            currentLocation = nextLocation
        }

        // 创建列车
        val trainId = "train-${UUID.randomUUID().toString().take(8)}"
        val train = CoreTrain.create(
            id = trainId,
            cars = cars,
            world = coreWorld
        )

        sender.sendMessage("创建列车成功: ${train.id}")
        sender.sendMessage("车厢数量: ${cars.size}")
        sender.sendMessage("每节车厢长度: ${"%.2f".format(carLength)} 米")
        sender.sendMessage("所在轨道: ${nearestRail.id}")

        return true
    }

    private fun handleStart(sender: CommandSender): Boolean {
        sender.sendMessage("列车启动功能待实现")
        return true
    }

    private fun handleStop(sender: CommandSender): Boolean {
        sender.sendMessage("列车停止功能待实现")
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

    private fun getPlayerWorld(player: Player): World? {
        val bukkitWorld = player.world
        val worldManager = BukkitPlugin.worldManager
        return worldManager.railWorlds[bukkitWorld]
    }
}
