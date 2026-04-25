package work.art1st.mineRails.bukkit.command

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.rail.Rail
import work.art1st.mineRails.core.physics.rail.CoreRail
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveImpl
import work.art1st.mineRails.core.util.BinarySearch
import org.joml.Vector3d
import org.joml.Vector3f

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
        val startX = sender.location.x.toFloat()
        val startY = sender.location.y.toFloat()
        val startZ = sender.location.z.toFloat()

        // 创建直线曲线
        val curve = createStraightCurve(startX, startY, startZ, direction, length)

        // 创建轨道
        val rail = CoreRail(
            coreWorld,
            curve,
            mutableListOf(),
            mutableListOf()
        )

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
        startX: Float,
        startY: Float,
        startZ: Float,
        direction: Vector3d,
        length: Double
    ): PrimeCurveImpl {
        val curve = PrimeCurveImpl()
        val step = Curve.PRECISION
        val steps = maxOf(2, (length / step).toInt())

        val tangent = Vector3f(direction.x().toFloat(), direction.y().toFloat(), direction.z().toFloat())
        val normal = Vector3f(0.0f, 1.0f, 0.0f)

        val points = (0..steps).map { i ->
            val t = i.toFloat() / steps
            val x = (startX + direction.x() * length * t).toFloat()
            val y = (startY + direction.y() * length * t).toFloat()
            val z = (startZ + direction.z() * length * t).toFloat()

            createInternalPoint(
                Vector3f(x, y, z),
                tangent,
                normal,
                (t * length).toFloat(),
                i
            )
        }

        setPointList(curve, points)
        return curve
    }

    private fun createInternalPoint(
        position: Vector3f,
        tangent: Vector3f,
        normal: Vector3f,
        arcLength: Float,
        idx: Int
    ): Any {
        val curveClassName = PrimeCurveImpl::class.qualifiedName!!
        val pointClassName = "$curveClassName\$InternalCurvePoint"
        val pointClass = Class.forName(pointClassName)
        val constructor = pointClass.getDeclaredConstructor(
            Vector3f::class.java,
            Vector3f::class.java,
            Vector3f::class.java,
            Float::class.java,
            Int::class.java,
        )
        constructor.isAccessible = true
        return constructor.newInstance(position, tangent, normal, arcLength, idx)
    }

    private fun setPointList(curve: PrimeCurveImpl, pointList: List<Any>) {
        val field = PrimeCurveImpl::class.java.getDeclaredField("pointList")
        field.isAccessible = true
        field.set(curve, pointList)
    }
}