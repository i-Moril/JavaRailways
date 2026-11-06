package work.art1st.javarailways.bukkit.http

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LineSimulationMaximumSpeedTest {
    @Test
    fun `measurement uses configured line cap and rejects overrides and missing caps`() {
        assertEquals(20.0, resolveInfrastructureLineMaximumSpeed(20.0, null))
        assertEquals(20.0, resolveInfrastructureLineMaximumSpeed(20.0, 20.0))
        assertEquals(400, assertThrows(HttpFailure::class.java) { resolveInfrastructureLineMaximumSpeed(20.0, 30.0) }.status)
        assertEquals(422, assertThrows(HttpFailure::class.java) { resolveInfrastructureLineMaximumSpeed(null, 30.0) }.status)
    }

    @Test
    fun `cap parsing rejects nonnumeric and nonpositive input without coercion`() {
        assertNull(parseLineMaximumSpeed(null))
        assertNull(parseLineMaximumSpeed(Json.parseToJsonElement("null")))
        listOf("5", "5.5", "1e1").forEach {
            assertEquals(it.toDouble(), parseLineMaximumSpeed(Json.parseToJsonElement(it)))
        }
        listOf("0", "-1", "\"5\"", "true", "[]", "{}", "1e999").forEach {
            assertThrows(HttpFailure::class.java) { parseLineMaximumSpeed(Json.parseToJsonElement(it)) }
        }
    }
}
