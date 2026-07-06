package work.art1st.mineRails.bukkit.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.mineRails.bukkit.extension.train.SavedCarRenderState
import work.art1st.mineRails.core.persistence.WorldSnapshotCodec
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.physics.train.SavedTrainV1

class WorldSnapshotExtensionDataTest {
    @Test
    fun `world snapshot codec preserves bukkit render extension data`() {
        val snapshot = CoreWorld.SavedWorldV1(
            rails = emptyList(),
            trains = listOf(
                SavedTrainV1(
                    id = "train-a",
                    cars = emptyList(),
                    extension = mapOf("BUKKIT" to SavedCarRenderState("s100/passenger"))
                )
            )
        )

        val json = WorldSnapshotCodec.encodeToString(snapshot)
        val decoded = WorldSnapshotCodec.decodeFromString(json)
        val state = decoded.trains.single().extension.getValue("BUKKIT")

        assertTrue(state is SavedCarRenderState)
        assertEquals("s100/passenger", (state as SavedCarRenderState).carModelName)
    }
}
