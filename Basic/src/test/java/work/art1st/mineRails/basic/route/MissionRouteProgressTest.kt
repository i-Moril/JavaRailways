package work.art1st.mineRails.basic.route

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.mineRails.basic.BasicWorldData
import work.art1st.mineRails.basic.WayPoint
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionRequest
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionResolutionResult
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionResolver
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionPoint
import work.art1st.mineRails.basic.api.ato.mission.WayPointRef
import work.art1st.mineRails.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.mineRails.core.api.physics.rail.RailPositionSnapshot
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import work.art1st.mineRails.core.physics.train.createStraightRailFromSegments
import work.art1st.mineRails.core.physics.train.createTestWorld
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator

class MissionRouteProgressTest {
    @Test
    fun `projects BA positions onto cumulative mission distance`() {
        val world = createTestWorld()
        val data = BasicWorldData()
        TestBasicWorldDataRegistry.register(world, data)
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
