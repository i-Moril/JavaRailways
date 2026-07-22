package work.art1st.mineRails.basic.api.line

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.mineRails.basic.api.destination.DestinationRole
import work.art1st.mineRails.basic.api.destination.OperationalDestination
import work.art1st.mineRails.basic.api.destination.OperationalDestinationService
import work.art1st.mineRails.basic.api.station.DoorSide
import work.art1st.mineRails.basic.api.station.StationId
import work.art1st.mineRails.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.mineRails.core.physics.train.createStraightRailFromSegments
import work.art1st.mineRails.core.physics.train.createTestWorld

class LinePlannerTest {
    private val worlds = mutableListOf<CoreWorld>()
    @AfterEach fun cleanup() = worlds.forEach(TestBasicWorldDataRegistry::unregister)

    @Test
    fun `chooses a directed berth route for each ordered station pair`() {
        val world = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(world)
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
            Line(LineId("L1"), "Line 1", listOf(StationId("a"), StationId("b")))
        )

        assertTrue(result is LineCompileResult.Success)
        result as LineCompileResult.Success
        assertEquals(60.0, result.totalLengthMeters, 1e-6)
        assertEquals("a@one", result.legs.single().fromDestinationId)
        assertEquals("b@one", result.legs.single().toDestinationId)
        assertTrue(LinePlanner(world).compile(
            Line(LineId("L2"), "Wrong direction", listOf(StationId("a"), StationId("wrong")))
        ) is LineCompileResult.Failure)
        val directional = Line(
            LineId("L3"), "Directional",
            listOf(StationId("a"), StationId("b")),
            listOf(StationId("a"), StationId("wrong"))
        )
        assertTrue(LinePlanner(world).compile(directional, LineDirection.UP) is LineCompileResult.Success)
        assertTrue(LinePlanner(world).compile(directional, LineDirection.DOWN) is LineCompileResult.Failure)
    }
}
