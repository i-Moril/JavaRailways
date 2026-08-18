package work.art1st.javarailways.tunnelbridgebuilder.rail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.rail.RailAssemblyKind
import work.art1st.javarailways.basic.rail.RailAssemblyMetadata
import work.art1st.javarailways.basic.rail.RailAssemblyRole
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadata
import work.art1st.javarailways.basic.rail.resolveLogicalRailLaneIds
import java.util.UUID

class LogicalRailSegmentResolverTest {
    @Test
    fun `rail without assembly resolves to itself`() {
        val selected = UUID.randomUUID()

        assertEquals(
            listOf(selected),
            resolveLogicalRailLaneIds(selected, mapOf(selected to RailInfrastructureMetadata.EMPTY))
        )
    }

    @Test
    fun `assembly resolves every physical segment in the selected lane`() {
        val groupId = "logical-curve"
        val ids = List(6) { UUID.randomUUID() }
        val metadata = ids.withIndex().associate { (flatIndex, id) ->
            val lane = flatIndex / 3
            val segment = flatIndex % 3
            id to RailInfrastructureMetadata(
                assembly = RailAssemblyMetadata(
                    groupId = groupId,
                    kind = RailAssemblyKind.FIVE_SEGMENT_CURVE,
                    laneIndex = lane,
                    laneCount = 2,
                    segmentIndex = segment,
                    segmentCount = 3,
                    role = when (segment) {
                        0 -> RailAssemblyRole.LEADING_TANGENT
                        2 -> RailAssemblyRole.TRAILING_TANGENT
                        else -> RailAssemblyRole.CURVE
                    }
                )
            )
        }

        assertEquals(
            listOf(ids[3], ids[4], ids[5]),
            resolveLogicalRailLaneIds(ids[4], metadata)
        )
    }

    @Test
    fun `incomplete logical lane is rejected instead of partially sweeping`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val metadata = mapOf(
            first to RailInfrastructureMetadata(
                assembly = RailAssemblyMetadata(
                    groupId = "incomplete",
                    kind = RailAssemblyKind.FIVE_SEGMENT_CURVE,
                    laneIndex = 0,
                    laneCount = 1,
                    segmentIndex = 0,
                    segmentCount = 3,
                    role = RailAssemblyRole.LEADING_TANGENT
                )
            ),
            second to RailInfrastructureMetadata(
                assembly = RailAssemblyMetadata(
                    groupId = "incomplete",
                    kind = RailAssemblyKind.FIVE_SEGMENT_CURVE,
                    laneIndex = 0,
                    laneCount = 1,
                    segmentIndex = 1,
                    segmentCount = 3,
                    role = RailAssemblyRole.CURVE
                )
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            resolveLogicalRailLaneIds(first, metadata)
        }
    }
}
