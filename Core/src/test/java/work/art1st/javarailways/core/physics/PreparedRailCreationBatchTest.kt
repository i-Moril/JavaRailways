package work.art1st.javarailways.core.physics

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.rail.PreparedRailCreationRejectedException
import work.art1st.javarailways.core.physics.rail.PreparedRailCreationRejectionReason
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinatorStatus
import work.art1st.javarailways.core.physics.train.createTestWorld
import java.util.UUID

class PreparedRailCreationBatchTest {
    @Test
    fun `prepared batch exposes immutable conservative existing rail candidates`() {
        val world = createTestWorld()
        val spatialCandidate = world.createRailForTest(line(0.1, -5.0, 0.1, 5.0))
        val replacementSource = world.createRailForTest(line(500.0, 0.0, 510.0, 0.0))
        val unrelated = world.createRailForTest(line(1_000.0, 0.0, 1_010.0, 0.0))

        val prepared = world.prepareRailCreation(
            line(0.0, -5.0, 0.0, 5.0),
            setOf(replacementSource.id)
        )

        assertTrue(prepared.crossings.isEmpty())
        assertEquals(
            setOf(spatialCandidate.id, replacementSource.id),
            prepared.potentiallyAffectedExistingRailIds
        )
        assertTrue(unrelated.id !in prepared.potentiallyAffectedExistingRailIds)
        @Suppress("UNCHECKED_CAST")
        val mutableView = prepared.potentiallyAffectedExistingRailIds as MutableSet<UUID>
        assertThrows(UnsupportedOperationException::class.java) {
            mutableView += unrelated.id
        }
    }

    @Test
    fun `exact crossing detection happens during preparation and not installation`() {
        val world = createTestWorld()
        world.createRailForTest(line(-5.0, 0.0, 5.0, 0.0))
        val beforePrepare = world.atGradeExactDetectionCountForTesting()

        val prepared = world.prepareRailCreation(line(0.0, -5.0, 0.0, 5.0))
        val afterPrepare = world.atGradeExactDetectionCountForTesting()
        assertTrue(afterPrepare > beforePrepare)

        val installed = world.installPreparedRails(prepared).single()

        assertEquals(afterPrepare, world.atGradeExactDetectionCountForTesting())
        assertSame(installed, world.getRail(prepared.creations.single().railId))
        assertEquals(1, world.topologyState.atGradeCrossings.size)
    }

    @Test
    fun `batch detects new against new crossing and publishes once in input order`() {
        val world = createTestWorld()
        val curves = listOf(
            line(-5.0, 0.0, 5.0, 0.0),
            line(0.0, -5.0, 0.0, 5.0)
        )

        val prepared = world.prepareRailCreationBatch(curves)
        val installed = world.installPreparedRails(prepared)

        assertEquals(1L, world.topologyRevision)
        assertEquals(prepared.creations.map { it.railId }, installed.map { it.id })
        assertEquals(curves, installed.map { it.curve })
        assertEquals(1, world.topologyState.atGradeCrossings.size)
    }

    @Test
    fun `world mismatch rejects before mutation`() {
        val source = createTestWorld()
        val target = createTestWorld()
        val prepared = source.prepareRailCreation(line(0.0, 0.0, 10.0, 0.0))

        val failure = assertThrows(PreparedRailCreationRejectedException::class.java) {
            target.installPreparedRails(prepared)
        }

        assertEquals(PreparedRailCreationRejectionReason.WORLD_MISMATCH, failure.reason)
        assertTrue(target.allRails.isEmpty())
        assertEquals(0L, target.topologyRevision)
    }

