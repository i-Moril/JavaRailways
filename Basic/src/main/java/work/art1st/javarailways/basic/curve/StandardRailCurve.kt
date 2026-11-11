package work.art1st.javarailways.basic.curve

import org.joml.Vector3d
import org.joml.Vector3dc
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 外轨超高配置
 *
 * @param maxSuperelevation 最大超高值（米），如 0.15 表示外轨最高高出 150mm
 * @param superelevationTransition 超高过渡方式，默认为 EN 13803 的 Sine/Klein 正弦过渡
 * @param autoCalculate 自动计算超高值：h(mm) ≈ 11.3·v²(km/h)/R(m)。若速度 ≤ 0 则不使用超高
 * @param maxCantGradient 最大瞬时超高变化率（m/m），默认 1:300
 * @param gaugeMeters 轨距（米）
 * @param gravityMetersPerSecondSquared 重力加速度（m/s²）
 */
data class SuperelevationConfig(
    val maxSuperelevation: Double = 0.15,
    val superelevationTransition: SuperelevationTransition = SuperelevationTransition.SINE_KLEIN,
    val autoCalculate: Boolean = true,
    val designSpeedKmH: Double = 0.0,
    val maxCantGradient: Double = 1.0 / 300.0,
    val gaugeMeters: Double = StandardRailCurveBuilder.DEFAULT_GAUGE,
    val gravityMetersPerSecondSquared: Double = StandardRailCurveBuilder.DEFAULT_GRAVITY
) {
    init {
        require(maxCantGradient.isFinite() && maxCantGradient >= 0.0) {
            "最大超高变化率必须是有限非负数"
        }
        require(gaugeMeters.isFinite() && gaugeMeters > 0.0) { "轨距必须是有限正数" }
        require(gravityMetersPerSecondSquared.isFinite() && gravityMetersPerSecondSquared > 0.0) {
            "重力加速度必须是有限正数"
        }
    }
    enum class SuperelevationTransition {
        LINEAR,      // 线性过渡
        SINE_KLEIN   // EN 13803 Sine/Klein 过渡，形状系数 qN = 2
    }

    /**
     * 计算指定速度下的超高值（米）。
     * - autoCalculate = true：h(mm) ≈ 11.3·v²/R(m)，最大值受 maxSuperelevation 限制
     * - autoCalculate = false：直接使用 maxSuperelevation（如果 > 0）
     */
    fun calculateSuperelevation(radius: Double): Double {
        if (radius <= 0) return 0.0
        if (autoCalculate) {
            return StandardRailCurveBuilder.calculateSuperelevation(
                designSpeedKmH, radius, maxSuperelevation, gaugeMeters, gravityMetersPerSecondSquared
            )
        }
        return if (maxSuperelevation > 0) maxSuperelevation else 0.0
    }
}

/**
 * 标准轨道曲线：回旋线（缓和曲线）+ 圆弧 + 回旋线
 *
 * 采用有限差分法（Euler 步进）计算曲线上的点，从起点切向角积分。
 * 坐标系：X 轴为前进方向，Y 轴向上，Z 轴横向（右手系）。
 * 外轨在曲线左侧（正 Z），内轨在右侧（负 Z）。
 *
 * @param R 圆曲线半径（米），> 0
 * @param L 单端回旋线长度（米）
 * @param Lc 圆弧段长度（米），中间圆曲线部分
 * @param superelevationConfig 外轨超高配置，null 表示不使用超高
 */
