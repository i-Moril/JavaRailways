package work.art1st.javarailways.basic.curve

import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.javarailways.core.api.physics.curve.Curve
import kotlin.math.*

sealed class ConstraintSolveResult {
    data class Success(
        val R: Double, val straightA: Double,
        val L1: Double, val Lc: Double, val L2: Double,
        val straightB: Double, val theta: Double,
        val totalLength: Double, val totalAngle: Double,
        val leftTurn: Boolean = false
    ) : ConstraintSolveResult()
    data class Failure(val reason: String) : ConstraintSolveResult()
}

object ConstraintSolver {

    /**
     * 给定 R，二分搜索 L1 使 straightA≥0 且 straightB≥0，误差=0。
     */
    fun solve(
        fromPos: Vector3dc, fromTangent: Vector3dc,
        toPos: Vector3dc, toTangent: Vector3dc,
        radius: Double
    ): ConstraintSolveResult {
        require(radius > 0)

        val dp = computeAngle(fromTangent, toTangent)
        if (dp < 1e-10) return ConstraintSolveResult.Failure("切线夹角为 0")

        var (tx, tz) = toLocalFrame(toPos, fromPos, fromTangent)
        val leftTurn = tz < 0
        if (leftTurn) tz = -tz  // 镜像为右转求解
        val sd = sin(dp); val cd = cos(dp)
        val maxL = radius * dp

        // 检查 L=0 是否可行
        if (!straightOk(0.0, radius, dp, tx, tz, sd, cd))
            return ConstraintSolveResult.Failure("R=$radius 无法满足约束，尝试更大 R 或手动指定")

        // 二分找最大可行 L1
        var lo = 0.0; var hi = maxL
        if (straightOk(maxL, radius, dp, tx, tz, sd, cd)) {
            lo = maxL
        } else {
            for (i in 0 until 60) {
                val mid = (lo + hi) / 2
                if (straightOk(mid, radius, dp, tx, tz, sd, cd)) lo = mid else hi = mid
                if (hi - lo < 0.005) break
            }
        }
        return build(lo, radius, dp, tx, tz, sd, cd, leftTurn)
    }

    /**
     * 自动选 R: 50m 倍数，尽可能大。
     */
    fun solveBest(
        fromPos: Vector3dc, fromTangent: Vector3dc,
        toPos: Vector3dc, toTangent: Vector3dc
    ): ConstraintSolveResult {
        val dp = computeAngle(fromTangent, toTangent)
        if (dp < 1e-10) return ConstraintSolveResult.Failure("切线夹角为 0")
        val dist = fromPos.distance(toPos)
        val maxR = maxOf(((dist * 5) / 50).let { ceil(it).toInt() * 50 }, 50).toDouble()

        // 50m 倍数的候选
        var R = maxR
        while (R >= 50) {
            val result = solve(fromPos, fromTangent, toPos, toTangent, R)
            if (result is ConstraintSolveResult.Success) return result
            R -= 50
        }
        // 如果 50m 倍数都失败，尝试更小的半径（40, 30, 20, 10）
        for (r in listOf(40.0, 30.0, 20.0, 10.0)) {
            if (r > dist) continue // R 不应超过锚点间距（纯弧场合）
            val result = solve(fromPos, fromTangent, toPos, toTangent, r)
            if (result is ConstraintSolveResult.Success) return result
        }
        return ConstraintSolveResult.Failure("未找到可行半径 (${maxR}m→10m, 间距≈${"%.1f".format(dist)}m)")
    }

