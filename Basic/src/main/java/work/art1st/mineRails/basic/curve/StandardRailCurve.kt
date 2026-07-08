package work.art1st.mineRails.basic.curve

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
 * @param superelevationTransition 超高过渡方式。true = 余弦过渡（更平滑），false = 线性过渡
 * @param autoCalculate 自动计算超高值：h(mm) ≈ 11.3·v²(km/h)/R(m)。若速度 ≤ 0 则不使用超高
 */
data class SuperelevationConfig(
    val maxSuperelevation: Double = 0.15,
    val superelevationTransition: SuperelevationTransition = SuperelevationTransition.COSINE,
    val autoCalculate: Boolean = true,
    val designSpeedKmH: Double = 0.0
) {
    enum class SuperelevationTransition {
        LINEAR,     // 线性过渡（简单）
        COSINE      // 余弦过渡（更平滑，高速铁路常用）
    }

    /**
     * 计算指定速度下的超高值（米）。
     * - autoCalculate = true：h(mm) ≈ 11.3·v²/R(m)，最大值受 maxSuperelevation 限制
     * - autoCalculate = false：直接使用 maxSuperelevation（如果 > 0）
     */
    fun calculateSuperelevation(radius: Double): Double {
        if (radius <= 0) return 0.0
        if (autoCalculate && designSpeedKmH > 0) {
            val hMm = 11.3 * designSpeedKmH * designSpeedKmH / radius
            return minOf(hMm / 1000.0, maxSuperelevation)
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

    /** 设计超高值（米），通过 autoCalculate 或手动设置获取 */
    val superelevation: Double = superelevationConfig?.calculateSuperelevation(R) ?: 0.0

    init {
        require(R > 0) { "半径 R 必须大于 0" }
        require(L >= 0) { "回旋线长度 L 必须非负" }
        require(Lc >= 0) { "圆弧长度 Lc 必须非负" }
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
     * 在缓和曲线段从 0 线性或余弦过渡到 superelevation，在圆曲线段保持 superelevation。
     *
     * @param s 弧长
     * @return 超高值（米），出口回旋线段从 superelevation 渐变回 0
     */
    fun superelevationAt(s: Double): Double {
        if (superelevation <= 0 || L == 0.0) return 0.0
        if (s < 0 || s > totalLength) return 0.0

        return when {
            // 入口回旋线（0 到 L）：超高从 0 过渡到 superelevation
            s < L -> {
                when (superelevationConfig?.superelevationTransition) {
                    SuperelevationConfig.SuperelevationTransition.COSINE -> {
                        val t = s / L  // 0 到 1
                        superelevation * (t - sin(2.0 * Math.PI * t) / (2.0 * Math.PI))
                    }
                    else -> superelevation * s / L  // 线性
                }
            }
            // 圆曲线（L 到 L+Lc）：保持 superelevation
            s <= L + Lc -> superelevation
            // 出口回旋线（L+Lc 到 2L+Lc）：超高从 superelevation 过渡到 0
            else -> {
                val u = s - (L + Lc)  // 0 到 L
                when (superelevationConfig?.superelevationTransition) {
                    SuperelevationConfig.SuperelevationTransition.COSINE -> {
                        val t = u / L  // 0 到 1
                        superelevation * (1.0 - t + sin(2.0 * Math.PI * t) / (2.0 * Math.PI))
                    }
                    else -> superelevation * (1.0 - u / L)  // 线性
                }
            }
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
    fun superelevationAngleAt(s: Double, gauge: Double = 1.435): Double {
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
     * 用有限差分法计算弧长 s 处的坐标（局部坐标系，起点原点，切线 +X）
     *
     * @param s 弧长
     * @param step 积分步长（米）
     * @return 坐标 (x, 0, z)，z 始终为 0（平面曲线，坡度需叠加）
     */
    fun positionAt(s: Double, step: Double = 0.001): Vector3d {
        if (s <= 0) return Vector3d(0.0, 0.0, 0.0)

        var x = 0.0
        var z = 0.0
        var sCur = 0.0

        while (sCur < s) {
            val ds = minOf(step, s - sCur)
            val theta = tangentAngleAt(sCur)
            // 沿切向方向前进 ds
            x += cos(theta) * ds
            z += sin(theta) * ds
            sCur += ds
        }

        return Vector3d(x, 0.0, z)
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
     * 在弧长 s 处获取轨道点信息
     *
     * @param s 弧长（米）
     * @return CurvePoint(position, tangentAngle, superelevation)
     */
    fun pointAt(s: Double): CurvePoint {
        if (s <= 0) {
            return CurvePoint(Vector3d(0.0, 0.0, 0.0), 0.0, superelevationAt(0.0))
        }
        if (s >= totalLength) {
            val theta = tangentAngleAt(totalLength)
            val pos = positionAt(totalLength, 0.001)
            return CurvePoint(Vector3d(pos.x, 0.0, pos.z), theta, superelevationAt(totalLength))
        }

        val theta = tangentAngleAt(s)
        val pos = positionAt(s, 0.001)
        return CurvePoint(Vector3d(pos.x, 0.0, pos.z), theta, superelevationAt(s))
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
            val superelAngle = kotlin.math.atan2(superelevationAt(arc), 1.435)
            // 法线：考虑超高的轨道平面法线（绕 X 轴旋转 superelAngle）
            val normal = Vector3d(0.0, cos(superelAngle), sin(superelAngle))

            points.add(CurveRawPoint(
                Vector3d(px, 0.0, pz),
                tangent,
                normal,
                arc
            ))
        }

        // 旧实现每个采样点都调用 pointAt(s)，而 pointAt 又从 0 重新积分到 s，
        // 总复杂度约 O((L/step)^2)，长曲线会在主线程建轨时卡死。
        // 这里沿弧长单次前进积分，保持与 positionAt 的左端点欧拉积分一致，复杂度 O(L/step)。
        while (s < totalLength - 1e-9) {
            addPoint(s, x, z)
            val ds = minOf(step, totalLength - s)
            val theta = tangentAngleAt(s)
            x += cos(theta) * ds
            z += sin(theta) * ds
            s += ds
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

/**
 * 超高过渡方式
 */
enum class SuperelevationTransition {
    /** 线性过渡（简单） */
    LINEAR,
    /** 余弦过渡（更平滑，高速铁路常用） */
    COSINE
}

object StandardRailCurveBuilder {
    /** 默认轨距（米） */
    const val DEFAULT_GAUGE = 1.435

    /** 默认最大超高（米） */
    const val DEFAULT_MAX_SUPERELEVATION = 0.15

    /**
     * 从参数构建标准轨道曲线（无超高）
     */
    fun build(R: Double, L: Double, Lc: Double): StandardRailCurve {
        return StandardRailCurve(R, L, Lc)
    }

    /**
     * 从参数构建标准轨道曲线（带超高）
     *
     * @param superelevation 超高值（米），null 表示 autoCalculate
     */
    fun build(
        R: Double,
        L: Double,
        Lc: Double,
        superelevationConfig: SuperelevationConfig
    ): StandardRailCurve {
        return StandardRailCurve(R, L, Lc, superelevationConfig)
    }

    /**
     * 从参数构建，并生成局部坐标系中的轨道点
     */
    fun buildAndGenerate(R: Double, L: Double, Lc: Double, step: Double = 1e-3): List<StandardRailCurve.CurveRawPoint> {
        return StandardRailCurve(R, L, Lc).generatePoints(step)
    }

    /**
     * 从参数构建，并生成局部坐标系中的轨道点（带超高）
     */
    fun buildAndGenerate(
        R: Double,
        L: Double,
        Lc: Double,
        superelevationConfig: SuperelevationConfig,
        step: Double = 1e-3
    ): List<StandardRailCurve.CurveRawPoint> {
        return StandardRailCurve(R, L, Lc, superelevationConfig).generatePoints(step)
    }

    /**
     * 从参数构建，并转换到全局坐标系
     */
    fun buildAndGenerateGlobal(
        origin: Vector3dc,
        startDirection: Vector3dc,
        R: Double,
        L: Double,
        Lc: Double,
        step: Double = 1e-3
    ): List<StandardRailCurve.CurveRawPoint> {
        val curve = StandardRailCurve(R, L, Lc)
        val localPoints = curve.generatePoints(step)
        return localPoints.map { curve.toGlobal(it, origin, startDirection) }
    }

    /**
     * 从参数构建，并转换到全局坐标系（带超高）
     */
    fun buildAndGenerateGlobal(
        origin: Vector3dc,
        startDirection: Vector3dc,
        R: Double,
        L: Double,
        Lc: Double,
        superelevationConfig: SuperelevationConfig,
        step: Double = 1e-3
    ): List<StandardRailCurve.CurveRawPoint> {
        val curve = StandardRailCurve(R, L, Lc, superelevationConfig)
        val localPoints = curve.generatePoints(step)
        return localPoints.map { curve.toGlobal(it, origin, startDirection) }
    }

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
        maxSuperelevation: Double = DEFAULT_MAX_SUPERELEVATION
    ): Double {
        if (speedKmH <= 0 || radius <= 0) return 0.0
        val hMm = 11.3 * speedKmH * speedKmH / radius
        return minOf(hMm / 1000.0, maxSuperelevation)
    }
}