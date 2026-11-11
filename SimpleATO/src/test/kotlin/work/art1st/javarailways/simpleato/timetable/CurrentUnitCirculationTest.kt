package work.art1st.javarailways.simpleato.timetable

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
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
                    val train = createTrainWithConfig("actual", rail, world, listOf(10.0))
                    val cars = train.cars.map { it.id }
                    depot.registerFixedUnit(FixedEmuUnit("unit", b, cars, "emu"), cars)
                    depot.registerRollingStock(RollingStockRecord(train.id, b, "emu", emptySet(), 1, 10.0, 20.0, emptySet()), StablingPointRef(a, "one"))
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
                    assertTrue(reducedDefinition.errors.any { "RECURRING_DEPOT_DEFICIT" in it })
                    val snapshot = depot.operationalSnapshot()
                    assertNotNull(depot.reservePhysicallySelected(train.id, StablingPointRef(a, "one"), DepotTrainSelector(a), "duty", cars))
                    assertEquals(RollingStockState.AVAILABLE, snapshot.stock.single().state)
                    assertTrue(snapshot.claims.isEmpty())
                    assertEquals(CirculationProof.UNPROVEN, CurrentUnitCirculation.analyze(world, timetable).inventory)
                    assertEquals(1, snapshot.units.size)
                    assertEquals(cars, snapshot.units.single().carIds)
                } finally { TestSimpleATOWorldDataRegistry.unregister(world) }
            }
        }
    }
}
