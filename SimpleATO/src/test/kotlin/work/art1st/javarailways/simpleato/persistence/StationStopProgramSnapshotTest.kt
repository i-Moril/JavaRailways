package work.art1st.javarailways.simpleato.persistence

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.timetable.*

class StationStopProgramSnapshotTest {
    @Test fun `shared stationary trees round trip on origin and incoming destination`() {
        val arrival = mutableListOf<StationProgramAction>(StationProgramAction.OpenDoors,
            StationProgramAction.Async("announcement", listOf(StationProgramAction.WaitDuration(8))), StationProgramAction.End)
        val programs = StationStopPrograms("central", "call-b", StationStopRole.STOP, arrival,
            listOf(StationProgramAction.Await("announcement"), StationProgramAction.Reverse, StationProgramAction.CloseDoors, StationProgramAction.End), 10, 100, "B")
        arrival.clear()
        assertEquals(3, programs.arrival.size)
        assertThrows(UnsupportedOperationException::class.java) { (programs.arrival as MutableList).clear() }
        val origin = StationStopPrograms("central", "call-a", StationStopRole.ORIGIN, listOf(StationProgramAction.End), listOf(StationProgramAction.CloseDoors, StationProgramAction.End), 10, 100, "A")
        for (action in listOf(TimetableAction.BeginStationStop(origin), TimetableAction.DestinationOptions("B", stopPrograms = programs))) {
            val saved = SavedTimetableActionV1.capture(action)
            val decoded = Json.decodeFromString(SavedTimetableActionV1.serializer(), Json.encodeToString(SavedTimetableActionV1.serializer(), saved))
            assertEquals(action, decoded.restore())
        }
    }

    @Test fun `invalid task graphs and termination cannot enter current model`() {
        fun create(actions: List<StationProgramAction>) = StationStopPrograms("s", "c", StationStopRole.STOP,
            actions, listOf(StationProgramAction.End), 0, 10)
        assertThrows(IllegalArgumentException::class.java) { create(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { create(listOf(StationProgramAction.Await("future"), StationProgramAction.End)) }
        assertThrows(IllegalArgumentException::class.java) { create(listOf(StationProgramAction.Async("a", listOf(StationProgramAction.End)), StationProgramAction.End)) }
    }
}
