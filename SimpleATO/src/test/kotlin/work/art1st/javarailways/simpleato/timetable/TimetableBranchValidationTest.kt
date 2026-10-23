package work.art1st.javarailways.simpleato.timetable

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.simpleato.api.timetable.*
import work.art1st.javarailways.simpleato.api.station.*
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationIds
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simpleato.api.destination.OperationalDestination
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationService
import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry

class TimetableBranchValidationTest {
    private fun at(action: TimetableAction) = TimetableStep.At(0, action)
    private fun branch(yes: List<TimetableStep.At>, no: List<TimetableStep.At>) = at(TimetableAction.Conditional(
        TimetableCondition.Predicate(TimetableExtensionCall("test:choice")), yes, no))
    private fun plan(vararg steps: TimetableStep) = Timetable("branches", services = listOf(
        TimetableService("duty", TrainSelector.TrainId("train"), listOf(TimetableStep.Check("A")) + steps)))

    @Test fun `different branch endpoints retain both actual onward origins and typed addresses`() = fixture { world ->
        val result = TimetableValidator(world).validate(plan(branch(listOf(at(TimetableAction.GoTo("B"))),
            listOf(at(TimetableAction.GoTo("C")))), at(TimetableAction.GoTo("D"))))
        assertTrue(result.isSuccess, result.errors.toString())
        assertEquals(setOf("B", "C"), result.retainedRoutes.keys.filter { it.stepIndex == 2 }.map { it.originId }.toSet())
        assertEquals(setOf(TimetableBranchArm.THEN, TimetableBranchArm.ELSE), result.retainedRoutes.keys
            .filter { it.stepIndex == 1 }.map { it.address.branchPath.single().arm }.toSet())
    }

    @Test fun `pending stopping options remain distinct across a shared movement`() = fixture { world ->
        val result = TimetableValidator(world).validate(plan(branch(
            listOf(at(TimetableAction.DestinationOptions("B", stoppingOffsetMetersBefore = 1.0))),
            listOf(at(TimetableAction.DestinationOptions("B", stoppingOffsetMetersBefore = 3.0)))), at(TimetableAction.GoTo("B"))))
        assertTrue(result.isSuccess, result.errors.toString())
        assertEquals(setOf(1.0, 3.0), result.retainedRoutes.keys.map { it.stoppingOffsetMeters }.toSet())
        assertEquals(setOf(1.0, 3.0), result.retainedRoutes.values.map { it.stoppingOffsetMeters }.toSet())
    }

    @Test fun `one invalid reversed successor rejects the whole branch program`() = fixture { world ->
        val result = TimetableValidator(world).validate(plan(branch(
            listOf(at(TimetableAction.GoTo("B")), at(TimetableAction.Reverse)),
            listOf(at(TimetableAction.GoTo("C")))), at(TimetableAction.GoTo("D"))))
        assertFalse(result.isSuccess, "the valid ELSE route must not mask the reversed THEN state")
    }

    private fun selection(vararg berths: String) = TimetableAction.SelectStop(StationStopSelection(
        StationId("central"), berths.mapIndexed { index, berth -> StationStopCandidate(berth, index) },
        StationStopSelectionPolicy.AVAILABLE_PRIORITY))

    @Test fun `selected stop excludes incoming-valid candidate whose onward movement is impossible`() = fixture { world ->
        val result = TimetableValidator(world).validate(plan(at(selection("late", "early")), at(TimetableAction.GoTo("D"))))
        assertTrue(result.isSuccess, result.errors.toString())
        val early = OperationalDestinationIds.stationBerth(StationId("central"), "early")
        assertEquals(setOf(early), result.retainedRoutes.keys.filter { it.stepIndex == 1 }.map { it.targetId }.toSet())
        assertEquals(setOf(early), result.retainedRoutes.keys.filter { it.stepIndex == 2 }.map { it.originId }.toSet())
    }

