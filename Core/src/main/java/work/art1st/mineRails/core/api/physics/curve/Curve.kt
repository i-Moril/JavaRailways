package work.art1st.mineRails.core.api.physics.curve

import work.art1st.mineRails.core.physics.rail.curve.CurveSlice
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurve
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
        val normal: Vector3D,
        val tangent: Vector3D,

        // 均为primeCurve上的idx和arcLength
        val curve: PrimeCurve,
        val idx: Int,
        val arcLength: Double
    )

    val totalArcLength: Double
    val totalPoints: Int
    val reversed: Curve

    /* Should allow negative index */
    fun getPointAt(idx: Int): Point?
    fun getPointAt(arcLength: Double): Point?

    /**
     * 不保证顺序
     */
    @Heavy
    fun stream(): Stream<Point>
    @Heavy
    fun getPointAt(position: Vector3D): Point? {
        val point = getNearestPoint(position) ?: return null
        if (point.position.distance(position) < Curve.TOLERANCE) return point
        return null
    }
    @Heavy
    fun getNearestPoint(position: Vector3D): Point? {
        return stream().min(Comparator.comparing { point -> point.position.distance(position)} ).getOrNull()
    }

    /**
     * 截取的是开区间
     * [startIdx, endIdx)
     */
    fun slice(startIdx: Int, endIdx: Int): Curve? {
        if (startIdx !in 0..<totalPoints) return null
        if (endIdx !in 0..totalPoints) return null
        if (startIdx > endIdx) return CurveSlice(this, endIdx, startIdx).reversed
        return CurveSlice(this, startIdx, endIdx)
    }

    /**
     * 截取的是闭区间
     * [start, end]
     */
    fun slice(start: Double, end: Double): Curve? {
        if (start !in 0.0..totalArcLength) return null
        if (end !in 0.0..totalArcLength) return null
        val startIdx = getPointAt(start)!!.idx
        val endIdx = getPointAt(end)!!.idx + 1
        return slice(startIdx, endIdx)
    }

    companion object {
        const val TOLERANCE: Double = 5e-3
        const val PRECISION: Double = 1e-3
    }
}