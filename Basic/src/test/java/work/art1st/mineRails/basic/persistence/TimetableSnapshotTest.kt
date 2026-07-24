package work.art1st.mineRails.basic.persistence

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.mineRails.basic.api.depot.DepotId
import work.art1st.mineRails.basic.api.depot.DepotTrainSelector
import work.art1st.mineRails.basic.api.station.DoorSide
import work.art1st.mineRails.basic.api.line.LineDirection
import work.art1st.mineRails.basic.api.line.LineId
import work.art1st.mineRails.basic.api.timetable.*
import work.art1st.mineRails.basic.timetable.*

class TimetableSnapshotTest {
    @Test
    fun `round trips directional WebUI row metadata`() {
        val saved = SavedTimetableV1(
            "rows", 1, listOf(SavedTimetableServiceV1(
                id = "S1", selectorType = SavedTrainSelectorTypeV1.TRAIN_ID, trainId = "T1", steps = emptyList(),
                lineId = "metro", lineDirection = LineDirection.DOWN,
                rows = listOf(SavedServiceEditorRowV1(
                    id = "station-1", type = SavedServiceRowTypeV1.STATION,
                    status = SavedServiceStopStatusV1.STOP, stationId = "central", berthId = "two",
                    arrivalTick = 100, departureTick = 140,
                    actions = listOf(SavedTimetableActionV1(SavedTimetableActionTypeV1.STOP, stopTicks = 20))
                ))
            ))
        )
        val decoded = TimetableSnapshotCodec.decode(TimetableSnapshotCodec.encode(saved))
        assertEquals(saved, decoded)
        assertEquals(saved, SavedTimetableV1.capture(decoded.restore()))
    }

    @Test
    fun `round trips normalized timetable actions`() {
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
                                DepotId("north"),
                                requiredTags = setOf("metro"),
                                minimumCars = 2,
                                maximumCars = 8,
                                minimumSpeedMetersPerSecond = 12.0,
                                requiredCapabilities = setOf("doors")
                            )
                        )),
                        TimetableStep.At(50L, TimetableAction.TakeFromWaypoint(
                            listOf("platform@one", "siding")
                        )),
                        TimetableStep.At(100L, TimetableAction.GoVia(listOf("via", "central@one"))),
                        TimetableStep.At(200L, TimetableAction.CallStation(
                            "central", "one", 40L, 1.5, DoorSide.LEFT, arrivalTick = 180L
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
                    lineId = LineId("metro"),
                    lineDirection = LineDirection.DOWN
                )
            ),
            diagramLines = listOf(TimetableDiagramLine(
                "metro",
                upStationIds = listOf("central", "terminus"),
                downStationIds = listOf("terminus", "central")
            )),
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
