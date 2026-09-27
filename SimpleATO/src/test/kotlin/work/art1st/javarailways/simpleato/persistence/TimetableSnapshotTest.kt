package work.art1st.javarailways.simpleato.persistence

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.depot.DepotId
import work.art1st.javarailways.basic.api.depot.DepotTrainSelector
import work.art1st.javarailways.simpleato.api.line.LineId
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.simpleato.api.timetable.*
import work.art1st.javarailways.simpleato.timetable.*

class TimetableSnapshotTest {
    @Test
    fun `presentation metadata survives JSON but is not restored into runtime`() {
        val saved = SavedTimetableV1(
            "rows", 1, listOf(SavedTimetableServiceV1(
                id = "S1", selectorType = SavedTrainSelectorTypeV1.TRAIN_ID, trainId = "T1",
                steps = listOf(SavedTimetableStepV1(
                    SavedTimetableStepTypeV1.AT, tick = 20,
                    action = SavedTimetableActionV1(
                        SavedTimetableActionTypeV1.STATION_DESTINATION_OPTIONS,
                        stationId = "central", berthId = "two", arrivalTick = 100
                    )
                )),
                diagramLineId = "metro", lineDirection = SavedDiagramDirectionV1.DOWN,
                rows = listOf(SavedServiceEditorRowV1(
                    id = "station-1", type = SavedServiceRowTypeV1.STATION,
                    status = SavedServiceStopStatusV1.STOP, stationId = "central", berthId = "two",
                    arrivalTick = 100, departureTick = 140
                ))
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
        assertNull(recaptured.services.single().rows?.single()?.arrivalTick)
        assertEquals(140, recaptured.services.single().rows?.single()?.departureTick)
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
                            thenActions = listOf(TimetableAction.Extension(
                                TimetableExtensionCall("example:prepare")
                            ))
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
}
