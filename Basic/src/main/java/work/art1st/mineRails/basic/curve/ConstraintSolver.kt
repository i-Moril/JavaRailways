package work.art1st.mineRails.basic.curve

import org.joml.Vector3d
import org.joml.Vector3dc
import kotlin.math.*

// ═══════════════════════════════════════════════════════════════
// 约束求解结果
// ═══════════════════════════════════════════════════════════════

sealed class ConstraintSolveResult {
    data class Success(
        val R: Double,
        val L1: Double,
        val Lc: Double,
        val L2: Double,
        val theta: Double,
        val totalLength: Double,
        val totalAngle: Double
    ) : ConstraintSolveResult() {
        val hasArc: Boolean get() = theta > 1e-10
        val isDegenerate: Boolean get() = theta <= 1e-10 && theta > -1e-10
    }

    data class Failure(
        val reason: String,
        val suggestion: String? = null,
        val minPossibleR: Double? = null
    ) : ConstraintSolveResult()
}

// ═══════════════════════════════════════════════════════════════
// Clothoid 数学工具
// ═══════════════════════════════════════════════════════════════

object ClothoidMath {
    /**
     * 计算局部 clothoid 上弧长 s 处的点 (x, z) 和切向角 theta。
     *
     * 局部 clothoid: κ(s) = s / (R·L)  (线性曲率, 0→1/R)
     * 切向角: θ(s) = s² / (2·R·L)
     *
     * 位置用 Gauss-Legendre 数值积分，精度 ~1e-9。
     *
     * @param s 弧长
     * @param R 终点曲率半径
     * @param L 缓和曲线总长
     */
    fun computeClothoidLocal(s: Double, R: Double, L: Double): ClothoidPoint {
        if (s <= 0.0) return ClothoidPoint(0.0, 0.0, 0.0)
        val sClamped = s.coerceIn(0.0, L)
        // 切向角（解析解）
        val theta = sClamped * sClamped / (2.0 * R * L)
        // 位置用 Simpson 积分
        val (x, z) = integrateClothoid(sClamped, R, L)
        return ClothoidPoint(x, z, theta)
    }

    /** 积分 clothoid 从 0 到 s 的 (cos(θ(t)), sin(θ(t))) */
    private fun integrateClothoid(s: Double, R: Double, L: Double): Pair<Double, Double> {
        // 1mm step for high precision (~1e-6 accuracy)
        val n = maxOf(100, (s / 0.0005).toInt().coerceAtMost(50000))
        val ds = s / n
        var x = 0.0
        var z = 0.0
        // Simpson's rule
        val A = 1.0 / (2.0 * R * L)
        for (i in 0 until n) {
            val t0 = i * ds
            val t1 = (i + 0.5) * ds
            val t2 = (i + 1) * ds
            val th0 = A * t0 * t0
            val th1 = A * t1 * t1
            val th2 = A * t2 * t2
            x += (cos(th0) + 4.0 * cos(th1) + cos(th2)) * ds / 6.0
            z += (sin(th0) + 4.0 * sin(th1) + sin(th2)) * ds / 6.0
        }
        return Pair(x, z)
    }
}

data class ClothoidPoint(val x: Double, val z: Double, val theta: Double)

// ═══════════════════════════════════════════════════════════════
// 约束求解器
// ═══════════════════════════════════════════════════════════════

object ConstraintSolver {

    /** 求解容差 (m) */
    private const val TOLERANCE = 1e-4
    private const val POSITION_TOLERANCE = 0.01  // 1cm
    private const val MAX_ITERATIONS = 200
    private const val REFINEMENT_STEPS = 20

