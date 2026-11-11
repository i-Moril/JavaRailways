package work.art1st.javarailways.basic.curve

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import kotlin.math.*

class ConstraintSolverTest {

    @Test
    fun `sub centimetre solved tangents remain part of the geometry`() {
        val radius = 20.0
        val leading = 0.000312
        val trailing = 0.0008
        val angle = PI / 2.0
        val solved = ConstraintSolveResult.Success(radius, leading, 0.0, radius * angle,
            0.0, trailing, angle, leading + radius * angle + trailing, angle)
        val from = Vector3d(0.0, 64.0, 0.0)
        val to = Vector3d(leading + radius, 64.0, radius + trailing)
        val points = ConstraintSolver.generateCurve(solved, from, Vector3d(1.0, 0.0, 0.0),
            to, Vector3d(0.0, 0.0, 1.0))
        assertEquals(from, points.first().position)
        assertEquals(0.0, points.last().position.distance(to), 1e-10)
        assertEquals(solved.totalLength, points.last().arcLength, 1e-12)
        assertTrue(points.zipWithNext().all { (a, b) -> b.arcLength > a.arcLength })
        assertTrue(points.any { abs(it.arcLength - leading) < 1e-12 })
        assertTrue(points.any { abs(it.arcLength - (solved.totalLength - trailing)) < 1e-12 })
    }

    @Test
    fun `millimetre negative tangent cannot be clamped into a solution`() {
        val result = ConstraintSolver.solve(Vector3d(), Vector3d(1.0, 0.0, 0.0),
            Vector3d(99.9995, 0.0, 100.0), Vector3d(0.0, 0.0, 1.0), 100.0)
        assertInstanceOf(ConstraintSolveResult.Failure::class.java, result)
    }

    @Test
    fun `sub representable tangent at pure circular limit still creates a valid Core curve`() {
        val from = Vector3d()
        val to = Vector3d(20.0 + 5e-13, 0.0, 20.0)
        val fromTangent = Vector3d(1.0, 0.0, 0.0)
        val toTangent = Vector3d(0.0, 0.0, 1.0)
        val solved = assertInstanceOf(ConstraintSolveResult.Success::class.java,
            ConstraintSolver.solve(from, fromTangent, to, toTangent, 20.0))
        val points = ConstraintSolver.generateCurve(solved, from, fromTangent, to, toTangent)
        val curve = assertDoesNotThrow<PrimeCurve> { PrimeCurve.fromPoints(points) }
        assertTrue(curve.getPointAt(curve.totalArcLength).position.distance(to) < 1e-11)
        assertEquals(0.0, solved.straightA)
        assertTrue(points.zipWithNext().all { (a, b) -> b.arcLength - a.arcLength >= 1e-12 })
    }

    @Test
    fun `ordinary short tangent retains its intermediate grade and roll sample`() {
        val radius = 20.0
        val leading = 0.3
        val angle = PI / 2.0
        val solved = ConstraintSolveResult.Success(radius, leading, 0.0, radius * angle,
            0.0, 0.0, angle, leading + radius * angle, angle)
        val points = ConstraintSolver.generateCurve(solved, Vector3d(), Vector3d(1.0, 0.1, 0.0),
            Vector3d(20.3, 1.0, 20.0), Vector3d(0.0, 0.0, 1.0),
            step = 0.2, fromRollDegrees = 2.0, toRollDegrees = 8.0)
        val midpoint = points.first { abs(it.position.x() - 0.15) < 1e-12 }
        assertTrue(midpoint.arcLength > 0.15)
        assertTrue(midpoint.tangent.y() > 0.0)
        assertTrue(AnchorGeometry.rollDegreesFromNormal(midpoint.tangent, midpoint.normal) > 2.0)
    }

    @Nested inner class ComputeAngle {
        @Test fun `same`() = assertEquals(0.0, ConstraintSolver.computeAngle(Vector3d(1.0,0.0,0.0), Vector3d(1.0,0.0,0.0)), 1e-9)
        @Test fun `opposite`() = assertEquals(PI, ConstraintSolver.computeAngle(Vector3d(1.0,0.0,0.0), Vector3d(-1.0,0.0,0.0)), 1e-6)
        @Test fun `90deg`() = assertEquals(PI/2, ConstraintSolver.computeAngle(Vector3d(1.0,0.0,0.0), Vector3d(0.0,0.0,1.0)), 1e-6)
    }

