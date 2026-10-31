package work.art1st.javarailways.simpleato.timetable

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.*
import work.art1st.javarailways.simpleato.api.depot.*
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry

class StructuralOnwardRoutingTest {
    @Test fun `split output cannot skip a waypoint but explicit via retains the physical proof`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            TestSimpleATOWorldDataRegistry.getOrCreate(world)
            try {
                val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(220.0, 1))
                val train = createTrainWithConfig("physical", rail, world, List(4) { 10.0 }, connectorLength = 0.25)
                world.onOwnerForTest { train.setLocation(rail.viewAB.getRailLocationAt(80.0)); train.commit() }
                listOf("origin" to 80.0, "via" to 130.0, "end" to 200.0).forEach { (id, arc) ->
                    world.basicWorldData.wayPoints.add(WayPoint(id, rail.id, arc))
                }
                val home = DepotId("home")
                val depot = world.simpleAtoWorldData.depots
                depot.addStablingPoint(home, "Home", DepotStablingPoint(StablingPointRef(home, "point"), "origin"))
                val carIds = train.cars.map { it.id }
                carIds.chunked(2).forEachIndexed { index, ids ->
                    depot.registerFixedUnit(FixedEmuUnit("unit-$index", home, ids, "emu"), carIds)
                }
                fun validate(move: TimetableAction) = TimetableValidator(world).validate(Timetable("routing",
                    services = listOf(TimetableService("source", TrainSelector.TrainId("physical"), listOf(
                        TimetableStep.Check("origin"), TimetableStep.At(0, TimetableAction.Split("divide")),
                        TimetableStep.At(0, move)))),
                    formationEvents = TimetableFormationCatalog(listOf(TimetableFormationEvent.Split("divide", "source", 1,
                        TimetableFormationOutput("first", FormationOutputDisposition.Duty("source")),
                        TimetableFormationOutput("rest", FormationOutputDisposition.Hold))))))
                val skipped = validate(TimetableAction.GoTo("end"))
                assertFalse(skipped.isSuccess)
                assertTrue(skipped.errors.any { it.contains("no route") }, skipped.errors.toString())
                val explicit = validate(TimetableAction.GoVia(listOf("via", "end")))
                assertTrue(explicit.isSuccess, explicit.errors.toString())
                assertEquals(1, explicit.structural.routes.size)
            } finally { TestSimpleATOWorldDataRegistry.unregister(world) }
        }
    }
}