    /** 生成五段曲线点列，叠加抛物线竖曲线使切线匹配锚点坡度。 */
    fun generateCurve(
        r: ConstraintSolveResult.Success, fromPos: Vector3dc, fromTangent: Vector3dc,
        toPos: Vector3dc, toTangent: Vector3dc, step: Double = 0.2,
        fromRollDegrees: Double = 0.0, toRollDegrees: Double = 0.0,
        superelevationConfig: SuperelevationConfig? = null
    ): List<Curve.RawPoint> {
        val fx = Vector3d(fromTangent.x(), 0.0, fromTangent.z()).normalize()
        val fz = if (r.leftTurn)
            Vector3d(Vector3d(0.0, 1.0, 0.0)).cross(fx).normalize()
        else
            Vector3d(fx).cross(Vector3d(0.0, 1.0, 0.0)).normalize()
        val pts = mutableListOf<Curve.RawPoint>()
        var s = 0.0
        val totalLen = r.totalLength
        // 坡度: tangent.y / horizontal_norm
        val hLenA = sqrt(fromTangent.x()*fromTangent.x() + fromTangent.z()*fromTangent.z())
        val hLenB = sqrt(toTangent.x()*toTangent.x() + toTangent.z()*toTangent.z())
        val gA = if (hLenA > 1e-10) fromTangent.y() / hLenA else 0.0
        val gB = if (hLenB > 1e-10) toTangent.y() / hLenB else 0.0

        // 三次 Hermite 多项式: 满足 y(0)=y_A, y'(0)=g_A, y(L)=y_B, y'(L)=g_B
        // y(s) = y_A + g_A·s + c·s² + d·s³
        // c = (3U - V·L) / L²,  d = (V·L - 2U) / L³
        // 其中 U = Δy - g_A·L,  V = g_B - g_A
        val L = totalLen
        val dy = toPos.y() - fromPos.y()
        val U = dy - gA * L
        val V = gB - gA
        val c = if (L > 1e-6) (3.0 * U - V * L) / (L * L) else 0.0
        val d = if (L > 1e-6) (V * L - 2.0 * U) / (L * L * L) else 0.0

        fun gradeAt(arc: Double): Double = gA + 2.0 * c * arc + 3.0 * d * arc * arc
        fun heightAt(arc: Double): Double = fromPos.y() + gA * arc + c * arc * arc + d * arc * arc * arc
        fun rollAt(arc: Double): Double {
            val t = if (totalLen > 1e-9) (arc / totalLen).coerceIn(0.0, 1.0) else 0.0
            return Math.toRadians(fromRollDegrees + (toRollDegrees - fromRollDegrees) * t)
        }

        fun applyRoll(normal: Vector3d, tangent: Vector3dc, rollRadians: Double): Vector3d {
            if (abs(rollRadians) < 1e-12) return normal
            val axis = Vector3d(tangent).normalize()
            val cosR = cos(rollRadians)
            val sinR = sin(rollRadians)
            val cross = Vector3d(axis).cross(normal)
            val axisPart = Vector3d(axis).mul(axis.dot(normal) * (1.0 - cosR))
            return normal.mul(cosR).add(cross.mul(sinR)).add(axisPart).normalize()
        }

        fun addSegment(len: Double, tan2d: Vector3dc, start: Vector3dc) {
            if (len < 0.01) return
            val tan2dn = Vector3d(tan2d).normalize()
            val m = maxOf(2, (len / step).toInt())
            for (i in 0..m) {
                val t = i.toDouble() / m
                val arc = s + len * t
                val y = heightAt(arc)
                val slope = gradeAt(arc)
                val p = Vector3d(start).fma(len * t, tan2dn)
                p.y = y
                val tan3d = Vector3d(tan2dn.x(), slope, tan2dn.z()).normalize()
                val (_, baseNormal, _) = ConstraintSolver.computeFrame(tan3d)
                val nrm3d = applyRoll(baseNormal, tan3d, rollAt(arc))
                pts.add(Curve.RawPoint(Vector3d(p), tan3d, nrm3d, arc))
            }
            s += len
        }

        // straightA
        addSegment(r.straightA, fx, fromPos)
        val curveOrigin = Vector3d(fromPos).fma(r.straightA, fx)
        curveOrigin.y = heightAt(r.straightA)

        // clothoid + arc + clothoid
        val curve = StandardRailCurve(r.R, r.L1, r.Lc, superelevationConfig)
        val curveStep = minOf(step, adaptiveCurveStep(r.R))
        val localPoints = curve.generatePoints(curveStep).toMutableList()
        if (localPoints.isNotEmpty()) {
            localPoints[0] = accurateLocalRawPoint(curve, 0.0)
            localPoints[localPoints.lastIndex] = accurateLocalRawPoint(curve, curve.totalLength)
        }
        localPoints.forEach { lp ->
            val lx = lp.position.x(); val lz = lp.position.z()
            val gx = curveOrigin.x() + lx * fx.x() + lz * fz.x()
            val gz = curveOrigin.z() + lx * fx.z() + lz * fz.z()
            val arc = s + lp.arcLength
            val gy = heightAt(arc)
            val ltx = lp.tangent.x(); val ltz = lp.tangent.z()
            val gtx = ltx * fx.x() + ltz * fz.x()
            val gtz = ltx * fx.z() + ltz * fz.z()
            val slope = gradeAt(arc)
            val tan3d = Vector3d(gtx, slope, gtz).normalize()
            val (_, baseNormal, _) = computeFrame(tan3d)
            val cantSign = if (r.leftTurn) -1.0 else 1.0
            val cantRoll = cantSign * curve.superelevationAngleAt(lp.arcLength)
            val nrm3d = applyRoll(baseNormal, tan3d, rollAt(arc) + cantRoll)
            pts.add(Curve.RawPoint(Vector3d(gx, gy, gz), tan3d, nrm3d, arc))
        }
        s += curve.totalLength

        // straightB
        val dp = r.totalAngle
        val endTan2d = Vector3d(fx.x()*cos(dp)+fz.x()*sin(dp), 0.0, fx.z()*cos(dp)+fz.z()*sin(dp)).normalize()
        addSegment(r.straightB, endTan2d, pts.last().position)

        return pts
    }