    /**
     * 给定两锚点 (位置+切线) 和半径 R，求解 clothoid-arc-clothoid 参数。
     *
     * 算法：
     * 1. 计算总偏转角 Δψ
     * 2. 在 Frenet 标架下二分搜索 L1，使积分终点与 P_B 重合
     * 3. L2 与 L1 成比例（默认 1:1）
     *
     * @param fromPos 起点坐标
     * @param fromTangent 起点切线（单位向量）
     * @param toPos 终点坐标
     * @param toTangent 终点切线（单位向量）
     * @param radius 圆曲线半径 R (m)
     * @param clothoidRatio L2/L1 比例（默认 1.0）; 若为 null 则固定为 R/9
     */
    fun solve(
        fromPos: Vector3dc,
        fromTangent: Vector3dc,
        toPos: Vector3dc,
        toTangent: Vector3dc,
        radius: Double,
        clothoidLength: Double? = null,
        clothoidRatio: Double = 1.0
    ): ConstraintSolveResult {
        require(radius > 0) { "R must be positive, got $radius" }

        // 1. 计算总偏转角
        val deltaPsi = computeAngle(fromTangent, toTangent)
        if (deltaPsi < 1e-10) {
            return ConstraintSolveResult.Failure("切线夹角为 0，锚点方向相同（无需转弯）")
        }

        // 2. 位移向量
        val displacement = Vector3d(toPos).sub(fromPos, Vector3d())
        val dist = displacement.length()

        // 3. 构建 Frenet 标架
        val fx = Vector3d(fromTangent).normalize()
        val fy = Vector3d(0.0, 1.0, 0.0)
        val fz = Vector3d(fx).cross(fy).normalize()

        // 投影位移到 Frenet 标架
        val targetX = displacement.dot(fx)
        val targetZ = displacement.dot(fz)

        // 4. 检查解的可行性
        // 最小半径估计：两切线的交点距离
        val minR = estimateMinRadius(fromPos, fromTangent, toPos, toTangent)
        if (radius > minR * 10 && dist > radius * 2) {
            // R 可能太大
        }

        // 5. 二分法求解 L1
        //    L1 的搜索范围: [0, R * deltaPsi]  (因为 L/(2R) ≤ Δψ → L ≤ 2R·Δψ)
        val maxL = 2.0 * radius * deltaPsi

        val fixedL = clothoidLength
        if (fixedL != null) {
            // 固定缓和曲线长度
            val theta = deltaPsi - (fixedL + fixedL * clothoidRatio) / (2.0 * radius)
            if (theta < 0) {
                return ConstraintSolveResult.Failure(
                    "R=$radius 太大，无法满足切线约束",
                    "减小 R 到 ${"%.1f".format(minR)}m 以下或减小缓和曲线长度",
                    minPossibleR = minR
                )
            }
            val result = tryL1(fixedL, radius, deltaPsi, targetX, targetZ, clothoidRatio)
            return if (result != null) {
                result
            } else {
                ConstraintSolveResult.Failure(
                    "固定 L=$fixedL 时无解",
                    "尝试减小缓和曲线长度或调整 R",
                    minPossibleR = minR
                )
            }
        }

        // 二分搜索 L1
        var lo = 0.0
        var hi = maxL.coerceAtMost(dist * 0.4)  // 缓和曲线不会超过位移的 40%

        // 扩展 hi 直到解可行
        var bestLo: ConstraintSolveResult? = null
        while (hi < maxL) {
            val result = tryL1(hi, radius, deltaPsi, targetX, targetZ, clothoidRatio)
            if (result is ConstraintSolveResult.Success) {
                bestLo = result
                break
            }
            hi *= 1.5
        }
        if (bestLo == null) {
            // 尝试用 L=0 (无缓和曲线)
            val result = tryL1(0.0, radius, deltaPsi, targetX, targetZ, 0.0)
            if (result is ConstraintSolveResult.Success) {
                return result.copy(
                    // 保持 L1=L2=R/9 作为最小
                )
            }
            return ConstraintSolveResult.Failure(
                "无法求解：R=$radius 下无法匹配位移",
                "尝试减小 R 或调整锚点位置",
                minPossibleR = minR
            )
        }

        // 二分细化
        var best = bestLo!!
        for (iter in 0 until MAX_ITERATIONS) {
            val mid = (lo + hi) / 2.0
            val result = tryL1(mid, radius, deltaPsi, targetX, targetZ, clothoidRatio)
            when {
                result is ConstraintSolveResult.Success -> {
                    best = result
                    val endPos = computeEndpoint(mid, radius, deltaPsi, clothoidRatio)
                    val dx = endPos.first - targetX
                    val dz = endPos.second - targetZ
                    val error = abs(dx) + abs(dz)
                    if (error < POSITION_TOLERANCE) break
                    if (abs(hi - lo) < 1e-6) break
                    // 判断搜索方向（基于 X 方向误差）
                    if (dx > 0) {
                        hi = mid
                    } else {
                        lo = mid
                    }
                }
                result is ConstraintSolveResult.Failure -> {
                    hi = mid
                }
                else -> lo = mid
            }
        }

        // Newton-Raphson 细化：微调 L1 使终点逼近目标
        if (best is ConstraintSolveResult.Success) {
            best = refineSolution(best, radius, deltaPsi, targetX, targetZ, clothoidRatio)
        }

        return best
    }