    @Nested inner class SolveWithRadius {
        @Test fun `90deg symmetric solves with zero error`() {
            val from = Vector3d(0.0, 64.0, 0.0); val fromTan = Vector3d(1.0, 0.0, 0.0)
            val to = Vector3d(100.0, 64.0, 100.0); val toTan = Vector3d(0.0, 0.0, 1.0)
            // R=100 matches the symmetric target exactly
            val result = ConstraintSolver.solve(from, fromTan, to, toTan, 100.0)
            assertTrue(result is ConstraintSolveResult.Success, "should solve with R=100: $result")
            val r = result as ConstraintSolveResult.Success
            val pts = ConstraintSolver.generateCurve(r, from, fromTan, to, toTan)
            assertEquals(0.0, Vector3d(pts.first().position).distance(from), 0.01)
            val endErr = Vector3d(pts.last().position).distance(to)
            assertTrue(endErr < 0.005, "终点误差=${"%.3f".format(endErr)}m")
        }

        @Test fun `symmetric 180deg U-turn`() {
            val from = Vector3d(0.0, 64.0, 0.0); val fromTan = Vector3d(1.0, 0.0, 0.0)
            val to = Vector3d(0.0, 64.0, 200.0); val toTan = Vector3d(-1.0, 0.0, 0.0)
            // R=100 gives z=200 exactly for 180° turn
            val result = ConstraintSolver.solve(from, fromTan, to, toTan, 100.0)
            assertTrue(result is ConstraintSolveResult.Success, "should solve with R=100: $result")
        }

        // R=1 with long straight segments is technically feasible — straights compensate.
        // The model always produces error=0 when a solution exists.

        @Test fun `straightA and B nonnegative`() {
            val from = Vector3d(0.0, 64.0, 0.0); val fromTan = Vector3d(1.0, 0.0, 0.0)
            val to = Vector3d(100.0, 64.0, 100.0); val toTan = Vector3d(0.0, 0.0, 1.0)
            val result = ConstraintSolver.solve(from, fromTan, to, toTan, 100.0)
            assertTrue(result is ConstraintSolveResult.Success, "$result")
            val r = result as ConstraintSolveResult.Success
            assertTrue(r.straightA >= 0, "straightA=${r.straightA}")
            assertTrue(r.straightB >= 0, "straightB=${r.straightB}")
        }

        @Test fun `rounding at zero circular arc does not produce negative Lc`() {
            val radius = 250.0
            val angle = 1.570796326794896
            val transitionLength = radius * angle
            val source = StandardRailCurve(radius, transitionLength, 0.0)
            val endpoint = source.positionAtAccurate(source.totalLength)
            val from = Vector3d(0.0, 64.0, 0.0)
            val to = Vector3d(endpoint.x(), 64.0, endpoint.z())
            val fromTan = Vector3d(1.0, 0.0, 0.0)
            val toTan = Vector3d(cos(angle), 0.0, sin(angle))

            val result = assertDoesNotThrow<ConstraintSolveResult> {
                ConstraintSolver.solve(from, fromTan, to, toTan, radius)
            }

            assertTrue(result is ConstraintSolveResult.Success, "$result")
            assertTrue((result as ConstraintSolveResult.Success).Lc >= 0.0, "Lc=${result.Lc}")
            val generated = ConstraintSolver.generateCurve(result, from, fromTan, to, toTan)
            val curve = assertDoesNotThrow<PrimeCurve> { PrimeCurve.fromPoints(generated) }
            assertTrue(curve.getPointAt(curve.totalArcLength).position.distance(to) <= 0.005)
        }
    }

    @Nested inner class SolveBest {
        @Test fun `90deg symmetric`() {
            val from = Vector3d(0.0, 64.0, 0.0); val fromTan = Vector3d(1.0, 0.0, 0.0)
            val to = Vector3d(100.0, 64.0, 100.0); val toTan = Vector3d(0.0, 0.0, 1.0)
            val result = ConstraintSolver.solveBest(from, fromTan, to, toTan)
            assertTrue(result is ConstraintSolveResult.Success, "$result")
        }

        @Test fun `picks large radius`() {
            val from = Vector3d(0.0, 64.0, 0.0); val fromTan = Vector3d(1.0, 0.0, 0.0)
            val to = Vector3d(100.0, 64.0, 100.0); val toTan = Vector3d(0.0, 0.0, 1.0)
            val result = ConstraintSolver.solveBest(from, fromTan, to, toTan)
            assertTrue(result is ConstraintSolveResult.Success, "$result")
            val r = result as ConstraintSolveResult.Success
            assertTrue(r.R >= 50, "应选择 ≥50m 的半径, actual=${r.R}")
        }
    }

