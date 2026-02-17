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
 * 通过反射设置 pointList（不修改 Core 源码）。
 */
object PrimeCurveGenerator {

    private const val DEFAULT_STEP = Curve.PRECISION

    /** 直线默认段数 */
    const val DEFAULT_STRAIGHT_SEGMENTS = 5

    /** 圆默认段数 */
    const val DEFAULT_CIRCLE_SEGMENTS = 4

    fun buildPoint(vararg args: Any): Any {
        val curveClass = PrimeCurveImpl::class.qualifiedName!!
        val pointClass = Class.forName($$"$$curveClass$InternalCurvePoint")
        val constructor = pointClass.getDeclaredConstructor(
            Vector3f::class.java,
            Vector3f::class.java,
            Vector3f::class.java,
            Float::class.java,
            Int::class.java,
        )
        constructor.isAccessible = true
        return constructor.newInstance(*args)
    }

    /**
     * 生成直线轨道并切成若干段。水平面为 XZ，Y 为高度；直线沿 X 轴，y=0，从 (0,0,0) 到 (length,0,0)。
     * @param segmentCount 段数，每段弧长 length/segmentCount
     * TODO: 修正构建曲线
     */
    fun buildStraight(
        length: Double,
        segmentCount: Int = DEFAULT_STRAIGHT_SEGMENTS,
        step: Double = DEFAULT_STEP
    ): List<PrimeCurve> {
        val segmentLength = length / segmentCount
        return (0 until segmentCount).map { seg ->
            val curve = PrimeCurveImpl()
            val tangent = Vector3f(1.0f, 0.0f, 0.0f)
            val normal = Vector3f(0.0f, 1.0f, 0.0f) // Y 向上
            val n = (segmentLength / step).toInt().coerceAtLeast(1)
            val actualStep = segmentLength / n
            val offsetX = seg * segmentLength
            val pointList = (0..n).map { i ->
                val s = i * actualStep
                val x = offsetX + s
                buildPoint(
                    Vector3f(x.toFloat(), 0.0f, 0.0f), // (x, y, z)，y=0 为地面
                    tangent,
                    normal,
                    s.toFloat(),
                    i
                )
            }
            setPointList(curve, pointList)
            curve
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
            val curve = PrimeCurveImpl()
            val theta0 = seg * (2 * PI / segmentCount)
            val theta1 = (seg + 1) * (2 * PI / segmentCount)
            val n = (segmentArcLength / step).toInt().coerceAtLeast(2)
            val dTheta = (theta1 - theta0) / n
            val pointList = (0..n).map { i ->
                val theta = theta0 + i * dTheta
                val s = i * (segmentArcLength / n)
                val position = Vector3f((radius * cos(theta)).toFloat(), 0.0f, (radius * sin(theta)).toFloat()) // XZ 平面，y=0
                val tangent = Vector3f(-sin(theta).toFloat(), 0.0f, cos(theta).toFloat())
                val normal = Vector3f(0.0f, 1.0f, 0.0f) // Y 向上
                buildPoint(position, tangent, normal, s.toFloat(), i)
            }
            setPointList(curve, pointList)
            curve
        }
    }

    private fun setPointList(curve: PrimeCurveImpl, pointList: List<Any>) {
        val field = PrimeCurveImpl::class.java.getDeclaredField("pointList")
        field.isAccessible = true
        field.set(curve, pointList)
    }
}
