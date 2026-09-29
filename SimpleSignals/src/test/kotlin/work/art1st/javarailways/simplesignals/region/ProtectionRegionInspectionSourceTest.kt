package work.art1st.javarailways.simplesignals.region

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simpleato.api.inspection.InspectionBindingInput
import work.art1st.javarailways.simpleato.api.inspection.InspectionSource
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationResult
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simplesignals.block.FIXED_BLOCK_RESOURCE_TYPE
import work.art1st.javarailways.simplesignals.block.FixedBlockCatalog
import work.art1st.javarailways.simplesignals.block.FixedBlockCatalogStatus
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.block.FixedBlockRouteResourcePolicy
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
import java.lang.reflect.Proxy
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

        val runtime = inspectionRuntime(source)
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
    fun `immediate fixed-block successor extends the region authority floor through that block`() {
        val block = FixedBlockDefinition(
            "test:block/east",
            1L,
            listOf(FixedBlockTraversal(east, ViewDirection.AB, 5.0, 10.0))
        )

        val declaration = source(listOf(block)).bindingProvider.bind(
            InspectionBindingInput(stamp, "train", forwardRoute())
        ).declarations.single()

        assertEquals(5.0, declaration.triggerRouteDistanceMeters, 0.0)
        assertEquals(
            30.0,
            requireNotNull(declaration.minimumRequestedAuthorityEndRouteDistanceMeters),
            0.0
        )
    }

    @Test
    fun `successor matching uses route tolerance without crossing a real gap`() {
        val withinTolerance = FixedBlockDefinition(
            "test:block/within-tolerance",
            1L,
            listOf(
                FixedBlockTraversal(
                    east,
                    ViewDirection.AB,
                    5.0 + Curve.TOLERANCE,
                    10.0
                )
            )
        )
        val beyondTolerance = FixedBlockDefinition(
            "test:block/beyond-tolerance",
            1L,
            listOf(
                FixedBlockTraversal(
                    east,
                    ViewDirection.AB,
                    5.0 + Curve.TOLERANCE * 2.0,
                    10.0
                )
            )
        )

        val matched = source(listOf(withinTolerance)).bindingProvider.bind(
            InspectionBindingInput(stamp, "train", forwardRoute())
        ).declarations.single()
        val separated = source(listOf(beyondTolerance)).bindingProvider.bind(
            InspectionBindingInput(stamp, "train", forwardRoute())
        ).declarations.single()

        assertEquals(
            30.0,
            requireNotNull(matched.minimumRequestedAuthorityEndRouteDistanceMeters),
            0.0
        )
        assertEquals(
            25.0,
            requireNotNull(separated.minimumRequestedAuthorityEndRouteDistanceMeters),
            0.0
        )
    }

    @Test
    fun `reverse route extends through the fixed block after the reversed region exit`() {
        val reverseRoute = RouteSnapshot(
            "test:route/reverse-successor",
            1L,
            topology.revision,
            listOf(
                segment(east, ViewDirection.BA, 0.0, 10.0),
                segment(middle, ViewDirection.BA, 0.0, 10.0),
                segment(west, ViewDirection.BA, 0.0, 10.0)
            )
        )
        val reverseBlock = FixedBlockDefinition(
            "test:block/west-reverse",
            1L,
            listOf(FixedBlockTraversal(west, ViewDirection.BA, 0.0, 5.0))
        )

        val declaration = source(listOf(reverseBlock)).bindingProvider.bind(
            InspectionBindingInput(stamp, "train", reverseRoute)
        ).declarations.single()

        assertEquals(east, declaration.railId)
        assertEquals(ViewDirection.BA, declaration.facing)
        assertEquals(5.0, declaration.triggerRouteDistanceMeters, 0.0)
        assertEquals(
            30.0,
            requireNotNull(declaration.minimumRequestedAuthorityEndRouteDistanceMeters),
            0.0
        )
    }

    @Test
    fun `region and block snapshots must share one topology root and definition revision`() {
        val sameValueTopology = topologyState(
            revision = topology.revision,
            railIds = listOf(west, middle, east, branchWest, branchEast),
            connections = listOf(
                endpoint(west, RailEndpoint.B) to endpoint(middle, RailEndpoint.A),
                endpoint(west, RailEndpoint.B) to endpoint(branchWest, RailEndpoint.A),
                endpoint(middle, RailEndpoint.B) to endpoint(east, RailEndpoint.A),
                endpoint(middle, RailEndpoint.B) to endpoint(branchEast, RailEndpoint.A)
            )
        )
        val capturedRegions = regionSnapshot()
        val otherRootBlocks = fixedBlockCatalog(3L, emptyList(), sameValueTopology)
        val otherRevisionBlocks = fixedBlockCatalog(4L, emptyList())

        val topologyFailure = assertThrows(IllegalStateException::class.java) {
            ProtectionRegionInspectionSource(
                { capturedRegions },
                { otherRootBlocks.snapshot(sameValueTopology) }
            ).source.bindingProvider.bind(InspectionBindingInput(stamp, "train", forwardRoute()))
        }
        val revisionFailure = assertThrows(IllegalStateException::class.java) {
            ProtectionRegionInspectionSource(
                { capturedRegions },
                { otherRevisionBlocks.snapshot(topology) }
            ).source.bindingProvider.bind(InspectionBindingInput(stamp, "train", forwardRoute()))
        }

        assertEquals(
            "protection regions and fixed blocks were captured from different topology roots",
            topologyFailure.message
        )
        assertEquals(
            "protection regions and fixed blocks were captured from different definition revisions",
            revisionFailure.message
        )
    }

    @Test
    fun `expanded region boundary feeds the existing fixed-block resource contributor`() {
        val worldId = UUID.randomUUID()
        val route = forwardRoute()
        val block = FixedBlockDefinition(
            "test:block/east-resource",
            1L,
            listOf(FixedBlockTraversal(east, ViewDirection.AB, 5.0, 10.0))
        )
        val blockSnapshot = fixedBlockCatalog(3L, listOf(block)).snapshot(topology)
        val source = ProtectionRegionInspectionSource(
            { regionSnapshot() },
            { blockSnapshot }
        ).source
        val runtime = inspectionRuntime(source)
        val worldStamp = RailwayStateStamp(worldId.toString(), 1L, topology.revision)
        runtime.synchronizeRoute(worldStamp, "train", route)
        val adjusted = runtime.adjustRequestedAuthorityBoundary(
            "train",
            FrozenRouteBinding.from(route),
            RouteBoundary(5.1)
        )
        val policy = FixedBlockRouteResourcePolicy(plugin("SimpleSignals")) { candidateWorld ->
            blockSnapshot.takeIf { candidateWorld == worldId }
        }

        val contribution = policy.contribute(
            worldId,
            RouteResourceIntent(
                "test:region-block-request",
                InfrastructureOwner("test:train", 1L),
                "train",
                worldStamp,
                route,
                adjusted
            )
        ).single()

        assertEquals(RouteBoundary(30.0), adjusted)
        assertEquals(
            StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, block.blockId),
            contribution.resource
        )
        assertEquals(RouteBoundary(30.0), contribution.releaseAfterTailPasses)
    }

    @Test
    fun `multiple fixed blocks beginning at the region exit reject conflicting definitions`() {
        val first = FixedBlockDefinition(
            "test:block/east-one",
            1L,
            listOf(FixedBlockTraversal(east, ViewDirection.AB, 5.0, 10.0))
        )
        val second = FixedBlockDefinition(
            "test:block/east-two",
            1L,
            listOf(FixedBlockTraversal(east, ViewDirection.AB, 5.0, 10.0))
        )

        assertThrows(IllegalStateException::class.java) {
            source(listOf(first, second)).bindingProvider.bind(
                InspectionBindingInput(stamp, "train", forwardRoute())
            )
        }
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
        val blocks = fixedBlockCatalog(0L, emptyList())
        val source = ProtectionRegionInspectionSource(
            { requireNotNull(catalog.compilationSnapshot()) },
            { blocks.snapshot(topology) }
        ).source

        val binding = source.bindingProvider.bind(
            InspectionBindingInput(stamp, "train", forwardRoute())
        )

        assertEquals(0, binding.declarations.size)
        assertEquals(0L, binding.definitionRevision)
    }

    private fun source(blocks: Collection<FixedBlockDefinition> = emptyList()) =
        ProtectionRegionInspectionSource(
            { regionSnapshot() },
            { fixedBlockCatalog(3L, blocks).snapshot(topology) }
        ).source

    private fun regionSnapshot(
        definitionRevision: Long = 3L,
        topologyState: TopologyState = topology
    ): ProtectionRegionCompilationSnapshot {
        val catalog = ProtectionRegionCatalog()
        assertEquals(
            ProtectionRegionCatalogStatus.PUBLISHED,
            catalog.publish(topologyState, definitionRevision, listOf(region())).status
        )
        return requireNotNull(catalog.compilationSnapshot())
    }

    private fun fixedBlockCatalog(
        definitionRevision: Long,
        blocks: Collection<FixedBlockDefinition>,
        topologyState: TopologyState = topology
    ) = FixedBlockCatalog().also { catalog ->
        assertEquals(
            FixedBlockCatalogStatus.PUBLISHED,
            catalog.publish(topologyState, definitionRevision, blocks).status
        )
    }

    private fun inspectionRuntime(source: InspectionSource) = InspectionRuntime(
        publisher = { publication ->
            TrainRestrictionPublicationResult(
                TrainRestrictionPublicationStatus.ACCEPTED,
                publication.revision
            )
        },
        sourceUnavailable = { _, _, failure -> throw failure }
    ).also { it.replaceSources(listOf(source)) }

    private fun plugin(name: String): Plugin = Plugin::class.java.cast(
        Proxy.newProxyInstance(Plugin::class.java.classLoader, arrayOf(Plugin::class.java)) {
                proxy, method, arguments ->
            when (method.name) {
                "isEnabled" -> true
                "getName", "toString" -> name
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.singleOrNull()
                else -> throw UnsupportedOperationException(method.name)
            }
        }
    )

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
