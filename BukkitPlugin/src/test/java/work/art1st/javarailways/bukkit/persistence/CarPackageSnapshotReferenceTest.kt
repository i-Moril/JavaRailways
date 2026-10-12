package work.art1st.javarailways.bukkit.persistence

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.extension.train.SavedCarRenderState
import work.art1st.javarailways.core.persistence.WorldSnapshotCodec
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.*

class CarPackageSnapshotReferenceTest {
    @Test
    fun `fixed current two-source car references survive nonempty train runtime codec`() {
        // Current-model fixture, not a claim that a legacy deployed train was migrated.
        val states = Json.decodeFromString<List<SavedCarRenderState>>("""
            [{"carModelName":"first/head","heldScenes":{"door-left":"open"},"packageSnapshotHash":"first-pinned-hash"},
             {"carModelName":"second/tail","heldScenes":{"door-right":"open"},"packageSnapshotHash":"second-pinned-hash"}]
        """.trimIndent())
        val world = createTestWorld()
        (world.tickManager as AutoCloseable).use {
            AutoCloseable { world.releaseRuntimeBinding() }.use {
                world.physicsCoordinator.use {
                    val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1))
                    val train = createTrainWithConfig("mixed", rail, world, listOf(10.0, 10.0))
                    val saved = train.save()
                    val cars = saved.cars.mapIndexed { index, car ->
                        car.copy(config = car.config.copy(extension = mapOf("BUKKIT" to states[index])))
                    }
                    val runtime = CoreWorld.SavedRuntimeV1(1, 7L, 11L, listOf(saved.copy(cars = cars)))
                    val restored = WorldSnapshotCodec.decodeRuntime(WorldSnapshotCodec.encodeRuntime(runtime))
                    val restoredStates = restored.trains.single().cars.map {
                        it.config.extension.getValue("BUKKIT") as SavedCarRenderState
                    }
                    assertEquals(states, restoredStates)
                    assertNotEquals(restoredStates[0].packageSnapshotHash, restoredStates[1].packageSnapshotHash)
                    assertEquals("open", restoredStates[0].heldScenes["door-left"])
                    assertEquals(1, restored.formatVersion)
                    assertEquals(7L, restored.infrastructureRevision)
                    assertEquals(11L, restored.physicsEpoch)
                }
            }
        }
    }
}