    @Nested inner class GenerateCurve {
        @Test fun `constant grade five segment uses physical arc and retains analytic flat stations`() {
            val horizontal = 20.0 + 10.0 * PI
            val solved = ConstraintSolveResult.Success(20.0, 10.0, 0.0, 10.0 * PI, 0.0, 10.0,
                PI / 2, horizontal, PI / 2)
            for (grade in listOf(0.0, 0.5, -0.5)) {
                val points = ConstraintSolver.generateCurve(solved, Vector3d(), Vector3d(1.0, grade, 0.0),
                    Vector3d(30.0, horizontal * grade, 30.0), Vector3d(0.0, grade, 1.0), step = 0.2)
                val expected = horizontal * sqrt(1.0 + grade * grade)
                assertEquals(expected, points.last().arcLength, 1e-9)
                assertEquals(10.0 * sqrt(1.0 + grade * grade),
                    points.single { it.position.x() == 10.0 && it.position.z() == 0.0 }.arcLength, 1e-9)
                assertTrue(points.zipWithNext().all { (a, b) -> b.arcLength > a.arcLength })
            }
        }

        @Test fun `straight curve junctions occur once with strictly increasing arcs`() {
            val circularLength = 10.0 * PI
            val result = ConstraintSolveResult.Success(
                R = 20.0, straightA = 10.0,
                L1 = 0.0, Lc = circularLength, L2 = 0.0,
                straightB = 10.0, theta = PI / 2,
                totalLength = 20.0 + circularLength, totalAngle = PI / 2
            )
            val from = Vector3d()
            val to = Vector3d(30.0, 0.0, 30.0)

            val points = ConstraintSolver.generateCurve(
                result, from, Vector3d(1.0, 0.0, 0.0),
                to, Vector3d(0.0, 0.0, 1.0), step = 1.0
            )

            assertEquals(0.0, points.first().arcLength)
            assertEquals(result.totalLength, points.last().arcLength, 1e-9)
            assertEquals(0.0, points.first().position.distance(from), 1e-9)
            assertEquals(0.0, points.last().position.distance(to), 1e-9)
            assertEquals(1, points.count { it.arcLength == 10.0 })
            assertEquals(1, points.count { it.arcLength == 10.0 + circularLength })
            for ((lo, hi) in points.zipWithNext()) {
                assertTrue(hi.arcLength > lo.arcLength, "${lo.arcLength} -> ${hi.arcLength}")
            }
            assertEquals(result.totalLength, PrimeCurve.fromPoints(points).totalArcLength, 1e-9)
        }

        @Test fun `start matches from`() {
            val from = Vector3d(10.0, 70.0, 20.0); val fromTan = Vector3d(1.0, 0.0, 0.0)
            val to = Vector3d(150.0, 70.0, 100.0); val toTan = Vector3d(0.0, 0.0, 1.0)
            val result = ConstraintSolver.solveBest(from, fromTan, to, toTan)
            assertTrue(result is ConstraintSolveResult.Success, "$result")
            val pts = ConstraintSolver.generateCurve(result as ConstraintSolveResult.Success, from, fromTan, to, toTan)
            assertEquals(0.0, Vector3d(pts.first().position).distance(from), 0.01)
        }

        @Test fun `arcLength monotonic`() {
            val from = Vector3d(0.0, 64.0, 0.0); val fromTan = Vector3d(1.0, 0.0, 0.0)
            val to = Vector3d(100.0, 64.0, 100.0); val toTan = Vector3d(0.0, 0.0, 1.0)
            val result = ConstraintSolver.solveBest(from, fromTan, to, toTan)
            assertTrue(result is ConstraintSolveResult.Success, "$result")
            val pts = ConstraintSolver.generateCurve(result as ConstraintSolveResult.Success, from, fromTan, to, to, 0.5)
            for (i in 1 until pts.size)
                assertTrue(pts[i].arcLength > pts[i-1].arcLength)
        }
    }
}
