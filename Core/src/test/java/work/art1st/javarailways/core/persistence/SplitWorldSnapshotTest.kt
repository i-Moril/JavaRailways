package work.art1st.javarailways.core.persistence

import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.di.NoOpWorldParentContext
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.util.TickManagerImpl
import java.util.concurrent.Executors

class SplitWorldSnapshotTest {
    @Test
    fun `split infrastructure and runtime snapshots restore one consistent world`() {
        withWorld { source ->
            val rail = createStraightRailFromSegments(
                source,
                PrimeCurveGenerator.buildStraight(100.0, segmentCount = 2)
            )
            createTrainWithConfig("T1", rail, source, listOf(10.0))
            val sourceRailIds = source.allRails.mapTo(linkedSetOf()) { it.id }
            val infrastructure = source.saveInfrastructure(42L)
            val runtime = source.saveRuntime(42L, physicsEpoch = 9L)

            withWorld(load = { tick, physics ->
                IsolatedSimulationThreadMarker.run { CoreWorld.load(
                    infrastructure,
                    runtime,
                    CoreWorld.SnapshotDependency(tick, NoOpWorldParentContext, physics)
                ) }
            }) { restored ->
                assertEquals(42L, infrastructure.infrastructureRevision)
                assertEquals(42L, runtime.infrastructureRevision)
                assertEquals(9L, runtime.physicsEpoch)
                assertEquals(sourceRailIds, restored.allRails.map { it.id }.toSet())
                assertEquals(setOf("T1"), restored.allTrains.map { it.id }.toSet())
                assertEquals(1L, restored.topologyRevision)
                assertEquals(restored.topologyRevision, restored.topologyState.revision)
                assertEquals(sourceRailIds, restored.topologyState.railProjection.keys)
                assertEquals(1, restored.topologyState.edges.size)
            }
        }
    }

    private fun withWorld(
        load: (TickManagerImpl, PhysicsCoordinator) -> CoreWorld = { tick, physics ->
            CoreWorld.createEmpty(tick, NoOpWorldParentContext, physics)
        },
        block: (CoreWorld) -> Unit
    ) {
        TickManagerImpl(Executors.newSingleThreadScheduledExecutor(), 0L).use { tick ->
            var world: CoreWorld? = null
            AutoCloseable { world?.releaseRuntimeBinding() }.use {
                PhysicsCoordinator(1, automaticFallback = false).use { physics ->
                    val created = load(tick, physics)
                    world = created
                    block(created)
                }
            }
        }
    }
}
