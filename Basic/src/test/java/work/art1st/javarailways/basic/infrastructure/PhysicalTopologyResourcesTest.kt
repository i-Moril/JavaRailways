package work.art1st.javarailways.basic.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
import work.art1st.javarailways.core.api.physics.rail.topology.RailCrossingPoint
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
import java.util.UUID

class PhysicalTopologyResourcesTest {
    @Test
    fun `one topology crossing becomes one deterministic two-anchor diamond resource`() {
        val first = UUID(0L, 1L)
        val second = UUID(0L, 2L)
        val crossing = AtGradeRailCrossing(
            RailCrossingPoint(first, 4.0),
            RailCrossingPoint(second, 7.0)
        )
        val topology = buildTestTopology(
            revision = 3L,
            rails = listOf(TestTopologyRail(first, 10.0), TestTopologyRail(second, 12.0)),
            continuations = emptyMap(),
            atGradeCrossings = listOf(crossing)
        )

        val binding = PhysicalTopologyResources.routeBoundBindings(topology).single()

        assertEquals(PhysicalTopologyResources.DIAMOND_TYPE_ID, binding.resource.typeId)
        assertTrue(binding.resource.resourceId.startsWith("$first/$second/"))
        assertEquals(setOf(first, second), binding.anchors.map { it.railId }.toSet())
        assertEquals(
            4.0 - PhysicalTopologyResources.DIAMOND_ANCHOR_HALF_LENGTH_METERS,
            binding.anchors.single { it.railId == first }.startAbMeters
        )
        assertEquals(
            4.0 + PhysicalTopologyResources.DIAMOND_ANCHOR_HALF_LENGTH_METERS,
            binding.anchors.single { it.railId == first }.endAbMeters
        )
        assertEquals(setOf(binding.resource), PhysicalTopologyResources.controlledResources(topology, listOf(binding)))
    }

    @Test
    fun `crossing at a rail endpoint still receives a positive anchor`() {
        val first = UUID(0L, 11L)
        val second = UUID(0L, 12L)
        val topology = buildTestTopology(
            revision = 4L,
            rails = listOf(TestTopologyRail(first, 1.0), TestTopologyRail(second, 1.0)),
            continuations = emptyMap(),
            atGradeCrossings = listOf(
                AtGradeRailCrossing(RailCrossingPoint(first, 0.0), RailCrossingPoint(second, 1.0))
            )
        )

        val anchors = PhysicalTopologyResources.routeBoundBindings(topology).single().anchors

        assertTrue(anchors.all { it.startAbMeters < it.endAbMeters })
        assertEquals(0.0, anchors.single { it.railId == first }.startAbMeters)
        assertEquals(1.0, anchors.single { it.railId == second }.endAbMeters)
    }
}
