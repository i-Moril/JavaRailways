package work.art1st.mineRails.basic.persistence

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.mineRails.basic.api.depot.DepotId
import work.art1st.mineRails.basic.api.depot.DepotTrainSelector
import work.art1st.mineRails.basic.api.station.DoorSide
import work.art1st.mineRails.basic.api.line.LineId
import work.art1st.mineRails.basic.api.timetable.*
import work.art1st.mineRails.basic.timetable.*

class TimetableSnapshotTest {
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
            )
        )

        val encoded = TimetableSnapshotCodec.encode(SavedTimetableV1.capture(timetable))
        val restored = TimetableSnapshotCodec.decode(encoded).restore()

        assertEquals(timetable, restored)
    }
}
