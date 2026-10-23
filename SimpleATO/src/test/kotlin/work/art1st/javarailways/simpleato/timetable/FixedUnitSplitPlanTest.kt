package work.art1st.javarailways.simpleato.timetable

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.route.*
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.*
import work.art1st.javarailways.simpleato.api.depot.*

class FixedUnitSplitPlanTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `HEAD partition keeps whole unequal units and maps logical FIRST independently of retained train`(reversed: Boolean) {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, 1))
            val train = createTrainWithConfig("source", rail, world, List(6) { 10.0 })
            if (reversed) world.onOwnerForTest { train.reverse() }
            val frame = train.publishedFrame
            val cars = frame.cars.map { it.carId }
            val depot = DepotId("home")
            val store = DepotStore().also {
                it.addStablingPoint(depot, "Home", DepotStablingPoint(StablingPointRef(depot, "p"), "point"))
                listOf(cars.take(1), cars.drop(1).take(2), cars.drop(3)).forEachIndexed { index, group ->
                    it.registerFixedUnit(FixedEmuUnit("unit-$index", depot, group, "emu"), cars)
                }
            }
            val event = TimetableFormationEvent.Split("divide", "source-duty", 1,
                TimetableFormationOutput("first", FormationOutputDisposition.Duty("branch")),
                TimetableFormationOutput("rest", FormationOutputDisposition.Hold))
            val grant = RouteGrantSnapshot(RouteAdmissionTestFixtures.grantId(), InfrastructureOwner("test:source", 1),
                train.id, "route", 1, RailwayStateStamp("world", frame.physicsEpoch, world.topologyState.revision), RouteBoundary(100.0))
            fun plan(definition: TimetableFormationEvent.Split = event, assignments: Map<String, String> =
                mapOf("first" to "branch-instance", "rest" to "hold-instance")) = FixedUnitSplitPlan.prepare(
                definition, "timetable/cycle-7/divide", frame, "source-instance", grant, store, "child", assignments)
            val result = plan()
            val first = result.outputs.getValue("first")
            assertEquals(if (reversed) cars.drop(3) else cars.take(1), first.carIds)
            assertEquals(if (reversed) "child" else "source", first.trainId)
            assertEquals("branch-instance", first.assignmentId)
            assertEquals(reversed, first.reversed)
            assertEquals(cars, result.ledgerChange.outputs.flatMap { it.owner.carIds })
            assertEquals(FixedFormationDisposition.HOLD,
                result.ledgerChange.outputs.single { it.owner.assignmentId == "hold-instance" }.disposition)
            assertEquals(if (reversed) cars[3] else cars[1], result.intent.beforeCarId)
            assertThrows(IllegalArgumentException::class.java) { plan(event.copy(firstUnitCount = 3)) }
            assertThrows(IllegalArgumentException::class.java) { plan(assignments = mapOf("first" to "shared", "rest" to "shared")) }
            assertThrows(IllegalArgumentException::class.java) { plan(assignments = mapOf("first" to "branch-instance")) }
            for (firstReversed in listOf(false, true)) for (restReversed in listOf(false, true)) {
                val oriented = FixedUnitSplitPlan.prepare(event, "oriented", frame, "source-instance", grant, store,
                    "child", mapOf("first" to "branch-instance", "rest" to "hold-instance"),
                    mapOf("first" to firstReversed, "rest" to restReversed))
                assertEquals(first.carIds, oriented.outputs.getValue("first").carIds,
                    "output reorientation must not change the original HEAD partition")
                assertEquals(firstReversed, oriented.outputs.getValue("first").reversed)
                assertEquals(restReversed, oriented.outputs.getValue("rest").reversed)
                assertEquals(reversed, oriented.ledgerChange.sources.single().reversed)
                oriented.ledgerChange.outputs.forEach { output ->
                    val physicalIntent = listOf(oriented.intent.retained, oriented.intent.separated)
                        .single { it.trainId == output.owner.trainId }
                    assertEquals(output.owner.reversed, physicalIntent.reversed)
                }
            }
            assertTrue(store.unresolvedFormationChanges().isEmpty(), "planning must not claim or publish")
            assertEquals(1, world.allTrains.size)
            assertEquals(cars, train.cars.map { it.id })
        }
    }
}
