package work.art1st.javarailways.simpleato.timetable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.line.LineId

class LineMaximumSpeedResolverTest {
    @Test
    fun `resolves one all-train maximum speed from the service infrastructure line`() {
        val timetable = Timetable(
            id = "weekday",
            services = listOf(TimetableService("local", lineId = LineId("metro"))),
            lineSpeedLimits = listOf(TimetableLineSpeedLimit(LineId("metro"), 22.0))
        )

        assertEquals(22.0, LineMaximumSpeedResolver.resolve(timetable, timetable.services.single()))
    }

    @Test
    fun `service without infrastructure line has no timetable speed cap`() {
        assertNull(LineMaximumSpeedResolver.resolve(Timetable("legacy"), TimetableService("local")))
    }
}
