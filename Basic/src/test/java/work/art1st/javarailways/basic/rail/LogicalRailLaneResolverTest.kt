package work.art1st.javarailways.basic.rail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class LogicalRailLaneResolverTest {
    @Test
    fun `selecting one lane excludes companion parallel lanes`() {
        val groupId = "parallel-assembly"
        val ids = List(6) { UUID.randomUUID() }
        val metadata = ids.withIndex().associate { (flatIndex, id) ->
            val laneIndex = flatIndex / 3
            val segmentIndex = flatIndex % 3
            id to RailInfrastructureMetadata(
                authoring = RailAuthoringMetadata(assembly = RailAssemblyMetadata(
                    groupId = groupId,
                    kind = RailAssemblyKind.FIVE_SEGMENT_CURVE,
                    laneIndex = laneIndex,
                    laneCount = 2,
                    segmentIndex = segmentIndex,
                    segmentCount = 3,
                    role = when (segmentIndex) {
                        0 -> RailAssemblyRole.LEADING_TANGENT
                        2 -> RailAssemblyRole.TRAILING_TANGENT
                        else -> RailAssemblyRole.CURVE
                    }
                ))
            )
        }

        assertEquals(
            listOf(ids[3], ids[4], ids[5]),
            resolveLogicalRailLaneIds(ids[4], metadata)
        )
    }

    @Test
    fun `single physical rail resolves to itself`() {
        val selected = UUID.randomUUID()

        assertEquals(listOf(selected), resolveLogicalRailLaneIds(selected, emptyMap()))
    }

    @Test
    fun `incomplete lane is rejected`() {
        val selected = UUID.randomUUID()
        val metadata = mapOf(
            selected to RailInfrastructureMetadata(
                authoring = RailAuthoringMetadata(assembly = RailAssemblyMetadata(
                    groupId = "incomplete",
                    kind = RailAssemblyKind.FIVE_SEGMENT_CURVE,
                    laneIndex = 0,
                    laneCount = 1,
                    segmentIndex = 0,
                    segmentCount = 2,
                    role = RailAssemblyRole.LEADING_TANGENT
                ))
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            resolveLogicalRailLaneIds(selected, metadata)
        }
    }
}
