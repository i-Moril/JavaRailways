package work.art1st.javarailways.scriptworker

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.persistence.TimetableSnapshotCodec
import work.art1st.javarailways.simpleato.timetable.StationProgramAction
import work.art1st.javarailways.simpleato.timetable.StationStopRole
import work.art1st.javarailways.simpleato.timetable.TimetableAction
import work.art1st.javarailways.simpleato.timetable.TimetableStep
import work.art1st.javarailways.simpleato.timetable.TrainSelector

/** Exact current HTTP browser capture, not a hand-written approximation of its source generator. */
class BrowserGeneratedStationProgramTest {
    private fun resource(name: String): String = checkNotNull(javaClass.getResource("/timetable/$name")).readText()

    @Test fun `captured browser source compiles through Graal and restores complete station contracts`() {
        val document = Json.parseToJsonElement(resource("browser-generated-station-programs.document.json")).jsonObject
        val source = resource("browser-generated-station-programs.js")
        val id = document.getValue("id").jsonPrimitive.content
        val encoded = executeScript(source, id)
        val worker = Json.decodeFromString<SavedTimetableV1>(encoded)
        val restored = TimetableSnapshotCodec.decode(encoded).restore()
        val service = restored.services.single()
        assertEquals(id, restored.id)
        assertEquals("D1", service.id)
        assertEquals(TrainSelector.TrainId("QA train"), service.trainSelector)
        assertEquals(20.0, restored.lineSpeedLimits.single().maximumSpeedMetersPerSecond)

        // INLINE is the first duty element. Its generated initial CHECK must precede both its
        // display instruction and the run scope, so the real compiler can establish acquisition.
        assertEquals(TimetableStep.Check("a@1"), service.steps[0])
        val display = (service.steps[1] as TimetableStep.At).action as TimetableAction.SetDisplay
        assertEquals(listOf(1), display.unitSlots)
        assertEquals(JsonPrimitive("C"), display.values["destination"])
        assertTrue(worker.compilationTrace.any { it.stepIndex == 1 && it.origin == "duty:D1/element:display/program:display/step:display-info" })

        val originStep = service.steps[2] as TimetableStep.At
        val origin = (originStep.action as TimetableAction.BeginStationStop).stopPrograms
        val incoming = service.steps.filterIsInstance<TimetableStep.At>().mapNotNull {
            (it.action as? TimetableAction.DestinationOptions)?.stopPrograms
        }
        val programs = listOf(origin) + incoming
        assertEquals(listOf(StationStopRole.ORIGIN, StationStopRole.STOP, StationStopRole.TERMINAL), programs.map { it.role })
        assertEquals(listOf("a", "b", "c"), programs.map { it.stationId })
        assertEquals(listOf("a@1", "b@1", "c@1"), programs.map { it.destinationId })
        assertEquals(listOf(100L,300L,600L), programs.map { it.plannedDepartureTick })
        assertEquals(0L, originStep.tick, "untimed origin scope retains prior schedule; preparation has its own planned deadline")
        for (program in programs) {
            assertEquals(20L, program.preparationLeadTicks)
            assertEquals(listOf(StationProgramAction.OpenDoors, StationProgramAction.End), program.arrival)
            assertEquals(listOf(StationProgramAction.CloseDoors, StationProgramAction.End), program.departurePreparation)
        }
        assertEquals(20L, incoming.first().minimumDwellTicks)

        val segment = service.runSegments.single()
        assertEquals("R1", segment.runId)
        assertEquals(2, segment.startStepIndex)
        assertEquals(service.steps.size, segment.endStepIndexExclusive)
        assertEquals(listOf("junction"), segment.calls[1].viaIds)
        val via = service.steps.filterIsInstance<TimetableStep.At>().map { it.action }.filterIsInstance<TimetableAction.GoVia>().single()
        assertEquals(listOf("junction","b@1"), via.wayPointIds)
        assertEquals(document.getValue("runs").jsonArray.single().jsonObject.getValue("calls").jsonArray.size, segment.calls.size)
    }
}
