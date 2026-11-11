package work.art1st.javarailways.simplesignals.region

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import java.util.UUID

class ProtectionRegionCatalogTest {
    private val west = UUID.randomUUID()
    private val middle = UUID.randomUUID()
    private val east = UUID.randomUUID()
    private val branchWest = UUID.randomUUID()
    private val branchEast = UUID.randomUUID()
    private val topology = topologyState(
        revision = 7L,
        railIds = listOf(west, middle, east, branchWest, branchEast),
        connections = listOf(
            endpoint(west, RailEndpoint.B) to endpoint(middle, RailEndpoint.A),
            endpoint(west, RailEndpoint.B) to endpoint(branchWest, RailEndpoint.A),
            endpoint(middle, RailEndpoint.B) to endpoint(east, RailEndpoint.A),
            endpoint(middle, RailEndpoint.B) to endpoint(branchEast, RailEndpoint.A)
        )
    )

    @Test
    fun `one region derives a complete two-turnout throat and matches the selected path`() {
        val catalog = ProtectionRegionCatalog()
        val published = catalog.publish(topology, 3L, listOf(throatRegion()))

        assertEquals(ProtectionRegionCatalogStatus.PUBLISHED, published.status)
        val matched = assertInstanceOf(
            ProtectionRegionRouteMatchResult.Matched::class.java,
            catalog.match(
                RouteSnapshot(
                    "test:route/main",
                    1L,
                    topology.revision,
                    listOf(
                        segment(west, ViewDirection.AB, 5.0, 10.0),
                        segment(middle, ViewDirection.AB, 0.0, 10.0),
                        segment(east, ViewDirection.AB, 0.0, 10.0)
                    )
                )
            )
        )

        val window = matched.windows.single()
        assertEquals("test:region/throat", window.regionId)
        assertEquals("test:boundary/west", window.entry.boundaryId)
        assertEquals(0.0, window.entry.routeDistanceMeters)
        assertEquals("test:boundary/east", window.exit.boundaryId)
        assertEquals(20.0, window.exit.routeDistanceMeters)
    }

    @Test
    fun `reverse traversal converts facing-local coordinates to canonical positions`() {
        val catalog = ProtectionRegionCatalog()
        catalog.publish(topology, 3L, listOf(throatRegion()))

        val matched = assertInstanceOf(
            ProtectionRegionRouteMatchResult.Matched::class.java,
            catalog.match(
                RouteSnapshot(
                    "test:route/reverse",
                    1L,
                    topology.revision,
                    listOf(
                        segment(east, ViewDirection.BA, 5.0, 10.0),
                        segment(middle, ViewDirection.BA, 0.0, 10.0),
                        segment(west, ViewDirection.BA, 0.0, 10.0)
                    )
                )
            )
        )

        val window = matched.windows.single()
        assertEquals("test:boundary/east", window.entry.boundaryId)
        assertEquals("test:boundary/west", window.exit.boundaryId)
    }

    @Test
    fun `invalid replacement preserves the previously published catalog`() {
        val catalog = ProtectionRegionCatalog()
        assertEquals(
            ProtectionRegionCatalogStatus.PUBLISHED,
            catalog.publish(topology, 3L, listOf(throatRegion())).status
        )
        val overlapping = ProtectionRegionDefinition(
            "test:region/overlap",
            1L,
            listOf(
                boundary("test:boundary/overlap-west", middle, 2.0, ViewDirection.AB),
                boundary("test:boundary/overlap-east", east, 8.0, ViewDirection.BA)
            )
        )

        val rejected = catalog.publish(topology, 4L, listOf(throatRegion(), overlapping))

        assertEquals(ProtectionRegionCatalogStatus.REJECTED, rejected.status)
        assertEquals(ProtectionRegionValidationCode.REGION_OVERLAP, rejected.diagnostics.single().code)
        assertEquals(listOf("test:region/throat"), catalog.definitions().map { it.regionId })
    }

    @Test
    fun `duplicate region ID is rejected before publication`() {
        val catalog = ProtectionRegionCatalog()
        val rejected = catalog.publish(topology, 1L, listOf(throatRegion(), throatRegion()))

        assertEquals(ProtectionRegionCatalogStatus.REJECTED, rejected.status)
        assertEquals(ProtectionRegionValidationCode.DUPLICATE_REGION_ID, rejected.diagnostics.single().code)
        assertTrue(catalog.definitions().isEmpty())
    }

    @Test
    fun `definition content cannot change without advancing its catalog revision`() {
        val catalog = ProtectionRegionCatalog()
        catalog.publish(topology, 3L, listOf(throatRegion()))
        val original = throatRegion()
        val changed = ProtectionRegionDefinition(original.regionId, 2L, original.boundaries)

        val rejected = catalog.publish(topology, 3L, listOf(changed))

        assertEquals(ProtectionRegionCatalogStatus.REJECTED, rejected.status)
        assertEquals(
            ProtectionRegionValidationCode.INVALID_DEFINITION_REVISION,
            rejected.diagnostics.single().code
        )
        assertEquals(1L, catalog.definitions().single().revision)
    }

