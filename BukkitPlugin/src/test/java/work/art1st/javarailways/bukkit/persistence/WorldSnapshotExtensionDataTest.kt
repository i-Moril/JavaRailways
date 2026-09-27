package work.art1st.javarailways.bukkit.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotV1
import work.art1st.javarailways.basic.persistence.BasicRuntimeSnapshotV3
import work.art1st.javarailways.simpleato.persistence.SavedDispatcherV1
import work.art1st.javarailways.simpleato.persistence.SimpleATORuntimeSnapshotV1
import work.art1st.javarailways.bukkit.extension.train.SavedCarRenderState
import work.art1st.javarailways.core.persistence.WorldSnapshotCodec
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.train.SavedTrainV1

class WorldSnapshotExtensionDataTest {
    @Test
    fun `split snapshot requires one infrastructure revision and physics epoch`() {
        val infrastructure = CoreWorld.SavedInfrastructureV2(CoreWorld.INFRASTRUCTURE_SNAPSHOT_FORMAT_VERSION, 7L, emptyList())
        val runtime = CoreWorld.SavedRuntimeV1(1, 7L, 9L, emptyList())
        val basicInfrastructure = BasicInfrastructureSnapshotV1(
            1, 7L, emptyList(), emptyList(), emptyList(), 0L
        )
        val basicRuntime = BasicRuntimeSnapshotV3(
            formatVersion = BasicRuntimeSnapshotV3.CURRENT_FORMAT_VERSION,
            infrastructureRevision = 7L,
            physicsEpoch = 9L,
            rollingStock = emptyList()
        )
        val simpleAtoRuntime = SimpleATORuntimeSnapshotV1(
            formatVersion = SimpleATORuntimeSnapshotV1.CURRENT_FORMAT_VERSION,
            infrastructureRevision = 7L,
            physicsEpoch = 9L,
            dispatcher = SavedDispatcherV1(1, 0L, 0L, emptyList(), emptyList())
        )

        val capture = WorldSnapshotCapture(
            infrastructure,
            basicInfrastructure,
            runtime,
            basicRuntime,
            simpleAtoRuntime
        ).encode()
        val decodedInfrastructure = SplitWorldSnapshotCodec.decodeInfrastructure(
            SplitWorldSnapshotCodec.encode(capture.infrastructure)
        )
        val decodedRuntime = SplitWorldSnapshotCodec.decodeRuntime(
            SplitWorldSnapshotCodec.encode(capture.runtime)
        )

        assertEquals(7L, decodedInfrastructure.infrastructureRevision)
        assertEquals(9L, decodedRuntime.physicsEpoch)
        assertEquals(7L, decodedRuntime.basic().infrastructureRevision)
        assertEquals(9L, decodedRuntime.simpleAto().physicsEpoch)
        assertThrows(IllegalArgumentException::class.java) {
            WorldSnapshotCapture(
                infrastructure,
                basicInfrastructure.copy(infrastructureRevision = 8L),
                runtime,
                basicRuntime,
                simpleAtoRuntime
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorldSnapshotCapture(
                infrastructure,
                basicInfrastructure,
                runtime,
                basicRuntime,
                simpleAtoRuntime.copy(physicsEpoch = 10L)
            )
        }
    }

    @Test
    fun `world snapshot codec preserves bukkit render extension data`() {
        val snapshot = CoreWorld.SavedRuntimeV1(
            formatVersion = CoreWorld.RUNTIME_SNAPSHOT_FORMAT_VERSION,
            infrastructureRevision = 1L,
            physicsEpoch = 1L,
            trains = listOf(
                SavedTrainV1(
                    id = "00000000-0000-0000-0000-00000000000a",
                    name = "train-a",
                    cars = emptyList(),
                    reversed = false,
                    extension = mapOf("BUKKIT" to SavedCarRenderState("s100/passenger")),
                    headLocation = work.art1st.javarailways.core.physics.rail.RailLocationWithFacing.SavedRailLocationWithFacingV1(
                        "00000000-0000-0000-0000-00000000000b", 0.0,
                        work.art1st.javarailways.core.api.physics.rail.ViewDirection.AB
                    ),
                    tailLocation = work.art1st.javarailways.core.physics.rail.RailLocationWithFacing.SavedRailLocationWithFacingV1(
                        "00000000-0000-0000-0000-00000000000b", 0.0,
                        work.art1st.javarailways.core.api.physics.rail.ViewDirection.AB
                    )
                )
            )
        )

        val json = WorldSnapshotCodec.encodeRuntime(snapshot)
        val decoded = WorldSnapshotCodec.decodeRuntime(json)
        val state = decoded.trains.single().extension.getValue("BUKKIT")

        assertTrue(state is SavedCarRenderState)
        assertEquals("s100/passenger", (state as SavedCarRenderState).carModelName)
    }
}
