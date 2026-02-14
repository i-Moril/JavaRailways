package work.art1st.mineRails.core.api.physics.curve

import work.art1st.mineRails.core.util.Heavy
import work.art1st.mineRails.core.api.math.Vector3D
import java.util.stream.Stream
import kotlin.jvm.optionals.getOrNull

/**
 * 离散化的曲线类，用于表示铁轨
 * 曲线每一点上都定义一个法线方向，作为铁轨平面的方向
 */
interface Curve {
    /**
     * 曲线上的点，每个点包含位置、切线方向和法线方向
     */
    @JvmRecord
    data class Point(
        val position: Vector3D,
        val tangent: Vector3D,
        val normal: Vector3D,

        val curve: PrimeCurve,
        val idx: Int,
        val arcLength: Double
    )

    val totalArcLength: Double
    val totalPoints: Int
    val reversed: Curve

    /* Should allow negative index */
    fun getPointAt(idx: Int): Point
    fun getPointAt(arcLength: Double): Point = getPointAt(getIdxAt(arcLength))
    fun getIdxAt(arcLength: Double): Int

    /**
     * 不保证顺序
     */
    @Heavy
    fun stream(): Stream<Point>
    @Heavy
    fun getPointAt(position: Vector3D): Point {
        val point = getNearestPoint(position)
        if (point.position.distance(position) < TOLERANCE) return point
        throw CurveException(this, point, "Point not in curve.")
    }
    @Heavy
    fun getNearestPoint(position: Vector3D): Point {
        return stream().min(Comparator.comparing { point -> point.position.distance(position)} ).get()
    }

    companion object {
        const val TOLERANCE: Double = 5e-3
        const val PRECISION: Double = 1e-3
    }
}