package work.art1st.javarailways.basic.rail

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SpeedLimitMetadataTest {
    @Test
    fun `construction defensively copies the caller list`() {
        val source = ArrayList<SpeedLimitEntry>().apply {
            add(SpeedLimitEntry(10.0, SpeedLimitType.SUBGRADE))
        }
        val metadata = SpeedLimitMetadata(source)

        source.clear()
        source.add(SpeedLimitEntry(1.0, SpeedLimitType.CURVE))

        assertEquals(listOf(SpeedLimitEntry(10.0, SpeedLimitType.SUBGRADE)), metadata.entries)
        assertEquals(10.0, metadata.effectiveLimitMetersPerSecond())
        assertThrows(UnsupportedOperationException::class.java) {
            (metadata.entries as MutableList<SpeedLimitEntry>).add(SpeedLimitEntry(1.0, SpeedLimitType.CURVE))
        }
    }

    @Test
    fun `duplicate types are rejected before and after external mutation`() {
        val source = ArrayList<SpeedLimitEntry>()
        assertThrows(IllegalArgumentException::class.java) {
            SpeedLimitMetadata(
                listOf(
                    SpeedLimitEntry(10.0, SpeedLimitType.SUBGRADE),
                    SpeedLimitEntry(20.0, SpeedLimitType.SUBGRADE)
                )
            )
        }
        val metadata = SpeedLimitMetadata(source)
        source.add(SpeedLimitEntry(10.0, SpeedLimitType.SUBGRADE))
        source.add(SpeedLimitEntry(20.0, SpeedLimitType.SUBGRADE))
        assertEquals(emptyList<SpeedLimitEntry>(), metadata.entries)
        assertEquals(null, metadata.effectiveLimitMetersPerSecond())
    }

    @Test
    fun `serializer keeps the stable entries field and round-trips`() {
        val metadata = SpeedLimitMetadata(
            listOf(
                SpeedLimitEntry(30.0, SpeedLimitType.SUBGRADE),
                SpeedLimitEntry(20.0, SpeedLimitType.CURVE)
            )
        )

        val encoded = Json.encodeToString(SpeedLimitMetadata.serializer(), metadata)
        val decoded = Json.decodeFromString(SpeedLimitMetadata.serializer(), encoded)

        assertEquals(metadata.entries, decoded.entries)
        assertEquals("""{"entries":[{"valueMetersPerSecond":30.0,"type":"SUBGRADE"},{"valueMetersPerSecond":20.0,"type":"CURVE"}]}""", encoded)
    }
    @Test
    fun `decoded entries cannot mutate the metadata and missing entries remain empty`() {
        val decoded = Json.decodeFromString(SpeedLimitMetadata.serializer(),
            """{"entries":[{"valueMetersPerSecond":30.0,"type":"SUBGRADE"}]}""")
        assertThrows(UnsupportedOperationException::class.java) {
            (decoded.entries as MutableList<SpeedLimitEntry>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (decoded.entries as MutableList<SpeedLimitEntry>)[0] = SpeedLimitEntry(1.0, SpeedLimitType.CURVE)
        }
        assertEquals(30.0, decoded.value(SpeedLimitType.SUBGRADE))
        assertEquals(30.0, decoded.effectiveLimitMetersPerSecond())
        assertEquals(emptyList<SpeedLimitEntry>(),
            Json.decodeFromString(SpeedLimitMetadata.serializer(), "{}").entries)
    }

    @Test
    fun `decoder rejects duplicate speed limit types`() {
        assertThrows(IllegalArgumentException::class.java) {
            Json.decodeFromString(SpeedLimitMetadata.serializer(),
                """{"entries":[{"valueMetersPerSecond":30.0,"type":"SUBGRADE"},{"valueMetersPerSecond":20.0,"type":"SUBGRADE"}]}""")
        }
    }

}