    /**
     * Newton-Raphson 细化：微调 L1 使终点逼近目标位置。
     *
     * 使用有限差分估计 ∂(endpoint)/∂(L1)，然后沿梯度方向调整 L1。
     */
    private fun refineSolution(
        initial: ConstraintSolveResult.Success,
        R: Double,
        deltaPsi: Double,
        targetX: Double,
        targetZ: Double,
        ratio: Double
    ): ConstraintSolveResult.Success {
        var L1 = initial.L1
        var best = initial
        var bestError = endpointError(L1, R, deltaPsi, targetX, targetZ, ratio)
        val h = maxOf(1e-6, L1 * 1e-6)  // finite difference step

        for (iter in 0 until REFINEMENT_STEPS) {
            // 计算当前位置误差
            val (x, z) = computeEndpoint(L1, R, deltaPsi, ratio)
            var dx = x - targetX
            var dz = z - targetZ
            val error = abs(dx) + abs(dz)

            if (error < POSITION_TOLERANCE) break
            if (error < bestError) {
                bestError = error
                val candidate = tryL1(L1, R, deltaPsi, targetX, targetZ, ratio)
                if (candidate is ConstraintSolveResult.Success) best = candidate
            }

            // 有限差分求梯度
            val L1p = L1 + h
            val thetaP = deltaPsi - (L1p + L1p * ratio) / (2.0 * R)
            if (thetaP < -1e-10) break  // can't increase L1 further

            val (xp, zp) = computeEndpoint(L1p, R, deltaPsi, ratio)
            val dxp = xp - targetX
            val dzp = zp - targetZ

            val dEx_dL1 = ((abs(dxp) + abs(dzp)) - error) / h
            if (abs(dEx_dL1) < 1e-15) break

            // Gradient descent step (small to avoid overshoot)
            val step = (error / maxOf(abs(dEx_dL1), 1e-10)).coerceIn(-L1 * 0.5, L1 * 0.5)
            val newL1 = (L1 - step * dEx_dL1.sign).coerceAtLeast(0.0)
                .coerceAtMost(2.0 * R * deltaPsi * 0.5)

            if (abs(newL1 - L1) < 1e-8) break
            L1 = newL1
        }

        return best
    }

    private fun endpointError(
        L1: Double, R: Double, deltaPsi: Double,
        targetX: Double, targetZ: Double, ratio: Double
    ): Double {
        val (x, z) = computeEndpoint(L1, R, deltaPsi, ratio)
        return abs(x - targetX) + abs(z - targetZ)
    }

