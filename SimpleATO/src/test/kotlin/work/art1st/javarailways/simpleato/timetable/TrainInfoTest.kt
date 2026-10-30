package work.art1st.javarailways.simpleato.timetable

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionCall
import work.art1st.javarailways.simpleato.persistence.*

class TrainInfoTest {
    private val empty = JsonObject(emptyMap())
    private val bindings = TrainInfoField.entries.associateBy { it.name }

    @Test fun `all action contexts preserve explicit bindings through persisted JSON`() {
        val duty = listOf(TimetableAction.SetDisplay(listOf(1), empty, trainInfo = bindings),
            TimetableAction.Extension(TimetableExtensionCall("test:info"), bindings))
        duty.forEach { value ->
            val saved = SavedTimetableActionV1.capture(value)
            assertEquals(value, Json.decodeFromString<SavedTimetableActionV1>(Json.encodeToString(SavedTimetableActionV1.serializer(), saved)).restore())
        }
        val station = listOf(StationProgramAction.SetDisplay(listOf(1), empty, trainInfo = bindings),
            StationProgramAction.Extension(TimetableExtensionCall("test:info"), bindings))
        station.forEach { value ->
            val saved = SavedStationProgramAction.capture(value)
            assertEquals(value, Json.decodeFromString<SavedStationProgramAction>(Json.encodeToString(SavedStationProgramAction.serializer(), saved)).restore())
        }
    }

    @Test fun `binding keys and action ownership reject malformed snapshots`() {
        assertThrows(Exception::class.java) { Json.decodeFromString<SavedTimetableActionV1>("""{"type":"EXTENSION","trainInfo":{"value":"MODEL"}}""") }
        assertThrows(IllegalArgumentException::class.java) { SavedTimetableActionV1(SavedTimetableActionTypeV1.REVERSE, trainInfo = mapOf("name" to SavedTrainInfoField.NAME)) }
        assertThrows(IllegalArgumentException::class.java) { SavedStationProgramAction(SavedStationProgramActionType.REVERSE, trainInfo = mapOf("name" to SavedTrainInfoField.NAME)) }
        for (keys in listOf(listOf(" "), listOf("x".repeat(257)), (1..65).map { "k$it" })) {
            assertThrows(IllegalArgumentException::class.java) { TimetableAction.Extension(TimetableExtensionCall("test:info"), keys.associateWith { TrainInfoField.NAME }) }
        }
    }

    @Test fun `bindings freeze caller maps and cannot mix literals`() {
        val mutable = mutableMapOf("name" to TrainInfoField.NAME)
        val action = TimetableAction.Extension(TimetableExtensionCall("test:info"), mutable)
        mutable.clear()
        assertEquals(mapOf("name" to TrainInfoField.NAME), action.trainInfo)
        val literal = kotlinx.serialization.json.buildJsonObject { put("trainInfo", empty) }
        assertEquals(literal, TimetableAction.Extension(TimetableExtensionCall("test:info", parameters = literal)).call.parameters)
        assertThrows(IllegalArgumentException::class.java) { TimetableAction.Extension(TimetableExtensionCall("test:info", parameters = literal), bindings) }
        assertThrows(IllegalArgumentException::class.java) { StationProgramAction.SetDisplay(listOf(1), literal, trainInfo = bindings) }
    }
}
