package work.art1st.javarailways.core.persistence

import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
        withWorld(load = { tick, physics ->
            CoreWorld.createEmpty(tick, physics)
        }) { world ->
            val segments = PrimeCurveGenerator.buildStraight(80.0, segmentCount = 1)
            val rail = createStraightRailFromSegments(world, segments)
            val sourceTrain = createTrainWithConfig("persist-t1", rail, world, listOf(8.0), isLoop = false)
            val originalCarIds = sourceTrain.cars.map { it.id }
            val originalComposition = sourceTrain.composition
            assertEquals("persist-t1", sourceTrain.name)
            assertEquals("persist-t1", originalComposition.units.single().unitId)
            IsolatedSimulationThreadMarker.run {
                world.physicsCoordinator.processBetweenFrames {
                    world.renameTrain(sourceTrain, "Player Express")
                }
            }
            assertEquals("persist-t1", sourceTrain.id)
            assertEquals(originalCarIds, sourceTrain.cars.map { it.id })
            assertEquals(originalComposition, sourceTrain.composition)

            val infrastructure = world.saveInfrastructure(1L)
            val runtime = world.saveRuntime(1L, 2L)
            val decodedInfrastructure = WorldSnapshotCodec.decodeInfrastructure(
                WorldSnapshotCodec.encodeInfrastructure(infrastructure)
            )
            val decodedRuntime = WorldSnapshotCodec.decodeRuntime(
                WorldSnapshotCodec.encodeRuntime(runtime)
            )

            withWorld(load = { tick, physics ->
                IsolatedSimulationThreadMarker.run { CoreWorld.load(
                    decodedInfrastructure,
                    decodedRuntime,
                    CoreWorld.SnapshotDependency(
                        tick,
                        physics
                    )
                ) }
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
                assertEquals("persist-t1", restoredTrain.id)
                assertEquals(originalCarIds, restoredTrain.cars.map { it.id })
                assertEquals("persist-t1", restoredTrain.composition.units.single().unitId)
                assertEquals(sourceTrain.composition, restoredTrain.composition)
                assertEquals(sourceTrain.composition, restoredTrain.publishedFrame.composition)
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
                    IsolatedSimulationThreadMarker.run { CoreWorld.load(
                        infrastructure,
                        runtime,
                        CoreWorld.SnapshotDependency(tick, physics)
                    ) }
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

    @Test
    fun `future root versions reject before malformed nested values`() {
        val runtime = assertThrows(IllegalArgumentException::class.java) {
            WorldSnapshotCodec.decodeRuntime("""{"formatVersion":99,"trains":[{"extension":{"TEST":{"typeId":"unknown:payload","schemaVersion":1,"payload":{}}}}]}""")
        }
        assertTrue(runtime.message!!.contains("Unsupported core runtime snapshot version"))
        val infrastructure = assertThrows(IllegalArgumentException::class.java) {
            WorldSnapshotCodec.decodeInfrastructure("""{"formatVersion":99,"rails":["malformed"]}""")
        }
        assertTrue(infrastructure.message!!.contains("Unsupported core infrastructure snapshot version"))
    }

    private fun withWorld(
        load: (TickManagerImpl, PhysicsCoordinator) -> CoreWorld,
        block: (CoreWorld) -> Unit
    ) {
        TickManagerImpl(Executors.newSingleThreadScheduledExecutor(), 0L).use { tick ->
            run {
                PhysicsCoordinator(1, automaticFallback = false).use { physics ->
                    val created = load(tick, physics)
                    block(created)
                }
            }
        }
    }
}