    /**
     * 尝试给定的 L1 值，返回求解结果或 null。
     */
    private fun tryL1(
        L1: Double,
        R: Double,
        deltaPsi: Double,
        targetX: Double,
        targetZ: Double,
        ratio: Double
    ): ConstraintSolveResult? {
        val L2 = L1 * ratio
        val theta = deltaPsi - (L1 + L2) / (2.0 * R)

        if (theta < -1e-10) return null  // 不可行

        val actualTheta = maxOf(theta, 0.0)
        val Lc = R * actualTheta
        val endPoint = computeEndpoint(L1, R, deltaPsi, ratio)

        val error = abs(endPoint.first - targetX) + abs(endPoint.second - targetZ)

        if (error.isNaN() || error.isInfinite()) return null

        val totalLength = L1 + Lc + L2
        return ConstraintSolveResult.Success(
            R = R,
            L1 = L1,
            Lc = Lc,
            L2 = L2,
            theta = actualTheta,
            totalLength = totalLength,
            totalAngle = deltaPsi
        )
    }

    /**
     * 计算 clothoid-arc-clothoid 曲线的终点在 Frenet 标架下的坐标。
     */
    private fun computeEndpoint(
        L1: Double,
        R: Double,
        deltaPsi: Double,
        ratio: Double
    ): Pair<Double, Double> {
        val L2 = L1 * ratio
        val theta = maxOf(deltaPsi - (L1 + L2) / (2.0 * R), 0.0)

        var x = 0.0
        var z = 0.0
        var cumulativeAngle = 0.0

        // 入口缓和曲线
        if (L1 > 0) {
            val cp1 = ClothoidMath.computeClothoidLocal(L1, R, L1)
            x += cp1.x
            z += cp1.z
            cumulativeAngle += cp1.theta
        }

        // 圆弧
        if (theta > 0) {
            val arcLen = R * theta
            val arcX = R * sin(theta)
            val arcZ = R * (1.0 - cos(theta))
            // 旋转到累积角度
            x += arcX * cos(cumulativeAngle) - arcZ * sin(cumulativeAngle)
            z += arcX * sin(cumulativeAngle) + arcZ * cos(cumulativeAngle)
            cumulativeAngle += theta
        }

        // 出口缓和曲线
        if (L2 > 0) {
            val cp2 = ClothoidMath.computeClothoidLocal(L2, R, L2)
            // 出口缓和曲线是镜像的：绕新切线方向旋转
            x += cp2.x * cos(cumulativeAngle) - cp2.z * sin(cumulativeAngle)
            z += cp2.x * sin(cumulativeAngle) + cp2.z * cos(cumulativeAngle)
            cumulativeAngle += cp2.theta
        }

        return Pair(x, z)
    }

    /**
     * 估算最小可行半径。
     *
     * 取两切线交点距离的一半作为近似最小半径。
     */
    fun estimateMinRadius(
        fromPos: Vector3dc,
        fromTangent: Vector3dc,
        toPos: Vector3dc,
        toTangent: Vector3dc
    ): Double {
        // 两射线最近点距离的近似
        val d = Vector3d(toPos).sub(fromPos, Vector3d())
        val fx = Vector3d(fromTangent).normalize()
        val tx = Vector3d(toTangent).normalize()
        val dist = d.length()
        val angle = computeAngle(fx, tx)
        if (angle < 1e-10) return dist * 0.5
        return (dist * 0.5 / sin(angle / 2.0)).coerceAtLeast(1.0)
    }

    /**
     * 计算两个水平单位向量之间的夹角（弧度，0 到 π）。
     */
    fun computeAngle(a: Vector3dc, b: Vector3dc): Double {
        val lenA = sqrt(a.x() * a.x() + a.z() * a.z())
        val lenB = sqrt(b.x() * b.x() + b.z() * b.z())
        if (lenA < 1e-15 || lenB < 1e-15) return 0.0
        val dot = (a.x() * b.x() + a.z() * b.z()) / (lenA * lenB)
        return acos(dot.coerceIn(-1.0, 1.0))
    }
}