    private fun adaptiveCurveStep(radius: Double): Double = when {
        radius < 30.0 -> 0.02
        radius < 100.0 -> 0.05
        radius < 300.0 -> 0.10
        else -> 0.20
    }

    private fun accurateLocalRawPoint(curve: StandardRailCurve, arc: Double): StandardRailCurve.CurveRawPoint {
        val p = curve.pointAtAccurate(arc)
        return StandardRailCurve.CurveRawPoint(
            position = p.position,
            tangent = Vector3d(cos(p.tangentAngle), 0.0, sin(p.tangentAngle)),
            normal = Vector3d(0.0, 1.0, 0.0),
            arcLength = arc
        )
    }

    // ── internal ──

    private fun straightOk(L1: Double, R: Double, dp: Double,
                           tx: Double, tz: Double, sd: Double, cd: Double): Boolean {
        val th = dp - L1 / R
        if (th < -1e-12) return false
        val crv = StandardRailCurve(R, L1, R * th)
        val e = crv.positionAtAccurate(crv.totalLength)
        val sb = if (abs(sd) < 1e-12) 0.0 else (tz - e.z) / sd
        val sa = tx - e.x - sb * cd
        return sa >= -0.001 && sb >= -0.001
    }

    private fun build(L1: Double, R: Double, dp: Double,
                      tx: Double, tz: Double, sd: Double, cd: Double,
                      leftTurn: Boolean): ConstraintSolveResult.Success {
        val th = dp - L1 / R
        val Lc = R * th
        val crv = StandardRailCurve(R, L1, Lc)
        val e = crv.positionAtAccurate(crv.totalLength)
        val sb = maxOf(if (abs(sd) < 1e-12) 0.0 else (tz - e.z) / sd, 0.0)
        val sa = maxOf(tx - e.x - sb * cd, 0.0)
        return ConstraintSolveResult.Success(R, sa, L1, Lc, L1, sb, th, sa + crv.totalLength + sb, dp, leftTurn)
    }

    private fun toLocalFrame(pt: Vector3dc, o: Vector3dc, t: Vector3dc): Pair<Double, Double> {
        val d = Vector3d(pt).sub(o, Vector3d())
        val fx = Vector3d(t).normalize()
        val fz = Vector3d(fx).cross(Vector3d(0.0, 1.0, 0.0)).normalize()
        return Pair(d.dot(fx), d.dot(fz))
    }

    /**
     * Gram-Schmidt 正交化：从 3D 切线构建 orthonormal 标架 (tangent, normal, binormal)。
     * normal = up 在切线垂直面上的投影；切线接近垂直时回退到 Z 轴。
     */
    fun computeFrame(tangent3d: Vector3dc): Triple<Vector3d, Vector3d, Vector3d> {
        val t = Vector3d(tangent3d).normalize()
        val ref = if (kotlin.math.abs(t.y()) > 0.99) Vector3d(0.0, 0.0, 1.0) else Vector3d(0.0, 1.0, 0.0)
        val n = Vector3d(ref).sub(Vector3d(t).mul(ref.dot(t))).normalize()
        val b = Vector3d()
        n.cross(t, b).normalize()
        return Triple(t, n, b)
    }

    fun computeAngle(a: Vector3dc, b: Vector3dc): Double {
        val la = sqrt(a.x()*a.x() + a.z()*a.z())
        val lb = sqrt(b.x()*b.x() + b.z()*b.z())
        if (la < 1e-15 || lb < 1e-15) return 0.0
        return acos(((a.x()*b.x() + a.z()*b.z()) / (la * lb)).coerceIn(-1.0, 1.0))
    }
}
