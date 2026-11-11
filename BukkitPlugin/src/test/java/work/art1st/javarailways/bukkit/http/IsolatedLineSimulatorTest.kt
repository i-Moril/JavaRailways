package work.art1st.javarailways.bukkit.http

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IsolatedLineSimulatorTest {
    private fun input(originOffset: Double = 0.0, departure: JsonElement = JsonPrimitive(100)) = buildJsonObject {
        put("measurementSource", "function main(timetable) {}")
        put("firstDepartureTick", departure)
        put("fixedDwellTicks", 20)
        put("minimumDwellTicks", 0)
        put("calls", buildJsonArray {
            add(buildJsonObject {
                put("callId", "a"); put("stationId", "a"); put("destinationId", "a@one"); put("kind", "ORIGIN")
                put("stoppingOffsetMetersBefore", originOffset)
            })
            add(buildJsonObject {
                put("callId", "b"); put("stationId", "b"); put("destinationId", "b@one"); put("kind", "TERMINAL")
            })
        })
    }

    @Test
    fun `origin offset unsupported by actual initial placement is rejected rather than ignored`() {
        assertEquals(100L, LineMeasurementRequest.parse(input()).firstDepartureTick)
        listOf<JsonElement?>(null, JsonNull).forEach { offset ->
            val request = input()
            val calls = request.getValue("calls").jsonArray.toMutableList()
            val origin = calls.first().jsonObject.toMutableMap()
            if (offset == null) origin.remove("stoppingOffsetMetersBefore") else origin["stoppingOffsetMetersBefore"] = offset
            calls[0] = JsonObject(origin)
            assertEquals(100L, LineMeasurementRequest.parse(JsonObject(request + ("calls" to JsonArray(calls)))).firstDepartureTick)
        }
        val failure = assertThrows(IllegalArgumentException::class.java) { LineMeasurementRequest.parse(input(1.0)) }
        assertTrue(failure.message!!.contains("origin stopping offset"))
    }

    @Test
    fun `measurement ticks require actual integers and safe bounded budget`() {
        listOf(JsonPrimitive("100"), JsonPrimitive(1.5), JsonPrimitive(-1)).forEach { departure ->
            assertThrows(IllegalArgumentException::class.java) { LineMeasurementRequest.parse(input(departure = departure)) }
        }
        val request = LineMeasurementRequest.parse(input())
        assertThrows(IllegalArgumentException::class.java) { request.copy(maximumDurationTicks = 0) }
        assertThrows(IllegalArgumentException::class.java) { request.copy(maximumDurationTicks = 1_728_001) }
    }
}
