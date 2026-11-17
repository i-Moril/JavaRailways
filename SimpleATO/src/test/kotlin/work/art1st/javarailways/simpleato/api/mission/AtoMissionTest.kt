package work.art1st.javarailways.simpleato.api.mission

import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.AfterEach
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.basic.basicWorldData

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.api.route.WayPoint
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments

class AtoMissionTest {
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
    fun `mission planning uses the SimpleATO operating direction store`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        data.wayPoints.add(WayPoint("A", rail.id, 80.0))
        data.wayPoints.add(WayPoint("B", rail.id, 20.0))
        val request = AtoMissionRequest(
            "direction-policy",
            1L,
            WayPointRef("A"),
            listOf(AtoMissionPoint.Stop(WayPointRef("B")))
        )

        world.basicWorldData.operatingDirections.put(rail.id, ViewDirection.AB)
        assertTrue(AtoMissionResolver(world).resolve(request) is AtoMissionResolutionResult.RouteUnavailable)

        world.basicWorldData.operatingDirections.put(rail.id, ViewDirection.BA)
        assertTrue(AtoMissionResolver(world).resolve(request) is AtoMissionResolutionResult.Success)
    }

    @Test
    fun `resolves immutable mission legs through stable waypoint references`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        data.wayPoints.add(WayPoint("A", rail.id, 10.0))
        data.wayPoints.add(WayPoint("B", rail.id, 40.0))
        data.wayPoints.add(WayPoint("C", rail.id, 80.0))
        val sourcePoints = mutableListOf<AtoMissionPoint>(
            AtoMissionPoint.Via(WayPointRef("B")),
            AtoMissionPoint.Stop(WayPointRef("C"), offsetMetersBefore = 1.5)
        )
        val request = AtoMissionRequest("service-1", 4L, WayPointRef("A"), sourcePoints)
        sourcePoints.clear()

        val result = AtoMissionResolver(world).resolve(request)

        assertTrue(result is AtoMissionResolutionResult.Success)
        val mission = (result as AtoMissionResolutionResult.Success).mission
        assertEquals(2, request.points.size)
        assertEquals(2, mission.legs.size)
        assertEquals(listOf(30.0, 40.0), mission.legs.map { it.plannedRoute.expandedRoute.totalLength })
        assertEquals(4L, mission.legs[1].plannedRoute.revision)
        assertEquals(WayPointRef("C"), mission.legs[1].point.target)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (mission.legs as MutableList<AtoMissionLeg>).clear()
        }
    }

    @Test
    fun `real formation origin reuses route planning without registering a waypoint`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        data.wayPoints.add(WayPoint("end", rail.id, 80.0))
        val origin = AtoMissionOrigin.FormationOutput("join-instance", "joined",
            work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot(rail.id, ViewDirection.AB, 37.0))
        val request = AtoMissionRequest("actual-output", 9L, WayPointRef(origin.id),
            listOf(AtoMissionPoint.Stop(WayPointRef("end"))), requiredStartDirection = ViewDirection.AB)
        val result = AtoMissionResolver(world).resolveFromPhysicalOrigin(request, origin)
        assertTrue(result is AtoMissionResolutionResult.Success)
        val mission = (result as AtoMissionResolutionResult.Success).mission
        assertEquals(origin, mission.origin)
        assertEquals(43.0, mission.legs.single().plannedRoute.expandedRoute.totalLength, 1e-10)
        assertEquals(listOf("end"), data.wayPoints.all().map { it.id })
        world.basicWorldData.operatingDirections.put(rail.id, ViewDirection.BA)
        assertTrue(AtoMissionResolver(world).resolveFromPhysicalOrigin(request, origin) is AtoMissionResolutionResult.RouteUnavailable)
        assertEquals(AtoMissionResolutionResult.TargetUnresolved(WayPointRef("missing")),
            AtoMissionResolver(world).resolveFromPhysicalOrigin(AtoMissionRequest("actual-output", 10L, WayPointRef(origin.id), listOf(AtoMissionPoint.Stop(WayPointRef("missing")))), origin))
    }

    @Test
    fun `reports the first unresolved mission target`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        data.wayPoints.add(WayPoint("A", rail.id, 10.0))
        val request = AtoMissionRequest(
            "service-1",
            1L,
            WayPointRef("A"),
            listOf(AtoMissionPoint.Stop(WayPointRef("missing")))
        )

        val result = AtoMissionResolver(world).resolve(request)

        assertEquals(
            AtoMissionResolutionResult.TargetUnresolved(WayPointRef("missing")),
            result
        )
    }

    @Test
    fun `reports unavailable route before mission reaches the ATO`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val first = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(50.0, segmentCount = 1)
        )
        val second = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(50.0, segmentCount = 1)
        )
        data.wayPoints.add(WayPoint("A", first.id, 10.0))
        data.wayPoints.add(WayPoint("B", second.id, 30.0))
        val request = AtoMissionRequest(
            "service-1",
            1L,
            WayPointRef("A"),
            listOf(AtoMissionPoint.Stop(WayPointRef("B")))
        )

        val result = AtoMissionResolver(world).resolve(request)

        assertTrue(result is AtoMissionResolutionResult.RouteUnavailable)
        result as AtoMissionResolutionResult.RouteUnavailable
        assertEquals(WayPointRef("A"), result.from)
        assertEquals(WayPointRef("B"), result.to)
    }

    @Test
    fun `carries each leg end direction into the next leg`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        data.wayPoints.add(WayPoint("A", rail.id, 80.0))
        data.wayPoints.add(WayPoint("B", rail.id, 40.0))
        data.wayPoints.add(WayPoint("C", rail.id, 60.0))

        val result = AtoMissionResolver(world).resolve(
            AtoMissionRequest(
                id = "direction-continuity",
                revision = 1L,
                origin = WayPointRef("A"),
                points = listOf(
                    AtoMissionPoint.Via(WayPointRef("B")),
                    AtoMissionPoint.Stop(WayPointRef("C"))
                ),
                requiredStartDirection = ViewDirection.BA,
                requiredGoalDirections = listOf(ViewDirection.BA, ViewDirection.AB)
            )
        )

        assertTrue(result is AtoMissionResolutionResult.RouteUnavailable)
        result as AtoMissionResolutionResult.RouteUnavailable
        assertEquals(WayPointRef("B"), result.from)
        assertEquals(WayPointRef("C"), result.to)
    }
}
