package work.art1st.mineRails.bukkit.command

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.rail.Rail
import work.art1st.mineRails.bukkit.extension.rail.RailExtension
import work.art1st.mineRails.core.physics.rail.CoreRail
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import org.joml.Vector3d

object RailCommand {
    fun onCommand(sender: CommandSender, args: List<String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage("用法: /mr rail <create|list|delete>")
            return true
        }

        when (args[0].lowercase()) {
            "create" -> return handleCreate(sender, args.drop(1))
            "list" -> return handleList(sender)
            "delete" -> return handleDelete(sender)
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
        // 注册轨道可视化扩展
        rail.registerExtensionData(BukkitPlugin, RailExtension())

        sender.sendMessage("创建轨道成功: ${rail.id}")
        sender.sendMessage("长度: ${"%.2f".format(length)} 米")

        return true
    }

    private fun handleList(sender: CommandSender): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }

        val world = getPlayerWorld(sender) ?: return true

        sender.sendMessage("=== 轨道列表 ===")

        return true
    }

    private fun handleDelete(sender: CommandSender): Boolean {
        sender.sendMessage("轨道删除功能待实现")
        return true
    }

    private fun getPlayerWorld(player: Player): World? {
        val bukkitWorld = player.world
        val worldManager = BukkitPlugin.worldManager
        return worldManager.railWorlds[bukkitWorld]
    }

    /**
     * 创建直线曲线。
     */
    private fun createStraightCurve(
        startX: Double,
        startY: Double,
        startZ: Double,
        direction: Vector3d,
        length: Double
    ): PrimeCurve {
        val step = Curve.PRECISION
        val steps = maxOf(2, (length / step).toInt())

        // 切线方向（前进方向）
        val tangentDir = Vector3d(direction.x(), direction.y(), direction.z())
        // 法线方向（垂直向上）
        val normalDir = Vector3d(0.0, 1.0, 0.0)

        val points = (0..steps).map { i ->
            val t = i.toDouble() / steps
            val arcLength = t * length

            Curve.RawPoint(
                position = org.joml.Vector3d(
                    startX + direction.x * length * t,
                    startY + direction.y * length * t,
                    startZ + direction.z * length * t
                ),
                tangent = tangentDir,
                normal = normalDir,
                arcLength = arcLength
            )
        }

        return PrimeCurve.fromPoints(points)
    }
}