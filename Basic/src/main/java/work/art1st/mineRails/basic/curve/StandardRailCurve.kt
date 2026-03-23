package work.art1st.mineRails.basic.curve

import org.joml.Vector3d
import org.joml.Vector3dc
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object StandardRailCurve {
    /**
     * 简化的回旋线（缓和曲线）坐标计算 - 使用级数展开近似 Fresnel 积分
     * @param l 当前长度
     * @param A 回旋线参数 (A = sqrt(R * L_total))
     * @return 相对坐标
     */
    fun calculateClothoid(l: Double, A: Double): DoubleArray {
        val x: Double = l - l.pow(5.0) / (40 * A.pow(4.0))
        val y: Double = l.pow(3.0) / (6 * A.pow(2.0)) - l.pow(7.0) / (336 * A.pow(6.0))
        return doubleArrayOf(x, y)
    }

    /**
     * 生成三维轨道点列表
     * @param pStart 起点
     * @param pEnd 终点
     * @param R 圆曲线半径
     * @param L 缓和曲线长度
     * @param step 步长（米）
     */
    fun generateRailway(pStart: Vector3dc, pEnd: Vector3dc, R: Double, L: Double, step: Double): MutableList<Vector3d?> {
        val points: MutableList<Vector3d?> = ArrayList<Vector3d?>()
        val totalDist = pStart.distance(pEnd)

        // 1. 简化的几何划分：假设轨道由两端缓和曲线和中间圆曲线组成
        // 实际工程中需根据转角计算圆曲线长度，此处为演示离散化逻辑
        val L_total = totalDist
        val L_arc = L_total - 2 * L // 圆曲线长度

        if (L_arc < 0) {
            println("警告：距离太短，无法容纳给定的缓和曲线长度！")
            return points
        }

        val A: Double = sqrt(R * L) // 回旋线参数
        var currentS = 0.0

        while (currentS <= L_total) {
            val tx: Double
            val ty: Double
            val tz: Double

            // 计算 Y 轴线性插值（坡度）
            ty = pStart.y() + (pEnd.y() - pStart.y()) * (currentS / L_total)

            if (currentS < L) {
                // 第一段：第一条缓和曲线
                val coords = calculateClothoid(currentS, A)
                tx = coords[0]
                tz = coords[2]
            } else if (currentS <= L + L_arc) {
                // 第二段：圆曲线 (简化为圆弧公式)
                val sInArc = currentS - L
                val angle = sInArc / R
                // 对接缓和曲线终点的切向
                tx = L + R * sin(angle)
                tz = (L.pow(3.0) / (6 * A * A)) + R * (1 - cos(angle))
            } else {
                // 第三段：第二条缓和曲线 (镜像逻辑)
                val sInLastTransition = L_total - currentS
                val coords = calculateClothoid(sInLastTransition, A)
                tx = L_total - coords[0]
                tz = coords[2]
            }

            // 此处 tx, tz 是在局部坐标系下的，实际应用需通过旋转矩阵转换到 pStart -> pEnd 的切向量方向
            // 为简化演示，直接输出坐标
            points.add(Vector3d(tx + pStart.x(), ty, tz + pStart.z()))
            currentS += step
        }

        return points
    }
}
