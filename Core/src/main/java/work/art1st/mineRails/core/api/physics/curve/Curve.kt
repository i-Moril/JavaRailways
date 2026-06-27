package work.art1st.mineRails.core.api.physics.curve

import org.joml.Vector3dc
import work.art1st.mineRails.core.util.Heavy
import java.util.stream.Stream

/**
 * 连续曲线，用弧长参数化。
 * 曲线每一点上都定义一个法线方向，作为铁轨平面的方向。
 */
interface Curve {
    data class Point(
        val position: Vector3dc,
        val tangent: Vector3dc,
        val normal: Vector3dc,
        val curve: PrimeCurve,
        val arcLength: Double
    )

    /** 采样段类型：决定 getPointAt 的插值策略 */
    enum class SegmentType {
        /** 离散密集采样点，getPointAt 在相邻点之间线性插值 */
        DISCRETE,
        /** 直线段起点，与下一个 LINE_END 配对，中间精确定义为直线 */
        LINE_START,
        /** 直线段终点 */
        LINE_END,
    }

    /** 仅用于创建曲线 */
    data class RawPoint(
        val position: Vector3dc,
        val tangent: Vector3dc,
        val normal: Vector3dc,
        val arcLength: Double,
        val segmentType: SegmentType = SegmentType.DISCRETE
    )

    val totalArcLength: Double
    val reversed: Curve

    /** 弧长参数化：返回 arcLength 处的精确点（根据段类型插值） */
    fun getPointAt(arcLength: Double): Point

    @Heavy
    fun stream(): Stream<Point>

    @Heavy
    fun getPointAt(position: Vector3dc): Point {
        val point = getNearestPoint(position)
        if (point.position.distance(position) < TOLERANCE) return point
        throw CurveException(this, point, "Point not in curve.")
    }

    @Heavy
    fun getNearestPoint(position: Vector3dc): Point {
        return stream().min(Comparator.comparing { point -> point.position.distance(position) }).get()
    }

    companion object {
        const val TOLERANCE: Double = 5e-3
        const val PRECISION: Double = 1e-3
    }
}
