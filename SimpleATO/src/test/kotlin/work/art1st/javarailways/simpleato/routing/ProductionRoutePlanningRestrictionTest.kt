package work.art1st.javarailways.simpleato.routing

import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.Assertions.assertAll
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.basic.api.route.planning.DirectedRailSpan
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictionSource
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictions
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.simpleato.api.destination.OperationalDestination
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationService
import work.art1st.javarailways.simpleato.api.line.Line
import work.art1st.javarailways.simpleato.api.line.LineCompileResult
import work.art1st.javarailways.simpleato.api.line.LineDirection
import work.art1st.javarailways.simpleato.api.line.LineId
import work.art1st.javarailways.simpleato.api.line.LinePlanner
import work.art1st.javarailways.simpleato.api.mission.AtoMissionPoint
import work.art1st.javarailways.simpleato.api.mission.AtoMissionRequest
import work.art1st.javarailways.simpleato.api.mission.AtoMissionResolutionResult
import work.art1st.javarailways.simpleato.api.mission.AtoMissionResolver
import work.art1st.javarailways.simpleato.api.mission.WayPointRef
import work.art1st.javarailways.simpleato.api.route.RoutePlanningRestrictionBinding
import work.art1st.javarailways.simpleato.api.station.DwellPlan
import work.art1st.javarailways.simpleato.api.station.StationCall
import work.art1st.javarailways.simpleato.api.station.StationCallRequest
import work.art1st.javarailways.simpleato.api.station.StationCallResolutionFailure
import work.art1st.javarailways.simpleato.api.station.StationCallResolutionResult
import work.art1st.javarailways.simpleato.api.station.StationCallResolver
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import work.art1st.javarailways.simpleato.timetable.Timetable
import work.art1st.javarailways.simpleato.timetable.TimetableAction
import work.art1st.javarailways.simpleato.timetable.TimetableService
import work.art1st.javarailways.simpleato.timetable.TimetableStep
import work.art1st.javarailways.simpleato.timetable.TimetableValidator
import work.art1st.javarailways.simpleato.timetable.TrainSelector

class ProductionRoutePlanningRestrictionTest {
    private val worldFixtures = mutableListOf<TestWorldFixture>()

    @AfterEach
    fun closeOwnedWorldFixtures() {
        val fixtures = worldFixtures.toList()
        worldFixtures.clear()
        assertAll(fixtures.flatMap { fixture ->
            listOf(
                Executable { TestSimpleATOWorldDataRegistry.unregister(fixture.world) },
                Executable { fixture.close() }
            )
        })
    }



    @Test
    fun `mission station line and timetable all obey the installed planning sources`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        val destinations = OperationalDestinationService(world)
        destinations.register(stationDestination("a", rail.id, 10.0))
        destinations.register(stationDestination("b", rail.id, 90.0))
        world.simpleAtoWorldData.routePlanningRestrictions.replaceSources(
            listOf(
                RoutePlanningRestrictionBinding(
                    "test:block-all-callers",
                    RoutePlanningRestrictionSource { topology ->
                        RoutePlanningRestrictions(
                            topology.revision,
                            forbiddenTraversals = ViewDirection.entries.map { direction ->
                                DirectedRailSpan(rail.id, direction, 0.0, 100.0)
                            }
                        )
                    }
                )
            )
        )

        val mission = AtoMissionResolver(world).resolve(
            AtoMissionRequest(
                id = "mission",
                revision = 1L,
                origin = WayPointRef("a@one"),
                points = listOf(AtoMissionPoint.Stop(WayPointRef("b@one")))
            )
        )
        assertTrue(mission is AtoMissionResolutionResult.RouteUnavailable)

        val stationCall = StationCallResolver(world).resolve(
            StationCallRequest(
                serviceRunId = "service",
                origin = WayPointRef("a@one"),
                call = StationCall(
                    callId = "call",
                    missionRevision = 1L,
                    stationId = StationId("b"),
                    berthRef = work.art1st.javarailways.simpleato.api.station.StationBerthRef(
                        StationId("b"),
                        "one"
                    ),
                    plannedArrivalTick = null,
                    dwellPlan = DwellPlan(0L)
                )
            )
        )
        assertEquals(
            StationCallResolutionFailure.ROUTE_UNAVAILABLE,
            (stationCall as StationCallResolutionResult.Failure).reason
        )

        val line = LinePlanner(world).compile(
            Line(
                LineId("line"),
                "Line",
                listOf(StationId("a"), StationId("b")),
                listOf(StationId("b"), StationId("a"))
            ),
            LineDirection.UP
        )
        assertEquals(
            "No directed route between a and b",
            (line as LineCompileResult.Failure).reason
        )

        val timetable = TimetableValidator(world).validate(
            Timetable(
                id = "timetable",
                services = listOf(
                    TimetableService(
                        id = "service",
                        trainSelector = TrainSelector.TrainId("train"),
                        steps = listOf(
                            TimetableStep.Check("a@one"),
                            TimetableStep.At(0L, TimetableAction.GoTo("b@one"))
                        )
                    )
                )
            )
        )
        assertTrue(timetable.errors.any { it.contains("经路不可达") })
    }

    private fun stationDestination(
        station: String,
        railId: java.util.UUID,
        arcLengthMeters: Double
    ) = OperationalDestination(
        id = "$station@one",
        displayName = station,
        railId = railId,
        arcLengthInAbMeters = arcLengthMeters,
        approachDirection = ViewDirection.AB,
        role = DestinationRole.StationBerthRole(
            stationId = StationId(station),
            stationDisplayName = station,
            berthId = "one",
            doorSide = DoorSide.LEFT
        )
    )
}
