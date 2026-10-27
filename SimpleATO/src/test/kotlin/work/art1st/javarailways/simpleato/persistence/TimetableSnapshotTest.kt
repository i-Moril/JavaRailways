package work.art1st.javarailways.simpleato.persistence

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.api.depot.DepotId
import work.art1st.javarailways.simpleato.api.depot.DepotTrainSelector
import work.art1st.javarailways.simpleato.api.line.LineId
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.simpleato.api.timetable.*
import work.art1st.javarailways.simpleato.timetable.*

class TimetableSnapshotTest {
    @Test
    fun `presentation metadata survives JSON but is not restored into runtime`() {
        val saved = SavedTimetableV1(
            "presentation", 1, listOf(SavedTimetableServiceV1(
                id = "S1", selectorType = SavedTrainSelectorTypeV1.TRAIN_ID, trainId = "T1",
                steps = listOf(SavedTimetableStepV1(
                    SavedTimetableStepTypeV1.AT, tick = 20,
                    action = SavedTimetableActionV1(
                        SavedTimetableActionTypeV1.STATION_DESTINATION_OPTIONS,
                        stationId = "central", berthId = "two", arrivalTick = 100
                    )
                )),
                diagramLineId = "metro", lineDirection = SavedDiagramDirectionV1.DOWN
            )),
            diagramLines = listOf(SavedDiagramLineV1("metro", listOf("central"), listOf("central"))),
            compilationTrace = listOf(SavedCompilationTraceV1("S1", 0, "duty:S1/run:up-01"))
        )

        val decoded = TimetableSnapshotCodec.decode(TimetableSnapshotCodec.encode(saved))
        assertEquals(saved, decoded)

        val recaptured = SavedTimetableV1.capture(decoded.restore())
        assertNull(recaptured.services.single().diagramLineId)
        assertNull(recaptured.services.single().steps.single().action?.arrivalTick)
        assertEquals(emptyList<SavedDiagramLineV1>(), recaptured.diagramLines)
        assertEquals(emptyList<SavedCompilationTraceV1>(), recaptured.compilationTrace)
    }

    @Test
    fun `lineId is restored as infrastructure identity instead of diagram metadata`() {
        val restored = TimetableSnapshotCodec.decode("""
            {"id":"old","periodDays":1,"services":[{
              "id":"S1","selectorType":"TRAIN_ID","trainId":"T1","steps":[],"lineId":"metro"
            }]}
        """.trimIndent()).restore()

        assertEquals(LineId("metro"), restored.services.single().lineId)
    }

    @Test
    fun `round trips runtime timetable actions`() {
        val timetable = Timetable(
            id = "weekday",
            periodDays = 2L,
            services = listOf(
                TimetableService(
                    id = "S1",
                    trainSelector = TrainSelector.TrainId("T1"),
                    steps = listOf(
                        TimetableStep.Check("origin"),
                        TimetableStep.At(0L, TimetableAction.TakeFromDepot(
                            DepotTrainSelector(
                                DepotId("north"), requiredTags = setOf("metro"), minimumCars = 2,
                                maximumCars = 8, minimumSpeedMetersPerSecond = 12.0,
                                requiredCapabilities = setOf("doors")
                            )
                        )),
                        TimetableStep.At(50L, TimetableAction.TakeFromWaypoint(
                            listOf("platform@one", "siding")
                        )),
                        TimetableStep.At(75L, TimetableAction.WaitUntil),
                        TimetableStep.At(100L, TimetableAction.GoVia(listOf("via", "central@one"))),
                        TimetableStep.At(200L, TimetableAction.CallStation(
                            "central", "one", 40L, 1.5, DoorSide.LEFT
                        )),
                        TimetableStep.At(300L, TimetableAction.Stop(20L)),
                        TimetableStep.At(350L, TimetableAction.Conditional(
                            condition = TimetableCondition.Predicate(TimetableExtensionCall(
                                "example:model",
                                parameters = JsonObject(mapOf("id" to JsonPrimitive("s100")))
                            )),
                            thenSteps = listOf(TimetableStep.At(350L, TimetableAction.Extension(
                                TimetableExtensionCall("example:prepare")
                            )))
                        )),
                        TimetableStep.At(400L, TimetableAction.StoreAtDepot(
                            DepotId("north"), setOf("road-1")
                        ))
                    ),
                    activation = TimetableServiceActivation.Conditional(
                        TimetableCondition.Predicate(TimetableExtensionCall("example:demand")),
                        TimetableActivationFalseBehavior.SKIP_CYCLE
                    ),
                    lineId = LineId("metro")
                )
            ),
            lineSpeedLimits = listOf(TimetableLineSpeedLimit(LineId("metro"), 22.0)),
            initialPositions = listOf(TimetableInitialPosition(
                TimetableObjectSelector.And(listOf(
                    TimetableObjectSelector.Regex("T.*"),
                    TimetableObjectSelector.Not(TimetableObjectSelector.Regex(".*-disabled"))
                )),
                TimetableObjectSelector.Or(listOf(
                    TimetableObjectSelector.Regex("north@.*"),
                    TimetableObjectSelector.Regex("south@.*")
                ))
            ))
        )

        val encoded = TimetableSnapshotCodec.encode(SavedTimetableV1.capture(timetable))
        val restored = TimetableSnapshotCodec.decode(encoded).restore()

        assertEquals(timetable, restored)
    }

