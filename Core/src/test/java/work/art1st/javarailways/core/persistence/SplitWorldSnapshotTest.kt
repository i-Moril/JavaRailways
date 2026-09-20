package work.art1st.javarailways.core.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.di.NoOpWorldParentContext
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.util.TickManagerImpl
import java.util.concurrent.Executors

class SplitWorldSnapshotTest {
    @Test
    fun `split infrastructure and runtime snapshots restore one consistent world`() {
        val source = createTestWorld()
        val rail = createStraightRailFromSegments(
            source,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 2)
        )
        createTrainWithConfig("T1", rail, source, listOf(10.0))
        val sourceRailIds = source.allRails.mapTo(linkedSetOf()) { it.id }

        val infrastructure = source.saveInfrastructure(42L)
        val runtime = source.saveRuntime(42L, physicsEpoch = 9L)
        val dependency = CoreWorld.SnapshotDependency(
            TickManagerImpl(Executors.newSingleThreadScheduledExecutor(), 0L),
            NoOpWorldParentContext,
            PhysicsCoordinator(1)
        )
        val restored = CoreWorld.load(infrastructure, runtime, dependency)

        assertEquals(42L, infrastructure.infrastructureRevision)
        assertEquals(42L, runtime.infrastructureRevision)
        assertEquals(9L, runtime.physicsEpoch)
        assertEquals(sourceRailIds, restored.allRails.map { it.id }.toSet())
        assertEquals(setOf("T1"), restored.allTrains.map { it.id }.toSet())
        assertEquals(1L, restored.topologyRevision)
        assertEquals(restored.topologyRevision, restored.currentTopologyState().revision)
        assertEquals(sourceRailIds, restored.currentTopologyState().railProjection.keys)
        assertEquals(1, restored.currentTopologyState().edges.size)
        restored.physicsCoordinator.close()
        (restored.tickManager as AutoCloseable).close()
        source.physicsCoordinator.close()
        (source.tickManager as AutoCloseable).close()
    }
}
