package work.art1st.javarailways.bukkit.tool

import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

import work.art1st.javarailways.core.physics.createRailForTest

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.configureTurnoutForTest
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import java.util.Locale
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

        val localized = error?.let { RailWandI18n.resolve(Locale.SIMPLIFIED_CHINESE, it) }
        assertTrue(localized?.contains("切线") == true, localized)
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

        val localizedMoved = movedError?.let { RailWandI18n.resolve(Locale.SIMPLIFIED_CHINESE, it) }
        val localizedRolled = rolledError?.let { RailWandI18n.resolve(Locale.SIMPLIFIED_CHINESE, it) }
        assertTrue(localizedMoved?.contains("位置") == true, localizedMoved)
        assertTrue(localizedRolled?.contains("横滚") == true, localizedRolled)
    }

    @Test
    fun `third leg occupancy blocks turnout geometry replacement`() {
        val (world, stem, _, branch) = turnout()
        createTrainWithConfig("occupied-turnout-branch", branch, world, listOf(5.0))

        val error = turnoutGeometryMaintenanceError(world, listOf(stem))

        val localized = error?.let { RailWandI18n.resolve(Locale.US, it) }
        assertTrue(localized?.contains("occupied-turnout-branch") == true, localized)
    }

    @Test
    fun `mapped turnout snapshot restores selected branch after one leg replacement`() {
        val (world, stem, straight, branch) = turnout()
        IsolatedSimulationThreadMarker.run { stem.viewAB.activeRail = branch.viewAB }
        val snapshots = captureTurnoutViewSnapshots(listOf(stem))

        IsolatedSimulationThreadMarker.run { world.removeRail(stem) }
        val replacement = rail(world, Vector3d(-10.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0))
        configureTurnoutForTest(replacement, listOf(straight, branch))
        assertSame(straight.viewAB, replacement.viewAB.activeRail)

        IsolatedSimulationThreadMarker.run {
            restoreMappedTurnoutViewSnapshots(world, snapshots, mapOf(stem.id to replacement.id))
        }

        assertEquals(listOf(straight.viewAB, branch.viewAB), replacement.viewAB.nextRails)
        assertSame(branch.viewAB, replacement.viewAB.activeRail)
        assertSame(replacement.viewBA, straight.viewBA.activeRail)
        assertSame(replacement.viewBA, branch.viewBA.activeRail)
    }

    @Test
    fun `mapped turnout snapshot rejects a topology that lost a branch`() {
        val (world, stem, straight, branch) = turnout()
        val snapshots = captureTurnoutViewSnapshots(listOf(stem))

        IsolatedSimulationThreadMarker.run {
            world.removeRail(stem)
            // Removing only the stem retains the two authored branch members. Rejoining either
            // one now correctly completes the turnout again, so actually remove the lost branch.
            world.removeRail(branch)
        }
        val replacement = rail(world, Vector3d(-10.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0))
        IsolatedSimulationThreadMarker.run { CoreRail.connect(replacement, straight) }

        assertThrows(IllegalArgumentException::class.java) {
            IsolatedSimulationThreadMarker.run {
            restoreMappedTurnoutViewSnapshots(world, snapshots, mapOf(stem.id to replacement.id))
        }
        }
    }

    @Test
    fun `mapped incomplete junction snapshot rejects lost membership despite unchanged empty routes`() {
        val world = createTestWorld()
        try {
            val straight = rail(world, Vector3d(), Vector3d(10.0, 0.0, 0.0))
            val branch = rail(world, Vector3d(), Vector3d(10.0, 0.0, 3.0))
            IsolatedSimulationThreadMarker.run { CoreRail.connect(straight, branch) }
            val snapshots = captureTurnoutViewSnapshots(listOf(straight))
            IsolatedSimulationThreadMarker.run { world.removeRail(straight) }
            val unjoinedReplacement = rail(world, Vector3d(), Vector3d(10.0, 0.0, 0.0))

            assertThrows(IllegalArgumentException::class.java) {
                IsolatedSimulationThreadMarker.run {
                    restoreMappedTurnoutViewSnapshots(world, snapshots, mapOf(straight.id to unjoinedReplacement.id))
                }
            }
            assertTrue(unjoinedReplacement.viewBA.joinedEndpoints.isEmpty())
            assertTrue(branch.viewBA.joinedEndpoints.isEmpty())
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `incomplete junction keeps its boundary frame and peers through mapped restore`() {
        val world = createTestWorld()
        try {
            val straight = rail(world, Vector3d(), Vector3d(10.0, 0.0, 0.0))
            val branch = rail(world, Vector3d(), Vector3d(10.0, 0.0, 3.0))
            IsolatedSimulationThreadMarker.run { CoreRail.connect(straight, branch) }
            val snapshots = captureTurnoutViewSnapshots(listOf(straight))
            assertEquals(2, snapshots.size)
            assertTrue(snapshots.all { it.next.isEmpty() && it.active == null && it.joinedEndpoints.size == 1 })
            val moved = curve(Vector3d(0.01, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0))
            val error = turnoutBoundaryFrameError(listOf(listOf(straight)), listOf(listOf(moved)))
            assertTrue(error?.let { RailWandI18n.resolve(Locale.US, it).contains("position") } == true)

            IsolatedSimulationThreadMarker.run { world.removeRail(straight) }
            val replacement = rail(world, Vector3d(), Vector3d(10.0, 0.0, 0.0))
            IsolatedSimulationThreadMarker.run {
                CoreRail.connect(replacement, branch)
                restoreMappedTurnoutViewSnapshots(world, snapshots, mapOf(straight.id to replacement.id))
            }
            assertTrue(replacement.viewBA.nextRails.isEmpty())
            assertEquals(setOf(replacement, branch),
                work.art1st.javarailways.basic.rail.TurnoutTopology.turnoutRailsTouching(listOf(replacement)))
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    private fun turnout(): TurnoutFixture {
        val world = createTestWorld()
        val stem = rail(world, Vector3d(-30.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0))
        val straight = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(30.0, 0.0, 0.0))
        val radians = Math.toRadians(20.0)
        val branch = rail(
            world,
            Vector3d(0.0, 0.0, 0.0),
            Vector3d(cos(radians) * 30.0, 0.0, sin(radians) * 30.0)
        )
        configureTurnoutForTest(stem, listOf(straight, branch))
        return TurnoutFixture(world, stem, straight, branch)
    }

    private fun rail(world: CoreWorld, from: Vector3d, to: Vector3d): CoreRail =
        world.createRailForTest(curve(from, to))

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
