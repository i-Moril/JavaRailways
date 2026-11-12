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

/** Current frontend-generator fixture exercising the real Graal and snapshot/domain boundaries. */
class BrowserGeneratedStationProgramTest {
    private fun resource(name: String): String = checkNotNull(javaClass.getResource("/timetable/$name")).readText()

    @Test fun `frontend generated source compiles through Graal and restores complete station contracts`() {
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

        // PROGRAM is the first duty element. Acquisition and task entry precede the generated
        // default-trip activation, which must precede display updates for that trip.
        assertEquals(TimetableStep.Check("a@1"), service.steps[0])
        assertEquals(TimetableAction.TaskStart("display"), (service.steps[1] as TimetableStep.At).action)
        val trip = worker.operationalTrips.single()
        assertEquals("default-run/2:D1/2:R1", trip.id)
        assertTrue(trip.defaults.isEmpty())
        assertEquals(SavedTimetableOperationalTripSegmentV1("D1", "R1", emptyList(), true), trip.segments.single())
        val restoredTrip = restored.operationalTrips.single()
        assertEquals(trip.id, restoredTrip.id)
        assertTrue(restoredTrip.defaults.isEmpty())
        assertEquals("D1", restoredTrip.segments.single().serviceId)
        assertEquals("R1", restoredTrip.segments.single().runId)
        assertTrue(restoredTrip.segments.single().unitSlots.isEmpty())
        assertTrue(restoredTrip.segments.single().unassignedUnits)
        val activationStep = service.steps[2] as TimetableStep.At
        assertEquals(TimetableAction.BeginOperationalTrip(trip.id, "R1", emptyList()), activationStep.action)
        val displayStep = service.steps[3] as TimetableStep.At
        val display = displayStep.action as TimetableAction.SetDisplay
        assertEquals(displayStep.tick, activationStep.tick)
        assertTrue(display.unitSlots.isEmpty())
        assertEquals(JsonPrimitive("C"), display.values["destination"])
        val displayOrigin = "duty:D1/element:display/call:display/program:display/step:display-info"
        val activationTrace = worker.compilationTrace.single { it.serviceId == "D1" && it.stepIndex == 2 }
        assertEquals("$displayOrigin/operational-trip:${trip.id}", activationTrace.origin)
        val displayTrace = worker.compilationTrace.single { it.serviceId == "D1" && it.stepIndex == 3 }
        assertEquals(displayOrigin, displayTrace.origin)
        assertEquals(SavedTimetableActionTypeV1.BEGIN_OPERATIONAL_TRIP, worker.services.single().steps[activationTrace.stepIndex].action!!.type)
        assertEquals(SavedTimetableActionTypeV1.SET_DISPLAY, worker.services.single().steps[displayTrace.stepIndex].action!!.type)

        assertEquals(TimetableAction.TaskEnd("display"), (service.steps[4] as TimetableStep.At).action)
        val originStep = service.steps[5] as TimetableStep.At
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
            assertEquals(20L, program.preparationLead.evaluate())
            assertEquals(listOf(StationProgramAction.OpenDoors, StationProgramAction.End), program.arrival)
            assertEquals(listOf(StationProgramAction.CloseDoors, StationProgramAction.End), program.departurePreparation)
        }
        assertEquals(20L, document.getValue("minimumDwellTicks").jsonPrimitive.long)
        assertEquals(listOf(0L, 20L, 0L), programs.map { it.minimumDwellTicks },
            "global minimum applies only to the intermediate STOP, including the nested runtime program")
        val options = service.steps.filterIsInstance<TimetableStep.At>().mapNotNull {
            it.action as? TimetableAction.DestinationOptions
        }
        assertEquals(listOf(20L, 0L), options.map { it.minimumDwellTicks },
            "explicit endpoint zero must not revive a station default through nullable fallback")

        val segment = service.runSegments.single()
        assertEquals("R1", segment.runId)
        assertEquals(5, segment.startStepIndex)
        assertEquals(segment.startStepIndex, worker.services.single().runSegments.single().startStepIndex)
        assertEquals("duty:D1/element:run/run:R1", worker.compilationTrace.single {
            it.serviceId == "D1" && it.stepIndex == segment.startStepIndex
        }.origin)
        assertEquals(service.steps.size, segment.endStepIndexExclusive)
        assertEquals(listOf("junction"), segment.calls[1].viaIds)
        val via = service.steps.filterIsInstance<TimetableStep.At>().map { it.action }.filterIsInstance<TimetableAction.GoVia>().single()
        assertEquals(listOf("junction","b@1"), via.wayPointIds)
        assertEquals(document.getValue("runs").jsonArray.single().jsonObject.getValue("calls").jsonArray.size, segment.calls.size)
    }
}
