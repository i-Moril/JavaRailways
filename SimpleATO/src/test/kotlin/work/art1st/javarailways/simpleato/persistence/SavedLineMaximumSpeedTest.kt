package work.art1st.javarailways.simpleato.persistence

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SavedLineMaximumSpeedTest {
    private val fixture = """{"id":"metro","displayName":"Metro","upStationIds":["a","b"],"downStationIds":["b","a"]}"""

    @Test
    fun `existing lines have an explicit unset cap and saved caps survive restore`() {
        val unset = Json.decodeFromString<SavedLineV1>(fixture)
        assertEquals(null, unset.restore().maximumSpeedMetersPerSecond)
        val configured = unset.copy(maximumSpeedMetersPerSecond = 25.0).restore()
        val encoded = Json.encodeToString(SavedLineV1.capture(configured))
        assertEquals(25.0, Json.decodeFromString<SavedLineV1>(encoded).restore().maximumSpeedMetersPerSecond)
    }

    @Test
    fun `invalid stored caps fail before materializing a line`() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { speed ->
            assertThrows(IllegalArgumentException::class.java) {
                Json.decodeFromString<SavedLineV1>(fixture).copy(maximumSpeedMetersPerSecond = speed).restore()
            }
        }
    }
}
