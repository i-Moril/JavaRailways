package work.art1st.mineRails.basic.api.ato.mission

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.mineRails.basic.BasicWorldData
import work.art1st.mineRails.basic.WayPoint
import work.art1st.mineRails.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.mineRails.core.physics.train.createStraightRailFromSegments
import work.art1st.mineRails.core.physics.train.createTestWorld

class AtoMissionTest {
    @Test
    fun `resolves immutable mission legs through stable waypoint references`() {
        val world = createTestWorld()
        val data = BasicWorldData()
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        data.wayPoints.add(WayPoint("A", "A", rail.id, 10.0))
        data.wayPoints.add(WayPoint("B", "B", rail.id, 40.0))
        data.wayPoints.add(WayPoint("C", "C", rail.id, 80.0))
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
    fun `reports the first unresolved mission target`() {
        val world = createTestWorld()
        val data = BasicWorldData()
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        data.wayPoints.add(WayPoint("A", "A", rail.id, 10.0))
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
        val world = createTestWorld()
        val data = BasicWorldData()
        TestBasicWorldDataRegistry.register(world, data)
        val first = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(50.0, segmentCount = 1)
        )
        val second = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(50.0, segmentCount = 1)
        )
        data.wayPoints.add(WayPoint("A", "A", first.id, 10.0))
        data.wayPoints.add(WayPoint("B", "B", second.id, 30.0))
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
}