    @Test
    fun `stale topology rejects whole batch before mutation`() {
        val world = createTestWorld()
        val prepared = world.prepareRailCreationBatch(
            listOf(line(0.0, 0.0, 10.0, 0.0), line(20.0, 0.0, 30.0, 0.0))
        )
        val unrelated = world.createRailForTest(line(40.0, 0.0, 50.0, 0.0))
        val revisionBeforeReject = world.topologyRevision

        val failure = assertThrows(PreparedRailCreationRejectedException::class.java) {
            world.installPreparedRails(prepared)
        }

        assertEquals(PreparedRailCreationRejectionReason.STALE_TOPOLOGY, failure.reason)
        assertEquals(listOf(unrelated), world.allRails.toList())
        assertEquals(revisionBeforeReject, world.topologyRevision)
        assertTrue(prepared.creations.none { world.getRail(it.railId) != null })
    }

    @Test
    fun `dirty outer transaction rejects prepared install without installing its rails`() {
        val world = createTestWorld()
        val prepared = world.prepareRailCreation(line(20.0, 0.0, 30.0, 0.0))
        lateinit var failure: PreparedRailCreationRejectedException

        world.railTopologyTransaction {
            world.createRailInTransaction(UUID.randomUUID(), line(0.0, 0.0, 10.0, 0.0))
            failure = assertThrows(PreparedRailCreationRejectedException::class.java) {
                world.installPreparedRails(prepared)
            }
        }

        assertEquals(PreparedRailCreationRejectionReason.DIRTY_OUTER_TRANSACTION, failure.reason)
        assertEquals(1, world.allRails.size)
        assertTrue(prepared.creations.none { world.getRail(it.railId) != null })
    }

    @Test
    fun `complete replacement rollback keeps world healthy`() {
        val world = createTestWorld()
        val source = world.createRailForTest(line(0.0, 0.0, 10.0, 0.0))
        val prepared = world.prepareRailCreation(line(0.0, 1.0, 10.0, 1.0), setOf(source.id))
        val revisionBefore = world.topologyRevision

        world.railTopologyTransaction {
            val replacement = world.installPreparedRails(prepared).single()
            world.removeRail(replacement)
        }

        assertSame(source, world.getRail(source.id))
        assertTrue(prepared.creations.none { world.getRail(it.railId) != null })
        assertEquals(revisionBefore, world.topologyRevision)
        assertEquals(PhysicsCoordinatorStatus.RUNNING, world.physicsCoordinator.status)
    }

    @Test
    fun `partial replacement rollback faults instead of publishing incomplete topology`() {
        val world = createTestWorld()
        val firstSource = world.createRailForTest(line(0.0, 0.0, 10.0, 0.0))
        val secondSource = world.createRailForTest(line(20.0, 0.0, 30.0, 0.0))
        val prepared = world.prepareRailCreationBatch(
            listOf(line(0.0, 1.0, 10.0, 1.0), line(20.0, 1.0, 30.0, 1.0)),
            setOf(firstSource.id, secondSource.id)
        )
        val revisionBefore = world.topologyRevision

        assertThrows(IllegalStateException::class.java) {
            world.railTopologyTransaction {
                val replacements = world.installPreparedRails(prepared)
                world.removeRail(firstSource)
                replacements.forEach(world::removeRail)
            }
        }

        assertEquals(revisionBefore, world.topologyRevision)
        assertEquals(PhysicsCoordinatorStatus.STALLED, world.physicsCoordinator.status)
        assertTrue(world.getRail(firstSource.id) == null)
        assertSame(secondSource, world.getRail(secondSource.id))
    }

    private fun line(fromX: Double, fromZ: Double, toX: Double, toZ: Double): PrimeCurve {
        val from = Vector3d(fromX, 0.0, fromZ)
        val to = Vector3d(toX, 0.0, toZ)
        val direction = Vector3d(to).sub(from)
        val length = direction.length()
        direction.normalize()
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(from), Vector3d(direction), Vector3d(0.0, 1.0, 0.0), 0.0),
            Curve.RawPoint(Vector3d(to), Vector3d(direction), Vector3d(0.0, 1.0, 0.0), length)
        ))
    }
}