    @Test fun `timed conditional snapshot preserves child tick and rejects a check child`() {
        val condition = SavedTimetableConditionV1(SavedTimetableConditionTypeV1.PREDICATE,
            call = SavedTimetableExtensionCallV1("test:choose"))
        val saved = SavedTimetableActionV1(SavedTimetableActionTypeV1.CONDITIONAL, condition = condition,
            thenSteps = listOf(SavedTimetableStepV1(SavedTimetableStepTypeV1.AT, tick = 23,
                action = SavedTimetableActionV1(SavedTimetableActionTypeV1.EXTENSION,
                    extensionCall = SavedTimetableExtensionCallV1("test:action")))))
        val restored = saved.restore() as TimetableAction.Conditional
        assertEquals(23L, restored.thenSteps.single().tick)
        assertEquals(saved, SavedTimetableActionV1.capture(restored))
        assertThrows(IllegalArgumentException::class.java) {
            saved.copy(thenSteps = listOf(SavedTimetableStepV1(SavedTimetableStepTypeV1.CHECK, wayPointId = "hidden"))).restore()
        }
    }


    @Test fun `selected stop snapshot preserves finite candidates policy and actual station options`() {
        val action = TimetableAction.SelectStop(work.art1st.javarailways.simpleato.api.station.StationStopSelection(
            work.art1st.javarailways.simpleato.api.station.StationId("central"), listOf(
                work.art1st.javarailways.simpleato.api.station.StationStopCandidate("long", 0),
                work.art1st.javarailways.simpleato.api.station.StationStopCandidate("short", 1)),
            work.art1st.javarailways.simpleato.api.station.StationStopSelectionPolicy.AVAILABLE_PRIORITY),
            minimumDwellTicks = 30, stoppingOffsetMetersBefore = 1.5, doorSideOverride = DoorSide.RIGHT)
        val saved = SavedTimetableActionV1.capture(action)
        val encoded = kotlinx.serialization.json.Json.encodeToString(SavedTimetableActionV1.serializer(), saved)
        val decoded = kotlinx.serialization.json.Json.decodeFromString(SavedTimetableActionV1.serializer(), encoded)
        assertEquals(action, decoded.restore())
        assertEquals(SavedTimetableActionTypeV1.SELECT_STOP, decoded.type)
        assertEquals(listOf("long", "short"), decoded.berthCandidates.map { it.berthId })
        assertThrows(IllegalArgumentException::class.java) { saved.copy(berthCandidates = emptyList()).restore() }
        assertThrows(IllegalArgumentException::class.java) { saved.copy(selectionPolicy = null).restore() }
    }

}
