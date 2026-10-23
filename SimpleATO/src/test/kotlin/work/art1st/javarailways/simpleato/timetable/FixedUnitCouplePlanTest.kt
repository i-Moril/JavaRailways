package work.art1st.javarailways.simpleato.timetable

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.route.*
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.train.*
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.*
import work.art1st.javarailways.simpleato.api.depot.*

class FixedUnitCouplePlanTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `host contact preserves whole units and binds authored end to operating direction`(stationaryReversed: Boolean) {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, 1))
            fun train(id: String, head: Double): CoreTrain = world.onOwnerForTest {
                val anchor = RailLocationWithFacing(rail.viewAB, head)
                val car = CoreCar.create("$id-car", CoreBogie.create(anchor), CoreBogie.create(anchor),
                    length = 10.0, bogieAOffset = 3.0, bogieBOffset = -3.0,
                    connectorLengthAtA = 0.3, connectorLengthAtB = 0.3, world = world)
                CoreTrain.create(id, listOf(car), world).also { it.setLocation(anchor); it.commitMaintenancePlacement() }
            }
            val moving = train("moving", 99.0)
            val stationary = train("stationary", 110.5)
            if (stationaryReversed) world.onOwnerForTest { stationary.reverse() }
            val proof = world.onOwnerForTest {
                assertInstanceOf(CouplingApproachInspection.Accepted::class.java,
                    world.inspectCouplingApproach(CouplingApproachRequest(moving.id, TrainEndSide.A,
                        stationary.id, TrainEndSide.B))).proof
            }
            val depot = DepotId("home")
            fun store(stationaryFamily: String = "emu") = DepotStore().also { store ->
                store.addStablingPoint(depot, "Home", DepotStablingPoint(StablingPointRef(depot, "p"), "point"))
                listOf(moving to "emu", stationary to stationaryFamily).forEach { (source, family) ->
                    val cars = source.cars.map { it.id }
                    store.registerFixedUnit(FixedEmuUnit("unit-${source.id}", depot, cars, family), cars)
                }
            }
            fun grant(train: CoreTrain) = RouteGrantSnapshot(RouteAdmissionTestFixtures.grantId(),
                InfrastructureOwner("test:${train.id}", 1), train.id, "route-${train.id}", 1,
                RailwayStateStamp("world", train.publishedFrame.physicsEpoch, world.topologyState.revision), RouteBoundary(100.0))
            val movingGrant = grant(moving)
            val stationaryGrant = grant(stationary)
            val end = if (stationaryReversed) FormationStationaryEnd.HEAD else FormationStationaryEnd.TAIL
            val event = TimetableFormationEvent.Couple("join", "moving-duty", "moving-duty", "stationary-duty",
                end, FormationRendezvous("approach", "meeting"), 0.4,
                TimetableFormationOutput("joined", FormationOutputDisposition.Duty("onward")))
            val depots = store()
            fun plan(definition: TimetableFormationEvent.Couple = event, selectedStore: DepotStore = depots) =
                FixedUnitCouplePlan.prepare(definition, "cycle-1/join", proof, "moving-owner", "stationary-owner",
                    movingGrant, stationaryGrant, "onward-owner", selectedStore)
            val result = plan()
            assertEquals(stationary.cars.map { it.id } + moving.cars.map { it.id }, result.output.carIds)
            assertEquals(moving.id, result.output.trainId)
            assertFalse(result.output.reversed)
            assertSame(proof, result.intent.proof)
            assertEquals(0.4, result.intent.maximumApproachSpeedMetersPerSecond)
            assertEquals(listOf("moving-owner", "stationary-owner"), result.ledgerChange.sources.map { it.assignmentId })
            assertThrows(IllegalArgumentException::class.java) { plan(event.copy(stationaryEnd =
                if (end == FormationStationaryEnd.HEAD) FormationStationaryEnd.TAIL else FormationStationaryEnd.HEAD)) }
            assertThrows(IllegalArgumentException::class.java) { plan(selectedStore = store("incompatible")) }
            assertTrue(depots.unresolvedFormationChanges().isEmpty())
            assertEquals(2, world.allTrains.size)
        }
    }
}
