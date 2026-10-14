package work.art1st.javarailways.core.persistence

import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.di.KoinWorldParentContext
import work.art1st.javarailways.core.di.NoOpWorldParentContext
import work.art1st.javarailways.core.di.ensureJavaRailwaysKoinStarted
import work.art1st.javarailways.core.di.javaRailwaysParentScope
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import work.art1st.javarailways.core.physics.train.SavedTrainV1
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.util.TickManagerImpl
import java.util.concurrent.Executors

class WorldPersistenceRoundTripTest {

    @Test
    fun worldSnapshotJsonRoundTrip() {
        ensureJavaRailwaysKoinStarted()
        withWorld(load = { tick, physics ->
            CoreWorld.createEmpty(tick, KoinWorldParentContext(javaRailwaysParentScope()), physics)
        }) { world ->
            val segments = PrimeCurveGenerator.buildStraight(80.0, segmentCount = 1)
            val rail = createStraightRailFromSegments(world, segments)
            val sourceTrain = createTrainWithConfig("persist-t1", rail, world, listOf(8.0), isLoop = false)
            IsolatedSimulationThreadMarker.run {
                world.physicsCoordinator.processBetweenFrames {
                    world.renameTrain(sourceTrain, "Player Express")
                }
            }

            val infrastructure = world.saveInfrastructure(1L)
            val runtime = world.saveRuntime(1L, 2L)
            val decodedInfrastructure = WorldSnapshotCodec.decodeInfrastructure(
                WorldSnapshotCodec.encodeInfrastructure(infrastructure)
            )
            val decodedRuntime = WorldSnapshotCodec.decodeRuntime(
                WorldSnapshotCodec.encodeRuntime(runtime)
            )

            withWorld(load = { tick, physics ->
                CoreWorld.load(
                    decodedInfrastructure,
                    decodedRuntime,
                    CoreWorld.SnapshotDependency(
                        tick,
                        KoinWorldParentContext(javaRailwaysParentScope()),
                        physics
                    )
                )
            }) { restored ->
                assertEquals("Player Express", restored.getTrain("persist-t1")?.name)
                assertEquals("persist-t1", restored.getTrain("Player Express")?.id)
                assertNotNull(restored.getRail(rail.id))
                assertTrue(!WorldSnapshotCodec.encodeInfrastructure(infrastructure).contains("\"metadata\""))
                val roundInfrastructure = restored.saveInfrastructure(1L)
                val roundRuntime = restored.saveRuntime(1L, 2L)
                assertEquals(infrastructure.rails.size, roundInfrastructure.rails.size)
                assertEquals(runtime.trains.size, roundRuntime.trains.size)
                assertEquals(
                    WorldSnapshotCodec.encodeInfrastructure(infrastructure),
                    WorldSnapshotCodec.encodeInfrastructure(roundInfrastructure)
                )
                val restoredTrain = restored.getTrain("persist-t1") ?: error("train should load")
                assertEquals(18.0, restoredTrain.publishedFrame.head.arcLengthInAbMeters, 1e-9)
                assertEquals(10.0, restoredTrain.publishedFrame.tail.arcLengthInAbMeters, 1e-9)
                assertEquals(14.0, restoredTrain.cars.single().position.x(), 1e-9)
                assertEquals(ViewDirection.AB, restoredTrain.publishedFrame.head.facing)
                assertEquals(false, restoredTrain.isReversed)
            }
        }
    }

    @Test
    fun worldLoadReportsTheTrainThatFailedToRestore() {
        val infrastructure = CoreWorld.SavedInfrastructureV2(
            formatVersion = CoreWorld.INFRASTRUCTURE_SNAPSHOT_FORMAT_VERSION,
            infrastructureRevision = 7L,
            rails = emptyList()
        )
        val runtime = CoreWorld.SavedRuntimeV1(
            formatVersion = CoreWorld.RUNTIME_SNAPSHOT_FORMAT_VERSION,
            infrastructureRevision = 7L,
            physicsEpoch = 1L,
            trains = listOf(
                SavedTrainV1(
                    id = "00000000-0000-0000-0000-000000000001",
                    name = "broken-train",
                    cars = emptyList(),
                    reversed = false,
                    extension = emptyMap(),
                    headLocation = work.art1st.javarailways.core.physics.rail.RailLocationWithFacing.SavedRailLocationWithFacingV1(
                        "00000000-0000-0000-0000-000000000002", 0.0, ViewDirection.AB
                    ),
                    tailLocation = work.art1st.javarailways.core.physics.rail.RailLocationWithFacing.SavedRailLocationWithFacingV1(
                        "00000000-0000-0000-0000-000000000002", 0.0, ViewDirection.AB
                    )
                )
            )
        )
        TickManagerImpl(Executors.newSingleThreadScheduledExecutor(), 0L).use { tick ->
            PhysicsCoordinator(1, automaticFallback = false).use { physics ->
                val failure = assertThrows(IllegalStateException::class.java) {
                    CoreWorld.load(
                        infrastructure,
                        runtime,
                        CoreWorld.SnapshotDependency(tick, NoOpWorldParentContext, physics)
                    )
                }
                assertTrue(failure.message!!.contains("broken-train"))
                assertNotNull(failure.cause)
            }
        }
    }

    @Test
    fun decodeRejectsUnsupportedSnapshotVersion() {
        val encoded = WorldSnapshotCodec.encodeInfrastructure(
            CoreWorld.SavedInfrastructureV2(
                formatVersion = CoreWorld.INFRASTRUCTURE_SNAPSHOT_FORMAT_VERSION + 1,
                infrastructureRevision = 0L,
                rails = emptyList()
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            WorldSnapshotCodec.decodeInfrastructure(encoded)
        }
    }

    @Test
    fun decodeInvalidJsonThrows() {
        assertThrows(SerializationException::class.java) {
            WorldSnapshotCodec.decodeInfrastructure("{")
        }
    }

    private fun withWorld(
        load: (TickManagerImpl, PhysicsCoordinator) -> CoreWorld,
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
