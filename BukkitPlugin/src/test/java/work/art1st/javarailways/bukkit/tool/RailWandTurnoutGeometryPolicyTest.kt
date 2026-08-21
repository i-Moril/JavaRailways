package work.art1st.javarailways.bukkit.tool

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.rail.RailAutoConnector
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import kotlin.math.cos
import kotlin.math.sin

class RailWandTurnoutGeometryPolicyTest {
    @Test
    fun `unchanged turnout boundary frame allows internal geometry replacement`() {
        val (_, stem, _, _) = turnout()

        val replacement = PrimeCurve.fromPoints(
            listOf(
                Curve.RawPoint(Vector3d(-30.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0),
                Curve.RawPoint(Vector3d(-15.0, 0.0, 2.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 15.2),
                Curve.RawPoint(Vector3d(0.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 30.4)
            )
        )

        assertNull(
            turnoutBoundaryFrameError(
                oldLanes = listOf(listOf(stem)),
                replacementLanes = listOf(listOf(replacement))
            )
        )
    }

    @Test
    fun `changed turnout boundary tangent rejects geometry replacement`() {
        val (_, stem, _, _) = turnout()
        val changed = curve(
            Vector3d(-10.0, 0.0, 0.0),
            Vector3d(0.0, 0.0, 0.0),
            endTangent = Vector3d(cos(Math.toRadians(2.0)), 0.0, sin(Math.toRadians(2.0)))
        )

        val error = turnoutBoundaryFrameError(
            oldLanes = listOf(listOf(stem)),
            replacementLanes = listOf(listOf(changed))
        )

        assertTrue(error?.contains("切线") == true, error)
    }

    @Test
    fun `changed turnout boundary position or roll rejects geometry replacement`() {
        val (_, stem, _, _) = turnout()
        val moved = curve(Vector3d(-10.0, 0.0, 0.0), Vector3d(0.01, 0.0, 0.0))
        val rolled = curve(
            Vector3d(-10.0, 0.0, 0.0),
            Vector3d(0.0, 0.0, 0.0),
            endNormal = Vector3d(0.0, cos(Math.toRadians(2.0)), sin(Math.toRadians(2.0)))
        )

        val movedError = turnoutBoundaryFrameError(listOf(listOf(stem)), listOf(listOf(moved)))
        val rolledError = turnoutBoundaryFrameError(listOf(listOf(stem)), listOf(listOf(rolled)))

        assertTrue(movedError?.contains("位置") == true, movedError)
        assertTrue(rolledError?.contains("横滚") == true, rolledError)
    }

    @Test
    fun `third leg occupancy blocks turnout geometry replacement`() {
        val (world, stem, _, branch) = turnout()
        createTrainWithConfig("occupied-turnout-branch", branch, world, listOf(5.0))

        val error = turnoutGeometryMaintenanceError(world, listOf(stem))

        assertTrue(error?.contains("occupied-turnout-branch") == true, error)
    }

    @Test
    fun `mapped turnout snapshot restores selected branch after one leg replacement`() {
        val (world, stem, straight, branch) = turnout()
        stem.viewAB.activeRail = branch.viewAB
        val snapshots = captureTurnoutViewSnapshots(listOf(stem))

        world.removeRail(stem)
        val replacement = rail(world, Vector3d(-10.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0))
        RailAutoConnector.connectIfAllowed(replacement, straight)
        RailAutoConnector.connectIfAllowed(replacement, branch)
        assertSame(straight.viewAB, replacement.viewAB.activeRail)

        restoreMappedTurnoutViewSnapshots(world, snapshots, mapOf(stem.id to replacement.id))

        assertEquals(listOf(straight.viewAB, branch.viewAB), replacement.viewAB.nextRails)
        assertSame(branch.viewAB, replacement.viewAB.activeRail)
        assertSame(replacement.viewBA, straight.viewBA.activeRail)
        assertSame(replacement.viewBA, branch.viewBA.activeRail)
    }

    @Test
    fun `mapped turnout snapshot rejects a topology that lost a branch`() {
        val (world, stem, straight, _) = turnout()
        val snapshots = captureTurnoutViewSnapshots(listOf(stem))

        world.removeRail(stem)
        val replacement = rail(world, Vector3d(-10.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0))
        RailAutoConnector.connectIfAllowed(replacement, straight)

        assertThrows(IllegalArgumentException::class.java) {
            restoreMappedTurnoutViewSnapshots(world, snapshots, mapOf(stem.id to replacement.id))
        }
    }

    private fun turnout(): TurnoutFixture {
        val world = createTestWorld()
        val stem = rail(world, Vector3d(-30.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0))
        val straight = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(30.0, 0.0, 0.0))
        RailAutoConnector.connectIfAllowed(stem, straight)
        val radians = Math.toRadians(20.0)
        val branch = rail(
            world,
            Vector3d(0.0, 0.0, 0.0),
            Vector3d(cos(radians) * 30.0, 0.0, sin(radians) * 30.0)
        )
        RailAutoConnector.connectIfAllowed(stem, branch)
        return TurnoutFixture(world, stem, straight, branch)
    }

    private fun rail(world: CoreWorld, from: Vector3d, to: Vector3d): CoreRail =
        CoreRail(world, curve(from, to), mutableListOf(), mutableListOf())

    private fun curve(
        from: Vector3d,
        to: Vector3d,
        endTangent: Vector3d = Vector3d(to).sub(from).normalize(),
        endNormal: Vector3d = Vector3d(0.0, 1.0, 0.0)
    ): PrimeCurve {
        val startTangent = Vector3d(to).sub(from).normalize()
        return PrimeCurve.fromPoints(
            listOf(
                Curve.RawPoint(Vector3d(from), startTangent, Vector3d(0.0, 1.0, 0.0), 0.0),
                Curve.RawPoint(
                    Vector3d(to),
                    Vector3d(endTangent).normalize(),
                    Vector3d(endNormal).normalize(),
                    from.distance(to)
                )
            )
        )
    }

    private data class TurnoutFixture(
        val world: CoreWorld,
        val stem: CoreRail,
        val straight: CoreRail,
        val branch: CoreRail
    )
}