    @Test fun `selected stop rejects all candidates when no complete onward continuation exists`() = fixture { world ->
        val result = TimetableValidator(world).validate(plan(at(selection("late")), at(TimetableAction.GoTo("D"))))
        assertFalse(result.isSuccess)
        assertTrue(result.errors.any { it.contains("no eligible incoming and onward candidate") }, result.errors.toString())
        assertTrue(result.retainedRoutes.isEmpty(), "rejected candidate must publish no partial incoming proof")
    }

    @Test fun `selected stop requires both future conditional arms and supports addressed selection`() = fixture { world ->
        val result = TimetableValidator(world).validate(plan(branch(listOf(at(selection("early"))),
            listOf(at(selection("early")))), branch(listOf(at(TimetableAction.GoTo("D"))),
            listOf(at(TimetableAction.Reverse), at(TimetableAction.GoTo("D"))))))
        assertFalse(result.isSuccess)
        assertTrue(result.retainedRoutes.isEmpty(), "one valid future arm cannot authorize selection")
    }

    @Test fun `equivalent empty branch continuations reuse suffix evidence without exponential route copies`() = fixture { world ->
        val steps = List(20) { branch(emptyList(), emptyList()) } + at(TimetableAction.GoTo("D"))
        val result = TimetableValidator(world).validate(plan(*steps.toTypedArray()))
        assertTrue(result.isSuccess, result.errors.toString())
        assertEquals(1, result.retainedRoutes.size)
        assertEquals(1, result.routes.size)
    }

    @Test fun `proof budget charges each via leg before expensive route expansion`() = fixture { world ->
        val result = TimetableValidator(world).validate(plan(at(TimetableAction.GoVia(List(4097) { "B" }))))
        assertFalse(result.isSuccess)
        assertTrue(result.errors.any { it.contains("4096 work items") }, result.errors.toString())
        assertTrue(result.retainedRoutes.isEmpty())
    }

    @Test fun `rejected selection probes consume the same finite proof budget`() = fixture { world ->
        val missing = selection(*(0 until 64).map { "missing-$it" }.toTypedArray())
        val steps = List(4000) { at(TimetableAction.WaitUntil) } + at(missing)
        val result = TimetableValidator(world).validate(plan(*steps.toTypedArray()))
        assertFalse(result.isSuccess)
        assertTrue(result.errors.any { it.contains("4096 work items") }, result.errors.toString())
        assertTrue(result.retainedRoutes.isEmpty())
    }

    private fun fixture(body: (CoreWorld) -> Unit) {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            TestSimpleATOWorldDataRegistry.getOrCreate(world)
            try {
                val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, 1))
                listOf("A" to 20.0, "B" to 60.0, "C" to 80.0, "D" to 120.0).forEach { (id, arc) ->
                    world.basicWorldData.wayPoints.add(WayPoint(id, id, rail.id, arc)) }
                val station = StationId("central")
                listOf("early" to 70.0, "late" to 150.0).forEach { (id, arc) ->
                    OperationalDestinationService(world).register(OperationalDestination(
                        OperationalDestinationIds.stationBerth(station, id), id, rail.id, arc, ViewDirection.AB,
                        DestinationRole.StationBerthRole(station, berthId = id, doorSide = DoorSide.NONE,
                            usableLengthMeters = 40.0)))
                }
                world.simpleAtoWorldData.timetableExtensions.registerCondition(object : TimetableConditionProvider {
                    override val typeId = "test:choice"
                    override val version = 1
                    override val descriptor = TimetableExtensionDescriptor("Choice", "Branch validation")
                    override fun validate(parameters: JsonObject) = emptyList<String>()
                    override fun evaluate(parameters: JsonObject, context: TimetableRuntimeContext) = TimetableConditionResult.False
                })
                body(world)
            } finally { TestSimpleATOWorldDataRegistry.unregister(world) }
        }
    }
}
