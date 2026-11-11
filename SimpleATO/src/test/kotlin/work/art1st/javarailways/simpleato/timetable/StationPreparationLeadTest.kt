package work.art1st.javarailways.simpleato.timetable

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StationPreparationLeadTest {
    private fun lead(json: String) = StationPreparationLead(Json.parseToJsonElement(json))
    @Test fun `duration leaf and numeric arithmetic are bounded typed and folded`() {
        val expression = lead("""{"operation":{"type":"ADD","operands":[{"doorCloseTicks":true},{"operation":{"type":"MULTIPLY","operands":[{"value":3},{"value":5}]}}]}}""")
        assertTrue(expression.requiresDoorCloseDuration)
        assertEquals(95L, expression.evaluate(80))
        assertEquals(Json.parseToJsonElement("""{"operation":{"type":"ADD","operands":[{"doorCloseTicks":true},{"value":15}]}}"""), expression.expression)
        assertEquals(10L, lead("""{"operation":{"type":"ROUND","operands":[{"value":9.5}]}}""").evaluate())
        assertEquals(9L, lead("""{"operation":{"type":"FLOOR","operands":[{"value":9.9}]}}""").evaluate())
        assertEquals(10L, lead("""{"operation":{"type":"CEIL","operands":[{"value":9.1}]}}""").evaluate())
    }
    @Test fun `invalid sources malformed trees and invalid final ticks fail`() {
        for (source in listOf("{}", "{\"value\":\"20\"}", "{\"value\":-1}", "{\"value\":0.3}",
            "{\"doorCloseTicks\":false}", "{\"parameterId\":\"x\"}", "{\"trainInfo\":{}}", "{\"value\":9007199254740992}",
            """{"operation":{"type":"TO_NUMBER","operands":[{"value":"1"}]}}""",
            """{"operation":{"type":"DIVIDE","operands":[{"value":2},{"value":0}]}}"""))
            assertThrows(RuntimeException::class.java, { lead(source) }, source)
        val negative = lead("""{"operation":{"type":"SUBTRACT","operands":[{"value":1},{"doorCloseTicks":true}]}}""")
        assertThrows(RuntimeException::class.java) { negative.evaluate(2) }
        val fractional = lead("""{"operation":{"type":"DIVIDE","operands":[{"doorCloseTicks":true},{"value":2}]}}""")
        assertThrows(RuntimeException::class.java) { fractional.evaluate(3) }
        var deep = "{\"doorCloseTicks\":true}"
        repeat(17) { deep = "{\"operation\":{\"type\":\"CEIL\",\"operands\":[$deep]}}" }
        assertThrows(RuntimeException::class.java) { lead(deep) }
    }
}
