package work.art1st.javarailways.basic.timetable.simulation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.depot.DepotId
import work.art1st.javarailways.basic.api.depot.DepotTrainSelector
import work.art1st.javarailways.basic.timetable.Timetable
import work.art1st.javarailways.basic.timetable.TimetableAction
import work.art1st.javarailways.basic.timetable.TimetableService
import work.art1st.javarailways.basic.timetable.TimetableStep
import work.art1st.javarailways.basic.timetable.TrainSelector

class TimetableSimulationTest {
    @Test
    fun `requires one user-selected profile for every distinct selector`() {
        val timetable = Timetable("t", services = listOf(
            service("fixed", TrainSelector.TrainId("s100-1")),
            TimetableService("depot", steps = listOf(
                TimetableStep.At(0, TimetableAction.TakeFromDepot(
                    DepotTrainSelector(DepotId("north"), requiredTags = setOf("metro"))
                )),
                TimetableStep.At(10, TimetableAction.GoTo("b"))
            ))
        ))
        val report = TimetableSimulationEngine(TimetableTravelTimeEstimator { 20 }).simulate(
            TimetableSimulationRequest(timetable, emptyMap())
        )
        assertFalse(report.isSuccess)
        assertEquals(setOf("name:s100-1", "depot:north;tags=metro"), report.selectorKeys.map { it.value }.toSet())
        assertEquals(2, report.issues.size)
    }

    @Test
    fun `propagates physical travel delay through a compiled service deterministically`() {
        val service = TimetableService(
            "duty-a", TrainSelector.TrainId("s100-1"), steps = listOf(
                TimetableStep.Check("a"),
                TimetableStep.At(100, TimetableAction.GoTo("b")),
                TimetableStep.At(150, TimetableAction.GoTo("c"))
            )
        )
        val requests = mutableListOf<TimetableMovementEstimateRequest>()
        val engine = TimetableSimulationEngine(TimetableTravelTimeEstimator { request ->
            requests.add(request)
            80
        })
        val input = TimetableSimulationRequest(
            Timetable("t", services = listOf(service)),
            mapOf(TimetableSimulationSelectorKey("name:s100-1") to TimetableSimulationProfileId("template:s100"))
        )
        val first = engine.simulate(input)
        val second = engine.simulate(input)

        assertTrue(first.isSuccess)
        assertEquals(first, second)
        assertEquals(30, first.maximumDelayTicks)
        assertEquals(
            listOf(100L, 180L, 180L, 260L),
            first.events.map { it.actualTick }
        )
        assertEquals(TimetableSimulationDelayCause.INSUFFICIENT_RUNNING_TIME, first.events[2].cause)
        assertEquals(listOf("a", "b"), requests.first().let { listOf(it.origin, it.targets.single()) })
    }

    @Test
    fun `uses operation estimates without changing logical tick duration`() {
        val service = TimetableService(
            "duty-a", TrainSelector.TrainId("s100-1"), steps = listOf(
                TimetableStep.Check("a"),
                TimetableStep.At(10, TimetableAction.Reverse),
                TimetableStep.At(20, TimetableAction.GoTo("b"))
            )
        )
        val engine = TimetableSimulationEngine(
            TimetableTravelTimeEstimator { 40 },
            TimetableOperationTimeEstimator { _, _, _, action -> if (action is TimetableAction.Reverse) 30 else 0 }
        )
        val report = engine.simulate(TimetableSimulationRequest(
            Timetable("t", services = listOf(service)),
            mapOf(TimetableSimulationSelectorKey("name:s100-1") to TimetableSimulationProfileId("world:s100-1"))
        ))
        assertEquals(listOf(10L, 40L, 80L), report.events.map { it.actualTick })
        assertEquals(20, report.maximumDelayTicks)
    }

    private fun service(id: String, selector: TrainSelector) = TimetableService(
        id, selector, listOf(TimetableStep.Check("a"), TimetableStep.At(10, TimetableAction.GoTo("b")))
    )
}
