package work.art1st.javarailways.simpleato.routing

import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.AfterEach
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
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
}
