package work.art1st.mineRails.core.physics.rail.curve

import org.joml.Vector3d
import org.joml.Vector3f
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 测试用：生成直线和圆弧 PrimeCurve，切成若干段，每段一个 PrimeCurve。
 */
object PrimeCurveGenerator {

    private const val DEFAULT_STEP = Curve.PRECISION

    /** 直线默认段数 */
    const val DEFAULT_STRAIGHT_SEGMENTS = 5

    /** 圆默认段数 */
    const val DEFAULT_CIRCLE_SEGMENTS = 4

    /**
     * 生成直线轨道并切成若干段。水平面为 XZ，Y 为高度；直线沿 X 轴，y=0，从 (0,0,0) 到 (length,0,0)。
     * @param segmentCount 段数，每段弧长 length/segmentCount
     */
    fun buildStraight(
        length: Double,
        segmentCount: Int = DEFAULT_STRAIGHT_SEGMENTS,
        step: Double = DEFAULT_STEP
    ): List<PrimeCurve> {
        val segmentLength = length / segmentCount
        return (0 until segmentCount).map { seg ->
            val tangent = Vector3d(1.0, 0.0, 0.0)
            val normal = Vector3d(0.0, 1.0, 0.0) // Y 向上
            val n = (segmentLength / step).toInt().coerceAtLeast(1)
            val actualStep = segmentLength / n
            val offsetX = seg * segmentLength
            val points = (0..n).map { i ->
                val s = i * actualStep
                val x = offsetX + s
                Curve.RawPoint(
                    position = Vector3d(x, 0.0, 0.0),
                    tangent = tangent,
                    normal = normal,
                    arcLength = s
                )
            }
            PrimeCurve.fromPoints(points)
        }
    }

    /**
     * 生成整圆轨道并切成若干段。水平面为 XZ，Y 为高度；圆心 (0,0,0)，半径 [radius] m，圆在 XZ 平面（y=0），总弧长 2*pi*radius。
     * @param segmentCount 段数（如 4 即四段圆弧）
     */
    fun buildCircle(
        radius: Double,
        segmentCount: Int = DEFAULT_CIRCLE_SEGMENTS,
        step: Double = DEFAULT_STEP
    ): List<PrimeCurve> {
        val circumference = 2 * PI * radius
        val segmentArcLength = circumference / segmentCount
        return (0 until segmentCount).map { seg ->
            val theta0 = seg * (2 * PI / segmentCount)
            val theta1 = (seg + 1) * (2 * PI / segmentCount)
            val n = (segmentArcLength / step).toInt().coerceAtLeast(2)
            val dTheta = (theta1 - theta0) / n
            val points = (0..n).map { i ->
                val theta = theta0 + i * dTheta
                val s = i * (segmentArcLength / n)
                val position = Vector3d(radius * cos(theta), 0.0, radius * sin(theta))
                val tangent = Vector3d(-sin(theta), 0.0, cos(theta))
                val normal = Vector3d(0.0, 1.0, 0.0)
                Curve.RawPoint(
                    position = position,
                    tangent = tangent,
                    normal = normal,
                    arcLength = s
                )
            }
            PrimeCurve.fromPoints(points)
        }
    }
}
