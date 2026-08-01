package work.art1st.javarailways.core.physics.rail

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.RailMetadata
import work.art1st.javarailways.core.api.physics.rail.RailSplitMetadata

class RailSplitMetadataTest {
    @Test
    fun `split lineage survives persistence`() {
        val metadata = RailMetadata(
            designSpeedMetersPerSecond = 30.0,
            createdBySplit = RailSplitMetadata(
                operationId = "child",
                segmentIndex = 1,
                parent = RailSplitMetadata("parent", 0)
            )
        )

        val encoded = Json.encodeToString(metadata)
        val restored = Json.decodeFromString<RailMetadata>(encoded)

        assertEquals(metadata, restored)
    }
}
