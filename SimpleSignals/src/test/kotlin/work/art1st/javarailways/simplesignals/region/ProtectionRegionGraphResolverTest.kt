package work.art1st.javarailways.simplesignals.region

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing
import work.art1st.javarailways.core.api.physics.rail.topology.RailCrossingPoint
import java.util.UUID

class ProtectionRegionGraphResolverTest {
    private val railA = UUID.randomUUID()
    private val railB = UUID.randomUUID()
    private val railC = UUID.randomUUID()
    private val topology = ProtectionRegionTopology.derive(lineTopologyState(7L, railA, railB, railC))

    @Test
    fun `crossing rails share one protected interior but not physical connections`() {
        val first = UUID(0, 1)
        val second = UUID(0, 2)
        val crossingTopology = ProtectionRegionTopology.derive(topologyState(7L,
            listOf(first, second), emptyList(), listOf(AtGradeRailCrossing(
                RailCrossingPoint(first, 5.0), RailCrossingPoint(second, 5.0)))))
        val definition = region("test:region/crossing",
            boundary("test:boundary/a", first, 2.0, ViewDirection.AB),
            boundary("test:boundary/b", first, 8.0, ViewDirection.BA),
            boundary("test:boundary/c", second, 2.0, ViewDirection.AB),
            boundary("test:boundary/d", second, 8.0, ViewDirection.BA))
        val result = assertInstanceOf(ProtectionRegionGraphResolution.Resolved::class.java,
            ProtectionRegionGraphResolver.resolve(definition, crossingTopology)).region
        assertEquals(setOf(first, second), result.interior.map { it.railId }.toSet())
        assertTrue(crossingTopology.rails.getValue(first).connectionsFromA.isEmpty())
        assertTrue(crossingTopology.rails.getValue(first).connectionsFromB.isEmpty())

        // The crossing is outside the bounded first-rail interval, so it cannot connect interiors.
        val outside = ProtectionRegionDefinition(definition.regionId, definition.revision,
            listOf(definition.boundaries[0], boundary("test:boundary/b", first, 4.0, ViewDirection.BA)) +
                definition.boundaries.drop(2))
        assertEquals(ProtectionRegionValidationCode.DISCONNECTED_INTERIOR,
            assertInstanceOf(ProtectionRegionGraphResolution.Rejected::class.java,
                ProtectionRegionGraphResolver.resolve(outside, crossingTopology)).diagnostic.code)
    }

    @Test
    fun `multi-rail boundaries derive one connected interior`() {
        val resolved = resolve(
            region(
                "test:region/main",
                boundary("test:boundary/entry", railA, 5.0, ViewDirection.AB),
                boundary("test:boundary/exit", railC, 5.0, ViewDirection.BA)
            )
        )

        assertEquals(
            setOf(
                Triple(railA, 5.0, 10.0),
                Triple(railB, 0.0, 10.0),
                Triple(railC, 0.0, 5.0)
            ),
            resolved.interior.mapTo(linkedSetOf()) {
                Triple(it.railId, it.startAbMeters, it.endAbMeters)
            }
        )
    }

    @Test
    fun `adjacent interiors sharing a cut do not overlap`() {
        val left = resolve(
            region(
                "test:region/left",
                boundary("test:boundary/left-entry", railA, 5.0, ViewDirection.AB),
                boundary("test:boundary/shared-left", railB, 5.0, ViewDirection.BA)
            )
        )
        val right = resolve(
            region(
                "test:region/right",
                boundary("test:boundary/shared-right", railB, 5.0, ViewDirection.AB),
                boundary("test:boundary/right-exit", railC, 5.0, ViewDirection.BA)
            )
        )

        assertNull(ProtectionRegionGraphResolver.overlap(left, right))
    }

    @Test
    fun `positive shared rail length is an overlap`() {
        val first = resolve(
            region(
                "test:region/first",
                boundary("test:boundary/first-entry", railA, 5.0, ViewDirection.AB),
                boundary("test:boundary/first-exit", railC, 5.0, ViewDirection.BA)
            )
        )
        val second = resolve(
            region(
                "test:region/second",
                boundary("test:boundary/second-entry", railB, 2.0, ViewDirection.AB),
                boundary("test:boundary/second-exit", railC, 8.0, ViewDirection.BA)
            )
        )

        assertEquals(
            ProtectionRegionValidationCode.REGION_OVERLAP,
            ProtectionRegionGraphResolver.overlap(first, second)?.code
        )
    }

    @Test
    fun `boundary at rail endpoint is rejected`() {
        val result = ProtectionRegionGraphResolver.resolve(
            region(
                "test:region/main",
                boundary("test:boundary/entry", railA, 0.0, ViewDirection.AB)
            ),
            topology
        )

        assertEquals(
            ProtectionRegionValidationCode.BOUNDARY_AT_RAIL_ENDPOINT,
            assertInstanceOf(ProtectionRegionGraphResolution.Rejected::class.java, result).diagnostic.code
        )
    }

    @Test
    fun `disconnected inward half-edges are rejected`() {
        val isolatedRail = UUID.randomUUID()
        val disconnected = ProtectionRegionTopology.derive(
            topologyState(
                revision = topology.topologyRevision,
                railIds = listOf(railA, railB, railC, isolatedRail),
                connections = listOf(
                    endpoint(railA, RailEndpoint.B) to endpoint(railB, RailEndpoint.A),
                    endpoint(railB, RailEndpoint.B) to endpoint(railC, RailEndpoint.A)
                )
            )
        )
        val result = ProtectionRegionGraphResolver.resolve(
            region(
                "test:region/main",
                boundary("test:boundary/first", railA, 5.0, ViewDirection.AB),
                boundary("test:boundary/second", isolatedRail, 5.0, ViewDirection.BA)
            ),
            disconnected
        )

        assertEquals(
            ProtectionRegionValidationCode.DISCONNECTED_INTERIOR,
            assertInstanceOf(ProtectionRegionGraphResolution.Rejected::class.java, result).diagnostic.code
        )
    }

    @Test
    fun `loop reconnecting inward and outward half-edges is rejected`() {
        val loopA = UUID.randomUUID()
        val loopB = UUID.randomUUID()
        val loop = ProtectionRegionTopology.derive(
            topologyState(
                revision = topology.topologyRevision,
                railIds = listOf(loopA, loopB),
                connections = listOf(
                    endpoint(loopA, RailEndpoint.A) to endpoint(loopB, RailEndpoint.A),
                    endpoint(loopA, RailEndpoint.B) to endpoint(loopB, RailEndpoint.B)
                )
            )
        )
        val result = ProtectionRegionGraphResolver.resolve(
            region(
                "test:region/loop",
                boundary("test:boundary/cut", loopA, 5.0, ViewDirection.AB)
            ),
            loop
        )

        assertEquals(
            ProtectionRegionValidationCode.OUTSIDE_RECONNECTS,
            assertInstanceOf(ProtectionRegionGraphResolution.Rejected::class.java, result).diagnostic.code
        )
    }

    private fun resolve(definition: ProtectionRegionDefinition): ResolvedProtectionRegion =
        assertInstanceOf(
            ProtectionRegionGraphResolution.Resolved::class.java,
            ProtectionRegionGraphResolver.resolve(definition, topology)
        ).region

    private fun region(id: String, vararg boundaries: ProtectionBoundaryDefinition) =
        ProtectionRegionDefinition(id, 1L, boundaries.toList())

    private fun boundary(id: String, railId: UUID, arc: Double, facing: ViewDirection) =
        ProtectionBoundaryDefinition(id, RailPositionSnapshot(railId, facing, arc))

}
