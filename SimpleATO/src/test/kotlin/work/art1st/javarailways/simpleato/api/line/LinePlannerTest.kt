package work.art1st.javarailways.simpleato.api.line

import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.Assertions.assertAll
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.joml.Vector3d
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.RailView
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.simpleato.api.destination.OperationalDestination
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationService
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments

class LinePlannerTest {
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
    fun `strict Reset follows ordered incoming via points without adding station calls`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        val destinations = OperationalDestinationService(world)
        for ((id, position) in listOf("a" to 10.0, "b" to 90.0)) destinations.register(OperationalDestination(
            id = "$id@one", displayName = id, railId = rail.id, arcLengthInAbMeters = position,
            approachDirection = ViewDirection.AB,
            role = DestinationRole.StationBerthRole(StationId(id), id, "one", DoorSide.LEFT)))
        world.basicWorldData.wayPoints.add(work.art1st.javarailways.basic.WayPoint("via1", "Via", rail.id, 40.0))
        world.basicWorldData.wayPoints.add(work.art1st.javarailways.basic.WayPoint("via2", "Via", rail.id, 60.0))
        val line = Line(LineId("L"), "Line", listOf(StationId("a"), StationId("b")), listOf(StationId("b"), StationId("a")))
        val planner = LinePlanner(world)
        fun compile(via: List<String>) = planner.compileResetCandidates(line, line.upStationIds, listOf("a@one", "b@one"),
            listOf(ViewDirection.AB), listOf(emptyList(), via), requireUniquePath = true, pruneNonTargetWaypoints = true)
        assertThrows(IllegalArgumentException::class.java) { compile(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { compile(listOf("via2", "via1")) }
        val result = compile(listOf("via1", "via2")).single().compilation
        assertEquals(1, result.legs.size)
        assertEquals(80.0, result.totalLengthMeters, 1e-6)
        assertEquals("a@one", result.legs.single().fromDestinationId)
        assertEquals("b@one", result.legs.single().toDestinationId)
    }

    @Test
    fun `Reset candidates traverse fractional multi rail endpoints in automatic origin mode`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        fun straight(start: Double, length: Double) = PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(start, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0),
                Vector3d(0.0, 1.0, 0.0), 0.0),
            Curve.RawPoint(Vector3d(start + length, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0),
                Vector3d(0.0, 1.0, 0.0), length)
        ))
        val first = world.createRailForTest(straight(0.0, 12.3))
        val second = world.createRailForTest(straight(12.3, 45.6))
        IsolatedSimulationThreadMarker.run { RailView.connect(first.viewAB, second.viewBA) }
        val edge = world.topologyState.edges.values.single()
        // Use the published normalization so random fixture rail UUIDs cannot change this traversal.
        val origin = edge.railSlices.last()
        val goal = edge.railSlices.first()
        val originDirection = if (origin.endAbMeters > origin.startAbMeters) ViewDirection.BA else ViewDirection.AB
        val goalDirection = if (goal.endAbMeters > goal.startAbMeters) ViewDirection.BA else ViewDirection.AB
        val destinations = OperationalDestinationService(world)
        destinations.register(OperationalDestination(
            id = "origin@one", displayName = "Origin", railId = origin.railId,
            arcLengthInAbMeters = origin.endAbMeters, approachDirection = originDirection,
            role = DestinationRole.StationBerthRole(StationId("origin"), "Origin", "one", DoorSide.LEFT)
        ))
        destinations.register(OperationalDestination(
            id = "goal@one", displayName = "Goal", railId = goal.railId,
            arcLengthInAbMeters = goal.startAbMeters, approachDirection = goalDirection,
            role = DestinationRole.StationBerthRole(StationId("goal"), "Goal", "one", DoorSide.LEFT)
        ))
        val line = Line(LineId("fractional"), "Fractional",
            listOf(StationId("origin"), StationId("goal")),
            listOf(StationId("goal"), StationId("origin")))

        val candidates = LinePlanner(world).compileResetCandidates(
            line, line.upStationIds, listOf("origin@one", "goal@one")
        )

        assertEquals(listOf(originDirection), candidates.map { it.originDepartureDirection })
        val route = candidates.single().compilation.legs.single().route
        assertEquals(listOf(origin.railId, goal.railId), route.segments.map { it.railViewRef.railId })
        assertEquals(0.0, route.segments.first().startArcLength)
        assertEquals(57.9, route.totalLength, 1e-9)
        assertTrue(route.segments.all { it.startArcLength.isFinite() && it.startArcLength >= 0.0 &&
            it.endArcLength.isFinite() && it.endArcLength >= it.startArcLength })
    }

    @Test
    fun `chooses a directed berth route for each ordered station pair`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        val destinations = OperationalDestinationService(world)
        fun station(id:String, arc:Double, direction:ViewDirection = ViewDirection.AB) = OperationalDestination(
            id="$id@one", displayName=id, railId=rail.id, arcLengthInAbMeters=arc,
            approachDirection=direction,
            role=DestinationRole.StationBerthRole(StationId(id), id, "one", DoorSide.LEFT)
        )
        destinations.register(station("a", 20.0))
        destinations.register(station("b", 80.0))
        destinations.register(station("wrong", 80.0, ViewDirection.BA))

        val result = LinePlanner(world).compile(
            Line(
                LineId("L1"), "Line 1",
                listOf(StationId("a"), StationId("b")),
                listOf(StationId("b"), StationId("a"))
            )
        )

        assertTrue(result is LineCompileResult.Success)
        result as LineCompileResult.Success
        assertEquals(60.0, result.totalLengthMeters, 1e-6)
        assertEquals("a@one", result.legs.single().fromDestinationId)
        assertEquals("b@one", result.legs.single().toDestinationId)
        assertTrue(LinePlanner(world).compile(
            Line(
                LineId("L2"), "Wrong direction",
                listOf(StationId("a"), StationId("wrong")),
                listOf(StationId("wrong"), StationId("a"))
            )
        ) is LineCompileResult.Failure)
        val directional = Line(
            LineId("L3"), "Directional",
            listOf(StationId("a"), StationId("b")),
            listOf(StationId("a"), StationId("wrong"))
        )
        assertTrue(LinePlanner(world).compile(directional, LineDirection.UP) is LineCompileResult.Success)
        assertTrue(LinePlanner(world).compile(directional, LineDirection.DOWN) is LineCompileResult.Failure)
    }

    @Test
    fun `uses the exact selected berth sequence when requested`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(120.0, 1))
        val destinations = OperationalDestinationService(world)
        fun berth(station:String, berth:String, arc:Double, direction:ViewDirection = ViewDirection.AB) = OperationalDestination(
            id="$station@$berth", displayName="$station $berth", railId=rail.id, arcLengthInAbMeters=arc,
            approachDirection=direction,
            role=DestinationRole.StationBerthRole(StationId(station), station, berth, DoorSide.LEFT)
        )
        destinations.register(berth("a", "near", 20.0))
        destinations.register(berth("a", "selected", 10.0))
        destinations.register(berth("b", "near", 80.0))
        destinations.register(berth("b", "selected", 90.0))
        destinations.register(berth("a", "return", 10.0, ViewDirection.BA))
        val line = Line(
            LineId("L-selected"), "Selected berths",
            listOf(StationId("a"), StationId("b")),
            listOf(StationId("b"), StationId("a"))
        )

        val result = LinePlanner(world).compileStations(
            line,
            line.upStationIds,
            listOf("a@selected", "b@selected")
        )

        assertTrue(result is LineCompileResult.Success)
        result as LineCompileResult.Success
        assertEquals("a@selected", result.legs.single().fromDestinationId)
        assertEquals("b@selected", result.legs.single().toDestinationId)
        assertEquals(80.0, result.totalLengthMeters, 1e-6)
        assertTrue(LinePlanner(world).compileStations(
            line,
            line.upStationIds,
            listOf("a@near", "b@selected", "extra")
        ) is LineCompileResult.Failure)

        val turnbackLine = Line(
            LineId("L-turnback"), "Turnback",
            listOf(StationId("b"), StationId("a")),
            listOf(StationId("a"), StationId("b"))
        )
        val turnbackCandidates = LinePlanner(world).compileResetCandidates(
            turnbackLine,
            turnbackLine.upStationIds,
            listOf("b@selected", "a@return")
        )
        assertEquals(listOf(ViewDirection.BA), turnbackCandidates.map { it.originDepartureDirection })
        assertEquals(80.0, turnbackCandidates.single().compilation.totalLengthMeters, 1e-6)
    }
}
