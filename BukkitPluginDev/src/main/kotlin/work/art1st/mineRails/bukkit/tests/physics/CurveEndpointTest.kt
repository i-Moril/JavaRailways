package work.art1st.mineRails.bukkit.tests.physics

import org.joml.Vector3d
import work.art1st.mineRails.bukkit.tests.Test
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import kotlin.math.PI

/**
 * Validates that makeStraight + makeArcRightTurn produce curves whose
 * endpoints match — the requirement for CoreRail.connect().
 */
class CurveEndpointTest {

    @Test(command = "curve_endpoints")
    fun testEndpointsMatch() {
        val normal = Vector3d(0.0, 1.0, 0.0)
        val step = 0.005  // 5mm to match Curve.TOLERANCE
        val straightLen = 200.0
        val radius = 80.0
        val origin = Vector3d(0.0, 64.0, 0.0)

        // Straight 1: east
        val s1 = makeStraight(origin, Vector3d(1.0, 0.0, 0.0), straightLen, normal, step)
        val p1 = s1.lastPoint().position

        // Arc 1: right turn from east to west
        val a1 = makeArcRightTurn(Vector3d(p1), radius, fromEast = true, normal, step)
        val startA1 = a1.getPointAt(0).position
        val endA1 = a1.lastPoint().position

        // Verify arc starts where straight ends
        assertClose(p1, startA1, "Arc1 start vs Straight1 end")
        // Straight 2: west
        val s2 = makeStraight(Vector3d(endA1), Vector3d(-1.0, 0.0, 0.0), straightLen, normal, step)
        val startS2 = s2.getPointAt(0).position
        val endS2 = s2.lastPoint().position
        assertClose(endA1, startS2, "S2 start vs Arc1 end")
        // Arc 2: right turn from west to east
        val a2 = makeArcRightTurn(Vector3d(endS2), radius, fromEast = false, normal, step)
        val startA2 = a2.getPointAt(0).position
        val endA2 = a2.lastPoint().position
        assertClose(endS2, startA2, "Arc2 start vs S2 end")
        // Verify the loop closes: arc2 end = origin
        assertClose(origin, endA2, "Loop close")
    }

    // ---- Duplicated from PhysTestCommand for isolated testing ----

    private fun makeStraight(start: Vector3d, dir: Vector3d, length: Double, normal: Vector3d, step: Double): PrimeCurve {
        val n = maxOf(2, (length / step).toInt())
        val pts = mutableListOf<Curve.RawPoint>()
        pts.add(Curve.RawPoint(Vector3d(start), Vector3d(dir), normal, 0.0))
        var arclen = 0.0
        for (i in 1..n) {
            val t = i.toDouble() / n
            val p = Vector3d(start).add(dir.mul(t * length, Vector3d()), Vector3d())
            arclen += p.distance(pts.last().position)
            pts.add(Curve.RawPoint(p, Vector3d(dir), normal, arclen))
        }
        return PrimeCurve.fromPoints(pts)
    }

    private fun makeArcRightTurn(startPoint: Vector3d, radius: Double, fromEast: Boolean, normal: Vector3d, step: Double): PrimeCurve {
        val (startAngle, endAngle) = if (fromEast) -PI / 2 to PI / 2 else PI / 2 to 3 * PI / 2
        val sinStart = kotlin.math.sin(startAngle)
        val center = Vector3d(startPoint).add(0.0, 0.0, -radius * sinStart, Vector3d())
        val arcLen = radius * PI
        val n = maxOf(200, (arcLen / step).toInt())
        val pts = mutableListOf<Curve.RawPoint>()
        pts.add(Curve.RawPoint(Vector3d(startPoint), Vector3d(-kotlin.math.sin(startAngle), 0.0, kotlin.math.cos(startAngle)), normal, 0.0))
        var arclen = 0.0
        for (i in 1..n) {
            val a = startAngle + (endAngle - startAngle) * i / n
            val p = Vector3d(center).add(radius * kotlin.math.cos(a), 0.0, radius * kotlin.math.sin(a), Vector3d())
            val t = Vector3d(-kotlin.math.sin(a), 0.0, kotlin.math.cos(a))
            arclen += p.distance(pts.last().position)
            pts.add(Curve.RawPoint(p, t, normal, arclen))
        }
        return PrimeCurve.fromPoints(pts)
    }

    private fun assertClose(a: org.joml.Vector3dc, b: org.joml.Vector3dc, label: String) {
        val eps = 1e-10
        require(kotlin.math.abs(a.x() - b.x()) < eps &&
                kotlin.math.abs(a.y() - b.y()) < eps &&
                kotlin.math.abs(a.z() - b.z()) < eps)
        { "$label: $a vs $b" }
    }

    private fun PrimeCurve.lastPoint(): Curve.Point = getPointAt(totalPoints - 1)
}
