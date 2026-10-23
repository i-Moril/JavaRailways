package work.art1st.javarailways.simpleato.routing

import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.AfterEach
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import work.art1st.javarailways.basic.api.route.ExpandedRoute
import work.art1st.javarailways.basic.api.route.PlannedRoute
import work.art1st.javarailways.basic.api.route.RailViewRef
import work.art1st.javarailways.basic.api.route.RouteSegment
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.physics.train.onOwnerForTest
import work.art1st.javarailways.simpleato.api.mission.AtoMission
import work.art1st.javarailways.simpleato.api.mission.AtoMissionLeg
import work.art1st.javarailways.simpleato.api.mission.AtoMissionOrigin
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.simpleato.api.mission.AtoMissionPoint
import work.art1st.javarailways.simpleato.api.mission.AtoMissionRequest
import work.art1st.javarailways.simpleato.api.mission.AtoMissionResolutionResult
import work.art1st.javarailways.simpleato.api.mission.AtoMissionResolver
import work.art1st.javarailways.simpleato.api.mission.WayPointRef
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry

class MissionRouteProgressTest {
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
    fun `projects BA positions onto cumulative mission distance`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        data.wayPoints.add(WayPoint("A", "A", rail.id, 80.0))
        data.wayPoints.add(WayPoint("B", "B", rail.id, 20.0))
        val result = AtoMissionResolver(world).resolve(
            AtoMissionRequest(
                id = "ba",
                revision = 1L,
                origin = WayPointRef("A"),
                points = listOf(AtoMissionPoint.Stop(WayPointRef("B")))
            )
        ) as AtoMissionResolutionResult.Success
        val layout = MissionRouteLayout(world, result.mission)

        assertEquals(60.0, layout.totalLengthMeters, 1.0e-9)
        assertEquals(
            10.0,
            layout.project(RailPositionSnapshot(rail.id, ViewDirection.BA, 70.0))!!,
            1.0e-9
        )
        assertEquals(
            55.0,
            layout.project(RailPositionSnapshot(rail.id, ViewDirection.BA, 25.0), 10.0)!!,
            1.0e-9
        )
    }
    @ParameterizedTest
    @EnumSource(ViewDirection::class)
    fun `formation origin projects actual AB and BA anchors without a waypoint and rejects shifted starts`(direction: ViewDirection) {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        val train = createTrainWithConfig("physical-origin", rail, world, listOf(10.0))
        if (direction == ViewDirection.BA) world.onOwnerForTest { train.reverse() }
        val anchor = train.publishedFrame.head
        val origin = AtoMissionOrigin.FormationOutput("cycle-0/split", "first", anchor)
        val start = if (direction == ViewDirection.AB) anchor.arcLengthInAbMeters
            else rail.curve.totalArcLength - anchor.arcLengthInAbMeters
        fun mission(shift: Double): AtoMission = AtoMission("physical-origin", 1, origin,
            listOf(AtoMissionLeg(0, AtoMissionPoint.Stop(WayPointRef("target")),
                PlannedRoute("selected", 1, ExpandedRoute(origin.id, "target",
                    listOf(RouteSegment(RailViewRef(rail.id, direction), start + shift, start + shift + 5.0)))))))
        assertNull(data.wayPoints[origin.id])
        assertNull(data.wayPoints["target"])
        val layout = MissionRouteLayout(world, mission(0.0))
        assertEquals(5.0, layout.totalLengthMeters, 1e-9)
        assertEquals(0.0, requireNotNull(layout.project(anchor)), 1e-9)
        val progressed = anchor.copy(arcLengthInAbMeters = anchor.arcLengthInAbMeters +
            if (direction == ViewDirection.AB) 2.0 else -2.0)
        assertEquals(2.0, requireNotNull(layout.project(progressed)), 1e-9)
        assertThrows(IllegalArgumentException::class.java) { MissionRouteLayout(world, mission(0.25)) }
    }

}