class StandardRailCurve(
    private val R: Double,
    private val L: Double,
    private val Lc: Double,
    private val superelevationConfig: SuperelevationConfig? = null
) {
    // 回旋线参数 A = sqrt(R * L)，其中 L 为单端缓和曲线长度
    val A: Double = sqrt(R * L)

    /** 轨道总长度：两段缓和曲线 + 圆曲线 */
    val totalLength: Double = 2 * L + Lc

    /** 按半径和设计速度计算、尚未应用变化率限制的目标超高值（米）。 */
    val requestedSuperelevation: Double = superelevationConfig?.calculateSuperelevation(R) ?: 0.0

    /**
     * 实际超高值（米）。缓和曲线不足时降低超高，保证完整过渡中的瞬时变化率不超过配置上限。
     * Sine/Klein 的峰值导数是平均导数的 2 倍；线性过渡的峰值因子为 1。
     */
    val superelevation: Double = minOf(requestedSuperelevation, maximumSuperelevationForTransition())

    init {
        require(R > 0) { "半径 R 必须大于 0" }
        require(L >= 0) { "回旋线长度 L 必须非负" }
        require(Lc >= 0) { "圆弧长度 Lc 必须非负" }
    }

    private fun maximumSuperelevationForTransition(): Double {
        val config = superelevationConfig ?: return 0.0
        if (L <= 0.0 || config.maxCantGradient <= 0.0) return 0.0
        val peakDerivativeFactor = when (config.superelevationTransition) {
            SuperelevationConfig.SuperelevationTransition.LINEAR -> 1.0
            SuperelevationConfig.SuperelevationTransition.SINE_KLEIN -> 2.0
        }
        return config.maxCantGradient * L / peakDerivativeFactor
    }

    companion object {
        private val GAUSS_16_NODES = doubleArrayOf(
            -0.9894009349916499, -0.9445750230732326, -0.8656312023878318, -0.7554044083550030,
            -0.6178762444026438, -0.4580167776572274, -0.2816035507792589, -0.0950125098376374,
            0.0950125098376374, 0.2816035507792589, 0.4580167776572274, 0.6178762444026438,
            0.7554044083550030, 0.8656312023878318, 0.9445750230732326, 0.9894009349916499
        )
        private val GAUSS_16_WEIGHTS = doubleArrayOf(
            0.0271524594117541, 0.0622535239386479, 0.0951585116824928, 0.1246289712555339,
            0.1495959888165767, 0.1691565193950025, 0.1826034150449236, 0.1894506104550685,
            0.1894506104550685, 0.1826034150449236, 0.1691565193950025, 0.1495959888165767,
            0.1246289712555339, 0.0951585116824928, 0.0622535239386479, 0.0271524594117541
        )
    }

    /**
     * 计算弧长 s 处的曲率 κ
     *
     * 入口回旋线（0 ≤ s ≤ L）：κ = s / (R·L) 线性从 0 到 1/R
     * 圆曲线（L ≤ s ≤ L+Lc）：κ = 1/R
     * 出口回旋线（L+Lc ≤ s ≤ 2L+Lc）：κ = (2L + Lc - s) / (R·L) 线性从 1/R 到 0
     */
    fun curvatureAt(s: Double): Double {
        if (L == 0.0) return if (s >= 0 && s <= Lc) 1.0 / R else 0.0
        return when {
            s < 0 -> 0.0
            s <= L -> s / (R * L)
            s <= L + Lc -> 1.0 / R
            s <= 2 * L + Lc -> (2.0 * L + Lc - s) / (R * L)
            else -> 0.0
        }
    }

    /**
     * 计算弧长 s 处的超高值（米）。
     * 在缓和曲线段从 0 线性或按 Sine/Klein 曲线过渡到 superelevation，在圆曲线段保持 superelevation。
     *
     * @param s 弧长
     * @return 超高值（米），出口回旋线段从 superelevation 渐变回 0
     */
    fun superelevationAt(s: Double): Double {
        if (s < 0 || s > totalLength || superelevation <= 0.0) return 0.0
        if (L == 0.0) return superelevation

        fun transitionFraction(t: Double): Double {
            val clamped = t.coerceIn(0.0, 1.0)
            return when (superelevationConfig?.superelevationTransition) {
                SuperelevationConfig.SuperelevationTransition.SINE_KLEIN ->
                    // EN 13803 Sine/Klein: f(t) = t - sin(2πt)/(2π), qN = 2。
                    clamped - sin(2.0 * Math.PI * clamped) / (2.0 * Math.PI)
                else -> clamped
            }
        }

        return when {
            // 入口回旋线：在完整 L 内按所选过渡曲线从 0 增长到设计超高。
            s < L -> superelevation * transitionFraction(s / L)
            // 圆曲线段保持设计超高。
            s <= L + Lc -> superelevation
            // 出口回旋线使用入口过渡的镜像，保证两端变化率一致。
            else -> superelevation * (1.0 - transitionFraction((s - L - Lc) / L))
        }
    }

    /**
     * 计算弧长 s 处的超高角（弧度）。
     * 超高角 = arctan(h / 轨距)，其中轨距默认 1.435m。
     *
     * @param s 弧长
     * @param gauge 轨距（米），默认 1.435m
     * @return 超高角（弧度）
     */
    fun superelevationAngleAt(
        s: Double,
        gauge: Double = superelevationConfig?.gaugeMeters ?: StandardRailCurveBuilder.DEFAULT_GAUGE
    ): Double {
        return kotlin.math.atan2(superelevationAt(s), gauge)
    }

    /**
     * 计算弧长 s 处的切向角 θ（弧度）
     *
     * θ(s) = ∫₀ˢ κ(t) dt，通过分段积分计算。
     */
    fun tangentAngleAt(s: Double): Double {
        if (L == 0.0) return if (s > 0 && s <= Lc) s / R else 0.0
        if (s >= totalLength - 1e-10) return totalTangentAngle()
        when {
            s <= 0 -> return 0.0
            // 入口回旋线（0 到 L）
            s <= L -> return s * s / (2.0 * A * A)
            // 圆曲线（L 到 L+Lc）
            s <= L + Lc -> {
                val thetaAtEndOfSpiral = L * L / (2.0 * A * A)
                return thetaAtEndOfSpiral + (s - L) / R
            }
            // 出口回旋线（L+Lc 到 2L+Lc）
            else -> {
                val thetaAtEndOfSpiral = L * L / (2.0 * A * A)
                val arcAngle = Lc / R
                val u = s - (L + Lc) // 从 0 到 L
                // κ = (L-u)/(R·L)，积分 ∫₀ᵘ (L-t)/(R·L) dt = u/R - u²/(2R·L)
                return thetaAtEndOfSpiral + arcAngle + u / R - u * u / (2.0 * R * L)
            }
        }
    }

    /** 终点总切向角 */
    private fun totalTangentAngle(): Double {
        val thetaAtEndOfSpiral = L * L / (2.0 * A * A)
        val arcAngle = Lc / R
        val u = L
        return thetaAtEndOfSpiral + arcAngle + u / R - u * u / (2.0 * R * L)
    }

    /**
     * 工程精确位置计算。使用分段 16 点 Gauss-Legendre 积分计算
     * ∫cos(theta(s))ds / ∫sin(theta(s))ds，避免固定小步长积分带来的端点漂移和 O(L/step) 成本。
     */
    fun positionAtAccurate(s: Double, maxChunkLength: Double = 2.0): Vector3d {
        val target = s.coerceIn(0.0, totalLength)
        if (target <= 0.0) return Vector3d(0.0, 0.0, 0.0)
        val chunks = maxOf(1, ceil(target / maxChunkLength).toInt())
        val chunkLength = target / chunks
        var x = 0.0
        var z = 0.0
        var a = 0.0
        repeat(chunks) {
            val b = if (it == chunks - 1) target else a + chunkLength
            x += integrateGaussLegendre16(a, b) { arc -> cos(tangentAngleAt(arc)) }
            z += integrateGaussLegendre16(a, b) { arc -> sin(tangentAngleAt(arc)) }
            a = b
        }
        return Vector3d(x, 0.0, z)
    }

    fun pointAtAccurate(s: Double): CurvePoint {
        val arc = s.coerceIn(0.0, totalLength)
        return CurvePoint(positionAtAccurate(arc), tangentAngleAt(arc), superelevationAt(arc))
    }

    private fun integrateGaussLegendre16(a: Double, b: Double, f: (Double) -> Double): Double {
        if (b <= a) return 0.0
        val mid = (a + b) * 0.5
        val half = (b - a) * 0.5
        var sum = 0.0
        for (i in GAUSS_16_NODES.indices) {
            sum += GAUSS_16_WEIGHTS[i] * f(mid + half * GAUSS_16_NODES[i])
        }
        return half * sum
    }

    /**
     * 生成轨道点列表（局部坐标系，Y 坡度 = 0）
     *
     * @param step 采样步长（米）
     * @return 点列表
     */
    fun generatePoints(step: Double = 1e-3): List<CurveRawPoint> {
        require(step > 0.0) { "step must be positive" }

        val points = mutableListOf<CurveRawPoint>()
        var s = 0.0
        var x = 0.0
        var z = 0.0

        fun addPoint(arc: Double, px: Double, pz: Double) {
            val theta = tangentAngleAt(arc)
            val tangent = Vector3d(cos(theta), 0.0, sin(theta))

            // 超高角：外轨在正 Z 方向倾斜
            val superelAngle = superelevationAngleAt(arc)
            // 法线：考虑超高的轨道平面法线（绕 X 轴旋转 superelAngle）
            val normal = Vector3d(0.0, cos(superelAngle), sin(superelAngle))

            points.add(CurveRawPoint(
                Vector3d(px, 0.0, pz),
                tangent,
                normal,
                arc
            ))
        }

        // 每个采样区间独立做 Gauss-Legendre 积分，保持 O(L/step)，同时避免左端点欧拉积分
        // 累积漂移。generateCurve 会用精确端点替换最后一个点；若内部点存在累积漂移，替换会在
        // 最后一个采样段制造明显折角。
        while (s < totalLength - 1e-9) {
            addPoint(s, x, z)
            val nextS = minOf(s + step, totalLength)
            x += integrateGaussLegendre16(s, nextS) { arc -> cos(tangentAngleAt(arc)) }
            z += integrateGaussLegendre16(s, nextS) { arc -> sin(tangentAngleAt(arc)) }
            s = nextS
        }

        addPoint(totalLength, x, z)
        return points
    }

    /**
     * 将局部坐标系中的点转换到全局坐标系。
     *
     * @param localPoint 局部坐标点（X=前进, Z=横向）
     * @param origin 起点全局坐标
     * @param startDirection 起点切向方向（单位向量，水平面）
     * @param leftTurn true=左转（Z 轴指向切向左侧），false=右转（默认，Z 轴指向切向右侧）
     */
    fun toGlobal(
        localPoint: CurveRawPoint,
        origin: Vector3dc,
        startDirection: Vector3dc,
        leftTurn: Boolean = false
    ): CurveRawPoint {
        val globalUp = Vector3d(0.0, 1.0, 0.0)
        // 横向向量: 右转 = startDirection × up, 左转 = up × startDirection
        val lateral = if (leftTurn)
            Vector3d(globalUp).cross(startDirection).normalize()
        else
            Vector3d(startDirection).cross(globalUp).normalize()

        val lx = localPoint.position.x()
        val lz = localPoint.position.z()

        val globalPos = Vector3d(
            origin.x() + lx * startDirection.x() + lz * lateral.x(),
            origin.y() + localPoint.position.y(),
            origin.z() + lx * startDirection.z() + lz * lateral.z()
        )

        // 切线方向旋转
        val tx = localPoint.tangent.x() * startDirection.x() + localPoint.tangent.z() * lateral.x()
        val tz = localPoint.tangent.x() * startDirection.z() + localPoint.tangent.z() * lateral.z()
        val globalTangent = Vector3d(tx, localPoint.tangent.y(), tz).normalize()

        // 法线旋转
        val nx = localPoint.normal.x() * startDirection.x() + localPoint.normal.z() * lateral.x()
        val ny = localPoint.normal.y()
        val nz = localPoint.normal.x() * startDirection.z() + localPoint.normal.z() * lateral.z()
        val globalNormal = Vector3d(nx, ny, nz).normalize()

        return CurveRawPoint(globalPos, globalTangent, globalNormal, localPoint.arcLength)
    }

    /**
     * 轨道上的单点（局部坐标系）
     */
    data class CurveRawPoint(
        val position: Vector3dc,  // 坐标
        val tangent: Vector3dc,    // 切向（方向向量）
        val normal: Vector3dc,    // 法线（轨道平面向量）
        val arcLength: Double     // 弧长
    )

    /**
     * 弧长 s 处的曲线信息
     */
    data class CurvePoint(
        val position: Vector3d,   // 坐标
        val tangentAngle: Double, // 切向角（弧度）
        val superelevation: Double // 超高值（米）
    )
}

object StandardRailCurveBuilder {
    /** 默认轨距（米） */
    const val DEFAULT_GAUGE = 1.435

    /** 默认重力加速度（m/s²） */
    const val DEFAULT_GRAVITY = 9.80665

    /** 默认最大超高（米） */
    const val DEFAULT_MAX_SUPERELEVATION = 0.15

    /**
     * 计算指定速度和半径下的超高值
     *
     * @param speedKmH 速度（km/h）
     * @param radius 曲线半径（m）
     * @param maxSuperelevation 最大超高（m），默认 150mm
     * @return 超高值（m）
     */
    fun calculateSuperelevation(
        speedKmH: Double,
        radius: Double,
        maxSuperelevation: Double = DEFAULT_MAX_SUPERELEVATION,
        gaugeMeters: Double = DEFAULT_GAUGE,
        gravityMetersPerSecondSquared: Double = DEFAULT_GRAVITY
    ): Double {
        if (speedKmH <= 0 || radius <= 0) return 0.0
        val speedMetersPerSecond = speedKmH / 3.6
        val heightMeters = gaugeMeters * speedMetersPerSecond * speedMetersPerSecond /
            (gravityMetersPerSecondSquared * radius)
        return minOf(heightMeters, maxSuperelevation)
    }
}