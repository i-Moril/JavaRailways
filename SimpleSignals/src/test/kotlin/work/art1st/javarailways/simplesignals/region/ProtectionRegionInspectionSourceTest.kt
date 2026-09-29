package work.art1st.javarailways.simplesignals.region

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.simpleato.api.inspection.InspectionBindingInput
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationResult
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import java.util.UUID

class ProtectionRegionInspectionSourceTest {
    private val west = UUID.nameUUIDFromBytes("inspection-west".toByteArray())
    private val middle = UUID.nameUUIDFromBytes("inspection-middle".toByteArray())
    private val east = UUID.nameUUIDFromBytes("inspection-east".toByteArray())
    private val branchWest = UUID.nameUUIDFromBytes("inspection-branch-west".toByteArray())
    private val branchEast = UUID.nameUUIDFromBytes("inspection-branch-east".toByteArray())
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
    private val stamp = RailwayStateStamp("world", 1L, topology.revision)

    @Test
    fun `complete crossing compiles one entry to exit authority floor`() {
        val source = source()
        val route = forwardRoute()

        val binding = source.bindingProvider.bind(InspectionBindingInput(stamp, "train", route))

        val declaration = binding.declarations.single()
        assertEquals(PROTECTION_REGION_INSPECTION_SOURCE_ID, binding.sourceId)
        assertEquals(3L, binding.definitionRevision)
        assertEquals("test:region/throat", declaration.declarationId)
        assertEquals(west, declaration.railId)
        assertEquals(ViewDirection.AB, declaration.facing)
        assertEquals(5.0, declaration.triggerRouteDistanceMeters, 0.0)
        assertEquals(
            25.0,
            requireNotNull(declaration.minimumRequestedAuthorityEndRouteDistanceMeters),
            0.0
        )

        val runtime = InspectionRuntime(
            publisher = { publication ->
                TrainRestrictionPublicationResult(
                    TrainRestrictionPublicationStatus.ACCEPTED,
                    publication.revision
                )
            },
            sourceUnavailable = { _, _, failure -> throw failure }
        )
        runtime.replaceSources(listOf(source))
        runtime.synchronizeRoute(stamp, "train", route)
        val frozen = FrozenRouteBinding.from(route)

        assertEquals(
            5.0,
            runtime.adjustRequestedAuthorityBoundary("train", frozen, RouteBoundary(5.0))
                .distanceFromRouteStartMeters,
            0.0
        )
        assertEquals(
            25.0,
            runtime.adjustRequestedAuthorityBoundary("train", frozen, RouteBoundary(5.1))
                .distanceFromRouteStartMeters,
            0.0
        )
    }

    @Test
    fun `reverse crossing keeps the route occurrence direction and reversed gates`() {
        val route = RouteSnapshot(
            "test:route/reverse",
            1L,
            topology.revision,
            listOf(
                segment(east, ViewDirection.BA, 0.0, 10.0),
                segment(middle, ViewDirection.BA, 0.0, 10.0),
                segment(west, ViewDirection.BA, 0.0, 10.0)
            )
        )

        val declaration = source().bindingProvider.bind(
            InspectionBindingInput(stamp, "train", route)
        ).declarations.single()

        assertEquals(east, declaration.railId)
        assertEquals(ViewDirection.BA, declaration.facing)
        assertEquals(5.0, declaration.triggerRouteDistanceMeters, 0.0)
        assertEquals(
            25.0,
            requireNotNull(declaration.minimumRequestedAuthorityEndRouteDistanceMeters),
            0.0
        )
    }

    @Test
    fun `route ending inside the region fails binding instead of requesting a partial path`() {
        val partial = RouteSnapshot(
            "test:route/partial",
            1L,
            topology.revision,
            listOf(
                segment(west, ViewDirection.AB, 0.0, 10.0),
                segment(middle, ViewDirection.AB, 0.0, 5.0)
            )
        )

        assertThrows(IllegalStateException::class.java) {
            source().bindingProvider.bind(InspectionBindingInput(stamp, "train", partial))
        }
    }

    @Test
    fun `empty region publication produces an empty binding`() {
        val catalog = ProtectionRegionCatalog()
        assertEquals(
            ProtectionRegionCatalogStatus.PUBLISHED,
            catalog.publish(topology, 0L, emptyList()).status
        )
        val source = ProtectionRegionInspectionSource {
            requireNotNull(catalog.compilationSnapshot())
        }.source

        val binding = source.bindingProvider.bind(
            InspectionBindingInput(stamp, "train", forwardRoute())
        )

        assertEquals(0, binding.declarations.size)
        assertEquals(0L, binding.definitionRevision)
    }

    private fun source() = ProtectionRegionInspectionSource {
        val catalog = ProtectionRegionCatalog()
        assertEquals(
            ProtectionRegionCatalogStatus.PUBLISHED,
            catalog.publish(topology, 3L, listOf(region())).status
        )
        requireNotNull(catalog.compilationSnapshot())
    }.source

    private fun forwardRoute() = RouteSnapshot(
        "test:route/forward",
        1L,
        topology.revision,
        listOf(
            segment(west, ViewDirection.AB, 0.0, 10.0),
            segment(middle, ViewDirection.AB, 0.0, 10.0),
            segment(east, ViewDirection.AB, 0.0, 10.0)
        )
    )

    private fun region() = ProtectionRegionDefinition(
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
