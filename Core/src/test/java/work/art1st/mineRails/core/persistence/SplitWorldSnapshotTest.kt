package work.art1st.mineRails.core.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.mineRails.core.di.NoOpWorldParentContext
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.mineRails.core.physics.tick.PhysicsCoordinator
import work.art1st.mineRails.core.physics.train.createStraightRailFromSegments
import work.art1st.mineRails.core.physics.train.createTestWorld
import work.art1st.mineRails.core.physics.train.createTrainWithConfig
import work.art1st.mineRails.core.util.TickManagerImpl
import java.util.concurrent.Executors

class SplitWorldSnapshotTest {
    @Test
    fun `split infrastructure and runtime snapshots restore one consistent world`() {
        val source = createTestWorld()
        val rail = createStraightRailFromSegments(
            source,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        createTrainWithConfig("T1", rail, source, listOf(10.0))

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
        assertEquals(setOf(rail.id), restored.allRails.map { it.id }.toSet())
        assertEquals(setOf("T1"), restored.allTrains.map { it.id }.toSet())
        restored.physicsCoordinator.close()
        (restored.tickManager as AutoCloseable).close()
        source.physicsCoordinator.close()
        (source.tickManager as AutoCloseable).close()
    }
}
