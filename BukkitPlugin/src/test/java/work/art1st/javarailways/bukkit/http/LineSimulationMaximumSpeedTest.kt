package work.art1st.javarailways.bukkit.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LineSimulationMaximumSpeedTest {
    @Test
    fun `absent and null inline caps preserve uncapped authoring requests`() {
        listOf("{}", """{"maximumSpeedMetersPerSecond":null}""").forEach { body ->
            assertNull(resolveLineSimulationMaximumSpeed(Json.parseToJsonElement(body).jsonObject) { _, _, _ ->
                error("must not read stored timetables")
            })
        }
    }

    @Test
    fun `accepts positive JSON numbers without coercing strings or other shapes`() {
        listOf("5", "5.5", "1e1").forEach { number ->
            assertEquals(number.toDouble(), resolveLineSimulationMaximumSpeed(
                Json.parseToJsonElement("""{"maximumSpeedMetersPerSecond":$number}""").jsonObject
            ) { _, _, _ -> error("must not read stored timetables") })
        }
        listOf("0", "-1", "\"5\"", "true", "false", "[]", "{}", "1e999").forEach { value ->
            val failure = assertThrows(HttpFailure::class.java) {
                resolveLineSimulationMaximumSpeed(Json.parseToJsonElement(
                    """{"maximumSpeedMetersPerSecond":$value}"""
                ).jsonObject) { _, _, _ -> error("invalid request cannot resolve a stored service") }
            }
            assertEquals(400, failure.status)
        }
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { value ->
            val failure = assertThrows(HttpFailure::class.java) {
                resolveLineSimulationMaximumSpeed(buildJsonObject {
                    put("maximumSpeedMetersPerSecond", JsonPrimitive(value))
                }) { _, _, _ -> error("invalid request cannot resolve a stored service") }
            }
            assertEquals(400, failure.status)
        }
    }

    @Test
    fun `stored pair remains authoritative when inline cap is omitted or null`() {
        listOf("", ",\"maximumSpeedMetersPerSecond\":null").forEach { suffix ->
            assertEquals(7.5, resolveLineSimulationMaximumSpeed(Json.parseToJsonElement(
                """{"timetableId":"weekday","serviceId":"service"$suffix}"""
            ).jsonObject) { timetable, service, runId ->
                assertNull(runId)
                assertEquals("weekday", timetable)
                assertEquals("service", service)
                7.5
            })
        }
    }

    @Test
    fun `stored segment identity is passed exactly and requires stored pair`() {
        assertEquals(3.0, resolveLineSimulationMaximumSpeed(Json.parseToJsonElement(
            """{"timetableId":"t","serviceId":"s","runId":"second-main"}"""
        ).jsonObject) { timetable, service, run ->
            assertEquals("t", timetable); assertEquals("s", service); assertEquals("second-main", run)
            3.0
        })
        listOf("""{"runId":"r"}""", """{"timetableId":"t","serviceId":"s","runId":""}""").forEach { body ->
            assertThrows(HttpFailure::class.java) {
                resolveLineSimulationMaximumSpeed(Json.parseToJsonElement(body).jsonObject) { _, _, _ -> error("invalid selection") }
            }
        }
    }

    @Test
    fun `rejects mixed inline and stored caps and incomplete stored references before lookup`() {
        listOf(
            """{"timetableId":"weekday","serviceId":"service","maximumSpeedMetersPerSecond":5}""",
            """{"timetableId":"weekday"}""",
            """{"serviceId":"service","maximumSpeedMetersPerSecond":5}"""
        ).forEach { body ->
            val failure = assertThrows(HttpFailure::class.java) {
                resolveLineSimulationMaximumSpeed(Json.parseToJsonElement(body).jsonObject) { _, _, _ ->
                    error("ambiguous request cannot resolve stored caps")
                }
            }
            assertEquals(400, failure.status)
        }
    }
}