    @Test
    fun `region definition defensively copies authored boundaries`() {
        val boundaries = throatRegion().boundaries.toMutableList()
        val definition = ProtectionRegionDefinition("test:region/copied", 1L, boundaries)

        boundaries.clear()

        assertEquals(4, definition.boundaries.size)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (definition.boundaries as MutableList<ProtectionBoundaryDefinition>).clear()
        }
    }

    @Test
    fun `route starting inside a region is rejected instead of inventing an entry`() {
        val catalog = ProtectionRegionCatalog()
        catalog.publish(topology, 3L, listOf(throatRegion()))

        val rejected = assertInstanceOf(
            ProtectionRegionRouteMatchResult.Rejected::class.java,
            catalog.match(
                RouteSnapshot(
                    "test:route/partial",
                    1L,
                    topology.revision,
                    listOf(
                        segment(middle, ViewDirection.AB, 2.0, 10.0),
                        segment(east, ViewDirection.AB, 0.0, 10.0)
                    )
                )
            )
        )

        assertEquals(ProtectionRegionValidationCode.INVALID_ROUTE, rejected.diagnostics.single().code)
    }

    @Test
    fun `route ending inside a region is rejected instead of inventing an exit`() {
        val catalog = ProtectionRegionCatalog()
        catalog.publish(topology, 3L, listOf(throatRegion()))

        val rejected = assertInstanceOf(
            ProtectionRegionRouteMatchResult.Rejected::class.java,
            catalog.match(
                RouteSnapshot(
                    "test:route/partial-exit",
                    1L,
                    topology.revision,
                    listOf(
                        segment(west, ViewDirection.AB, 5.0, 10.0),
                        segment(middle, ViewDirection.AB, 0.0, 5.0)
                    )
                )
            )
        )

        assertEquals(ProtectionRegionValidationCode.INVALID_ROUTE, rejected.diagnostics.single().code)
    }

    @Test
    fun `route of another topology revision still validates its physical region window`() {
        val catalog = ProtectionRegionCatalog()
        catalog.publish(topology, 3L, listOf(throatRegion()))

        val rejected = assertInstanceOf(
            ProtectionRegionRouteMatchResult.Rejected::class.java,
            catalog.match(
                RouteSnapshot(
                    "test:route/stale",
                    1L,
                    topology.revision + 1L,
                    listOf(segment(west, ViewDirection.AB, 5.0, 11.0))
                )
            )
        )

        assertEquals(ProtectionRegionValidationCode.INVALID_ROUTE, rejected.diagnostics.single().code)
        val matched = assertInstanceOf(ProtectionRegionRouteMatchResult.Matched::class.java,
            catalog.match(RouteSnapshot("test:route/unchanged", 1L, topology.revision + 1L,
                listOf(segment(west, ViewDirection.AB, 5.0, 10.0), segment(middle, ViewDirection.AB, 0.0, 10.0),
                    segment(east, ViewDirection.AB, 0.0, 5.0)))))
        assertEquals(throatRegion().regionId, matched.windows.single().regionId)
    }

    @Test
    fun `one frozen route keeps separate complete region visits`() {
        val catalog = ProtectionRegionCatalog()
        catalog.publish(topology, 3L, listOf(throatRegion()))

        val matched = assertInstanceOf(
            ProtectionRegionRouteMatchResult.Matched::class.java,
            catalog.match(
                RouteSnapshot(
                    "test:route/repeated",
                    1L,
                    topology.revision,
                    listOf(
                        segment(west, ViewDirection.AB, 5.0, 10.0),
                        segment(middle, ViewDirection.AB, 0.0, 10.0),
                        segment(east, ViewDirection.AB, 0.0, 10.0),
                        segment(east, ViewDirection.BA, 5.0, 10.0),
                        segment(middle, ViewDirection.BA, 0.0, 10.0),
                        segment(west, ViewDirection.BA, 0.0, 10.0)
                    )
                )
            )
        )

        assertEquals(2, matched.windows.size)
        assertEquals(1, matched.windows.map { it.regionId }.distinct().size)
    }

    private fun throatRegion() = ProtectionRegionDefinition(
        "test:region/throat",
        1L,
        listOf(
            boundary("test:boundary/west", west, 5.0, ViewDirection.AB),
            boundary("test:boundary/east", east, 5.0, ViewDirection.BA),
            boundary("test:boundary/branch-west", branchWest, 5.0, ViewDirection.BA),
            boundary("test:boundary/branch-east", branchEast, 5.0, ViewDirection.BA)
        )
    )

    private fun boundary(id: String, railId: UUID, arc: Double, facing: ViewDirection) =
        ProtectionBoundaryDefinition(id, RailPositionSnapshot(railId, facing, arc))

    private fun segment(
        railId: UUID,
        facing: ViewDirection,
        start: Double,
        end: Double
    ) = RouteSegmentSnapshot(railId, facing, start, end)
}
