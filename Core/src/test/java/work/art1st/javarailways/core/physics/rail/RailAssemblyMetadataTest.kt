package work.art1st.javarailways.core.physics.rail

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.RailMetadata

class RailAssemblyMetadataTest {
    @Test
    fun `metadata persisted before assemblies defaults to an ungrouped rail`() {
        val metadata = Json { ignoreUnknownKeys = false }.decodeFromString<RailMetadata>("{}")

        assertNull(metadata.assembly)
    }
}
