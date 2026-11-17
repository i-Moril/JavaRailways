package work.art1st.javarailways.simpleato.timetable

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.api.route.WayPoint
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.train.*
import work.art1st.javarailways.simpleato.api.depot.*
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry

class CurrentUnitCirculationTest {
    @Test fun `actual physical publication and atomic depot ledger feed analysis without phantom source assets`() {
        PhysicsCoordinator(1, automaticFallback = false).use { physics ->
            TestWorldFixture.create(physics).use { fixture ->
                val world = fixture.world
                TestSimpleATOWorldDataRegistry.register(world, BasicWorldData())
                try {
                    val a = DepotId("A"); val b = DepotId("B")
                    val depot = world.simpleAtoWorldData.depots
                    listOf(a, b).forEach { id -> depot.addStablingPoint(id, id.value,
                        DepotStablingPoint(StablingPointRef(id, "one"), "${id.value}-point")) }
                    val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, 1))
                    val train = createTrainWithConfig("actual", rail, world, listOf(10.0), composition = work.art1st.javarailways.simpleato.testing.testTrainComposition("unit" to listOf("car-0")))
                    val cars = train.cars.map { it.id }
                    depot.registerUnitControlCompatibility("unit", "emu")
                    TestSimpleATOWorldDataRegistry.setCandidateFacts(world) { captured ->
                        TrainCandidateFacts("emu", emptySet(), 20.0, emptySet()).takeIf { captured.id == train.id }
                    }
                    world.basicWorldData.wayPoints.add(WayPoint("A-point", rail.id, train.publishedFrame.head.arcLengthInAbMeters))
                    world.basicWorldData.wayPoints.add(WayPoint("B-point", rail.id, 120.0))
                    world.onOwnerForTest { world.publishOwnerChanges(listOf(train)) }
                    fun trip(id: String, from: DepotId, to: DepotId) = TimetableService(id, steps = listOf(
                        TimetableStep.At(0, TimetableAction.TakeFromDepot(DepotTrainSelector(from))),
                        TimetableStep.At(100, TimetableAction.StoreAtDepot(to))))
                    val timetable = Timetable("actual", services = listOf(trip("out", a, b), trip("back", b, a)))
                    assertEquals(CirculationProof.PROVEN, CurrentUnitCirculation.analyze(world, timetable).inventory)
                    val startedService = TimetableValidator(world).validateForServiceStart(timetable, "out")
                    assertEquals(work.art1st.javarailways.simpleato.api.timetable.TimetableCirculationStatus.PROVEN,
                        startedService.circulation?.inventory)
                    assertFalse(startedService.errors.any { "RECURRING_DEPOT_DEFICIT" in it })
                    val reducedDefinition = TimetableValidator(world).validate(timetable.copy(services = listOf(timetable.services.first())))
                    assertEquals(work.art1st.javarailways.simpleato.api.timetable.TimetableCirculationStatus.IMPOSSIBLE,
                        reducedDefinition.circulation?.inventory)
                    assertFalse(reducedDefinition.errors.any { "RECURRING_DEPOT_DEFICIT" in it },
                        "Ordinary acquisition keeps its existing admission rules while retaining the actual IMPOSSIBLE circulation report")
                    assertTrue(reducedDefinition.circulation!!.findings.none { it.blocking })
                    assertTrue(CurrentUnitCirculation.analyze(world, timetable.copy(services = listOf(timetable.services.first())))
                        .findings.any { it.blocking }, "Standalone analysis retains its genuine inventory result")
                    val structural = timetable.copy(formationEvents = TimetableFormationCatalog(listOf(
                        TimetableFormationEvent.Split("divide", "out", 1,
                            TimetableFormationOutput("first", FormationOutputDisposition.Duty("out")),
                            TimetableFormationOutput("rest", FormationOutputDisposition.Hold)))))
                    val malformedStructure = TimetableValidator(world).validate(structural)
                    assertTrue(malformedStructure.circulation!!.findings.any { it.blocking },
                        "A declared structural program still requires inventory admission evidence")
                    assertTrue(malformedStructure.errors.any { "circulation" in it }, malformedStructure.errors.toString())
                    val snapshot = depot.operationalSnapshot()
                    assertEquals(1, depot.claimFixedFormation(train.id, cars, "duty").size)
                    assertEquals(2, snapshot.points.size)
                    assertTrue(snapshot.claims.isEmpty())
                    assertEquals(CirculationProof.UNPROVEN, CurrentUnitCirculation.analyze(world, timetable).inventory)
                    assertEquals(1, snapshot.units.size)
                    assertEquals(cars, snapshot.units.single().carIds)
                } finally { TestSimpleATOWorldDataRegistry.unregister(world) }
            }
        }
    }
}
