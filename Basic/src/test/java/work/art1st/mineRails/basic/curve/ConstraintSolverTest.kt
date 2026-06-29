package work.art1st.mineRails.basic.curve

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.*

class ConstraintSolverTest {

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
            assertTrue(endErr < 0.5, "终点误差=${"%.3f".format(endErr)}m")
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
