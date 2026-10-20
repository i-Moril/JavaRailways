package work.art1st.javarailways.basic.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailSliceRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import java.util.UUID
import work.art1st.javarailways.basic.infrastructure.admission.PhysicalRouteCompilation
import work.art1st.javarailways.basic.infrastructure.admission.PhysicalRouteRange
import work.art1st.javarailways.basic.infrastructure.admission.PhysicalRouteResourceCompiler

class RouteBoundResourceCatalogTest {
    private val revision = 19L
    private val diamond = StableInfrastructureResource("javarailways:diamond", "test:diamond/main")

    @Test
    fun `complete empty snapshot is distinct from unavailable and requires the exact topology root`() {
        val rail = rail(1)
        var current = lineTopologyState(revision, rail)
        val catalog = RouteBoundResourceCatalog { current }
        val route = project(current, segment(rail, ViewDirection.AB))

        assertInstanceOf(RouteBoundResourceQuery.Unavailable::class.java, catalog.query(route))
        assertEquals(RouteBoundResourceCatalogUpdate.Published, catalog.publish(current, emptyList()))
        val complete = assertInstanceOf(RouteBoundResourceQuery.Complete::class.java, catalog.query(route))
        assertTrue(complete.hits.isEmpty())

        val oldRoot = current
        current = lineTopologyState(revision, rail)
        assertSame(oldRoot, route.topology)
        assertInstanceOf(RouteBoundResourceQuery.StaleTopology::class.java, catalog.query(route))
    }

    @Test
    fun `AB and BA traversal on either anchor resolve the same resource identity`() {
        val first = rail(1)
        val second = rail(2)
        val topology = topologyState(revision, listOf(first, second), emptyList())
        val catalog = RouteBoundResourceCatalog { topology }
        assertEquals(
            RouteBoundResourceCatalogUpdate.Published,
            catalog.publish(topology, listOf(binding(first, second)))
        )

        val firstAb = complete(catalog.query(project(topology, segment(first, ViewDirection.AB)))).hits.single()
        val firstBa = complete(catalog.query(project(topology, segment(first, ViewDirection.BA)))).hits.single()
        val secondAb = complete(catalog.query(project(topology, segment(second, ViewDirection.AB)))).hits.single()

        assertEquals(diamond, firstAb.resource)
        assertEquals(firstAb.resource, firstBa.resource)
        assertEquals(firstAb.resource, secondAb.resource)
        assertEquals(
            listOf(RailSliceRef(first, 2.0, 4.0), RailSliceRef(second, 6.0, 8.0)),
            firstAb.occupancyAnchors
        )
        assertEquals(2.0, firstAb.routeStartMeters)
        assertEquals(4.0, firstAb.routeEndMeters)
        assertEquals(6.0, firstBa.routeStartMeters)
        assertEquals(8.0, firstBa.routeEndMeters)
    }

    @Test
    fun `invalid update keeps the previous complete snapshot`() {
        val first = rail(1)
        val second = rail(2)
        val topology = topologyState(revision, listOf(first, second), emptyList())
        val catalog = RouteBoundResourceCatalog { topology }
        assertEquals(
            RouteBoundResourceCatalogUpdate.Published,
            catalog.publish(topology, listOf(binding(first, second)))
        )

        val rejected = catalog.publish(
            topology,
            listOf(
                RouteBoundResourceBinding(
                    diamond,
                    listOf(RailSliceRef(first, 4.0, 2.0), RailSliceRef(second, 6.0, 8.0))
                )
            )
        )

        assertInstanceOf(RouteBoundResourceCatalogUpdate.Rejected::class.java, rejected)
        assertEquals(
            diamond,
            complete(catalog.query(project(topology, segment(first, ViewDirection.AB)))).hits.single().resource
        )
    }

    @Test
    fun `separate visits keep one stable identity and separate route spans`() {
        val first = rail(1)
        val middle = rail(2)
        val last = rail(3)
        val topology = lineTopologyState(revision, first, middle, last)
        val catalog = RouteBoundResourceCatalog { topology }
        assertEquals(
            RouteBoundResourceCatalogUpdate.Published,
            catalog.publish(topology, listOf(binding(first, last)))
        )
        val route = project(
            topology,
            segment(first, ViewDirection.AB),
            segment(middle, ViewDirection.AB),
            segment(last, ViewDirection.AB)
        )

        val queried = assertInstanceOf(RouteBoundResourceQuery.Complete::class.java, catalog.query(route))
        assertEquals(2, queried.hits.size)
        assertEquals(1, queried.hits.map { it.resource }.distinct().size)
        val compiled = assertInstanceOf(PhysicalRouteCompilation.Compiled::class.java,
            PhysicalRouteResourceCompiler(catalog).compile(route, PhysicalRouteRange(0.0, 30.0)))
        assertEquals(1, compiled.admission.resources.resources.count { it == diamond })
        assertTrue(compiled.admission.preparations.isEmpty())
        assertEquals(28.0, compiled.admission.tailReleaseEntries.single { it.resource == diamond }.tailClearRouteMeters)
    }

    @Test
    fun `adjacent occurrence hits merge into one route span`() {
        val first = rail(1)
        val second = rail(2)
        val topology = lineTopologyState(revision, first, second)
        val catalog = RouteBoundResourceCatalog { topology }
        assertEquals(
            RouteBoundResourceCatalogUpdate.Published,
            catalog.publish(
                topology,
                listOf(
                    RouteBoundResourceBinding(
                        diamond,
                        listOf(RailSliceRef(first, 9.0, 10.0), RailSliceRef(second, 0.0, 1.0))
                    )
                )
            )
        )
        val route = project(
            topology,
            segment(first, ViewDirection.AB),
            segment(second, ViewDirection.AB)
        )

        val hit = complete(catalog.query(route)).hits.single()

        assertEquals(9.0, hit.routeStartMeters)
        assertEquals(11.0, hit.routeEndMeters)
    }

    @Test
    fun `closed interval contact at anchor boundary is a fail-safe hit`() {
        val first = rail(1)
        val second = rail(2)
        val topology = topologyState(revision, listOf(first, second), emptyList())
        val catalog = RouteBoundResourceCatalog { topology }
        assertEquals(
            RouteBoundResourceCatalogUpdate.Published,
            catalog.publish(topology, listOf(binding(first, second)))
        )
        val route = project(topology, segment(first, ViewDirection.AB, 0.0, 2.0))

        val hit = complete(catalog.query(route)).hits.single()

        assertEquals(2.0, hit.routeStartMeters)
        assertEquals(2.0, hit.routeEndMeters)
    }

    private fun binding(first: UUID, second: UUID) = RouteBoundResourceBinding(
        diamond,
        listOf(RailSliceRef(first, 2.0, 4.0), RailSliceRef(second, 6.0, 8.0))
    )

    private fun project(
        topology: TopologyState,
        vararg segments: RouteSegmentSnapshot
    ): ProjectedTopologyRoute = assertInstanceOf(
        TopologyRouteProjectionResult.Projected::class.java,
        TopologyRouteProjector.project(
            RouteSnapshot("test:route", 1L, topology.revision, segments.asList()),
            topology
        )
    ).route

    private fun complete(result: RouteBoundResourceQuery): RouteBoundResourceQuery.Complete =
        assertInstanceOf(RouteBoundResourceQuery.Complete::class.java, result)

    private fun segment(
        railId: UUID,
        facing: ViewDirection,
        startMeters: Double = 0.0,
        endMeters: Double = 10.0
    ) = RouteSegmentSnapshot(railId, facing, startMeters, endMeters)

    private fun rail(value: Long): UUID = UUID(0L, value)
}
