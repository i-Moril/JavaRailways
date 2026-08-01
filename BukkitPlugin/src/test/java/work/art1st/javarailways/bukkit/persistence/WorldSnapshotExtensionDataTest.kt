package work.art1st.javarailways.bukkit.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotV1
import work.art1st.javarailways.basic.persistence.BasicRuntimeSnapshotV1
import work.art1st.javarailways.basic.persistence.SavedDispatcherV1
import work.art1st.javarailways.bukkit.extension.train.SavedCarRenderState
import work.art1st.javarailways.core.persistence.WorldSnapshotCodec
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.train.SavedTrainV1

class WorldSnapshotExtensionDataTest {
    @Test
    fun `split snapshot requires one infrastructure revision and physics epoch`() {
        val infrastructure = CoreWorld.SavedInfrastructureV1(1, 7L, emptyList())
        val runtime = CoreWorld.SavedRuntimeV1(1, 7L, 9L, emptyList())
        val basicInfrastructure = BasicInfrastructureSnapshotV1(1, 7L, emptyList())
        val basicRuntime = BasicRuntimeSnapshotV1(
            infrastructureRevision = 7L,
            physicsEpoch = 9L,
            timetables = emptyList(),
            dispatcher = SavedDispatcherV1(1, 0L, 0L)
        )

        val capture = WorldSnapshotCapture(
            infrastructure,
            basicInfrastructure,
            runtime,
            basicRuntime
        ).encode()
        val decodedInfrastructure = SplitWorldSnapshotCodec.decodeInfrastructure(
            SplitWorldSnapshotCodec.encode(capture.infrastructure)
        )
        val decodedRuntime = SplitWorldSnapshotCodec.decodeRuntime(
            SplitWorldSnapshotCodec.encode(capture.runtime)
        )

        assertEquals(7L, decodedInfrastructure.infrastructureRevision)
        assertEquals(9L, decodedRuntime.physicsEpoch)
        assertThrows(IllegalArgumentException::class.java) {
            WorldSnapshotCapture(
                infrastructure,
                basicInfrastructure.copy(infrastructureRevision = 8L),
                runtime,
                basicRuntime
            )
        }
    }

    @Test
    fun `world snapshot codec preserves bukkit render extension data`() {
        val snapshot = CoreWorld.SavedRuntimeV1(
            infrastructureRevision = 1L,
            physicsEpoch = 1L,
            trains = listOf(
                SavedTrainV1(
                    id = "00000000-0000-0000-0000-00000000000a",
                    name = "train-a",
                    cars = emptyList(),
                    reversed = false,
                    extension = mapOf("BUKKIT" to SavedCarRenderState("s100/passenger"))
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
