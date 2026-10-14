package work.art1st.javarailways.core.physics

import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.exception.InvalidActiveRailException
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailView
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.onOwnerForTest
import java.util.UUID

class CoreWorldActiveContinuationsPublicationTest {
    @Test
    fun `world frame reuses unchanged continuations and publishes a later branch selection`() = withWorld { world ->
        val (stem, first, second) = turnout(world)
        val stemEntry = RailEndpointRef(stem.id, RailEndpoint.B)
        val firstExit = RailEndpointRef(first.id, RailEndpoint.A)
        val secondExit = RailEndpointRef(second.id, RailEndpoint.A)

        world.onOwnerForTest {
            stem.viewAB.activeRail = first.viewAB
            world.publishOwnerSelectionChanges()
            val before = world.latestWorldFrame()
            world.publishOwnerSelectionChanges()
            assertSame(before, world.latestWorldFrame(), "unchanged owner polls must not rebuild the world")
            stem.viewAB.activeRail = second.viewAB
            world.publishOwnerSelectionChanges()
            assertEquals(before.physicsEpoch, world.latestWorldFrame().physicsEpoch)
            assertEquals(secondExit, world.latestWorldFrame().activeContinuations.selectedContinuation(stemEntry))
            assertEquals(firstExit, before.activeContinuations.selectedContinuation(stemEntry))
            assertSame(world.latestWorldFrame().activeContinuations,
                world.latestWorldFrame().occupancy.collisionActiveContinuations)
        }

        stem.viewAB.activeRail = first.viewAB
        val firstFrame = publish(world, 1L)
        val unchangedFrame = publish(world, 2L)

        assertEquals(firstExit, firstFrame.activeContinuations.selectedContinuation(stemEntry))
        assertSame(firstFrame.activeContinuations, unchangedFrame.activeContinuations)

        val failure = assertThrows(InvalidActiveRailException::class.java) {
            stem.viewAB.selectNextRail(stem.viewBA)
        }
        val rejectedMessage = failure.message
        assertEquals(
            "Invalid active rail: source=${stem.id}/AB, selected=${stem.id}/BA",
            rejectedMessage
        )
        assertSame(first.viewAB, stem.viewAB.nextActiveRail)
        val rejectedFrame = publish(world, 3L)
        assertSame(firstFrame.activeContinuations, rejectedFrame.activeContinuations)

        stem.viewAB.activeRail = second.viewAB
        val changedFrame = publish(world, 4L)
        assertEquals(rejectedMessage, failure.message, "later selection must not rewrite captured diagnostics")

        assertEquals(secondExit, changedFrame.activeContinuations.selectedContinuation(stemEntry))
        assertTrue(
            changedFrame.activeContinuations.selectionRevision >
                firstFrame.activeContinuations.selectionRevision
        )
        assertNotSame(firstFrame.activeContinuations, changedFrame.activeContinuations)
        assertEquals(firstExit, firstFrame.activeContinuations.selectedContinuation(stemEntry))
        assertEquals(firstFrame.topologyRevision, changedFrame.topologyRevision)

        stem.viewAB.activeRail = null
        val unselectedFrame = publish(world, 5L)
        assertNull(unselectedFrame.activeContinuations.selectedContinuation(stemEntry))
    }

    @Test
    fun `branch change during epoch processing rejects the captured frame`() = withWorld { world ->
        val (stem, first, second) = turnout(world)
        stem.viewAB.activeRail = first.viewAB
        world.rebuildSweptOccupancyIndex(1L)
        val candidate = world.captureWorldFrame(1L)

        stem.viewAB.activeRail = second.viewAB

        val failure = assertThrows(IllegalStateException::class.java) {
            world.publishCapturedWorldFrame(candidate)
        }
        assertTrue(failure.message.orEmpty().contains("active rail continuation changed"))
        assertNotSame(candidate, world.latestWorldFrame())
    }

    @Test
    fun `collision envelope continuation uses the published turnout selection`() = withWorld { world ->
        val (stem, first, second) = turnout(world)
        stem.viewAB.activeRail = first.viewAB
        val firstFrame = publish(world, 1L)

        assertSame(firstFrame.activeContinuations, firstFrame.occupancy.collisionActiveContinuations)
        assertSame(first.viewAB, world.collisionContinuation(stem.viewAB, firstFrame.activeContinuations))

        stem.viewAB.activeRail = second.viewAB
        val secondFrame = publish(world, 2L)

        assertSame(second.viewAB, world.collisionContinuation(stem.viewAB, secondFrame.activeContinuations))
        assertSame(first.viewAB, world.collisionContinuation(stem.viewAB, firstFrame.activeContinuations),
            "older collision evidence must retain the branch selected in its own publication")

        stem.viewAB.activeRail = null
        val unselectedFrame = publish(world, 3L)
        assertNull(world.collisionContinuation(stem.viewAB, unselectedFrame.activeContinuations))
    }

    private fun turnout(world: CoreWorld): Triple<CoreRail, CoreRail, CoreRail> {
        lateinit var stem: CoreRail
        lateinit var first: CoreRail
        lateinit var second: CoreRail
        IsolatedSimulationThreadMarker.run { world.railTopologyTransaction {
            stem = world.createRailInTransaction(UUID.randomUUID(), straightCurve(0.0, 10.0))
            first = world.createRailInTransaction(UUID.randomUUID(), straightCurve(10.0, 20.0))
            second = world.createRailInTransaction(UUID.randomUUID(), straightCurve(10.0, 30.0))
            RailView.connect(stem.viewAB, first.viewBA)
            RailView.connect(stem.viewAB, second.viewBA)
        } }
        return Triple(stem, first, second)
    }

    private fun publish(world: CoreWorld, epoch: Long) =
        world.rebuildSweptOccupancyIndex(epoch).let { world.publishWorldFrame(epoch) }

    private fun straightCurve(fromX: Double, toX: Double): PrimeCurve {
        val normal = Vector3d(0.0, 1.0, 0.0)
        val tangent = Vector3d(1.0, 0.0, 0.0)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(fromX, 0.0, 0.0), Vector3d(tangent), Vector3d(normal), 0.0),
            Curve.RawPoint(Vector3d(toX, 0.0, 0.0), Vector3d(tangent), Vector3d(normal), toX - fromX)
        ))
    }

    private fun withWorld(block: (CoreWorld) -> Unit) {
        val world = createTestWorld()
        try {
            block(world)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }
}
