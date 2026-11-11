package work.art1st.javarailways.simplesignals.block

import work.art1st.javarailways.simplesignals.testing.fixedBlockPolicy

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.route.FormationRouteContext
import work.art1st.javarailways.basic.api.route.FormationRoutePurpose
import work.art1st.javarailways.basic.api.route.FormationRoutePolicyResult
import work.art1st.javarailways.basic.api.route.FormationTrainIdentity
import work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.simplesignals.region.endpoint
import work.art1st.javarailways.simplesignals.region.lineTopologyState
import work.art1st.javarailways.simplesignals.region.topologyState
import java.lang.reflect.Proxy
import java.util.UUID

class FixedBlockRouteResourcePolicyTest {
    @Test
    fun `resource contribution follows both geometric directions on a mixed facing path`() {
        val mixed = topologyState(7L, listOf(first, second), listOf(
            endpoint(first, RailEndpoint.B) to endpoint(second, RailEndpoint.B)))
        val definition = FixedBlockDefinition("test:mixed", 1L, listOf(
            FixedBlockTraversal(first, ViewDirection.AB, 2.0, 10.0),
            FixedBlockTraversal(second, ViewDirection.BA, 2.0, 10.0)))
        val snapshot = FixedBlockCatalog().also { it.publish(mixed, 1L, listOf(definition)) }.snapshot(mixed)
        val contributor = FixedBlockRouteResourcePolicy(plugin) { snapshot }
        val forward = route(listOf(RouteSegmentSnapshot(first, ViewDirection.AB, 0.0, 10.0),
            RouteSegmentSnapshot(second, ViewDirection.BA, 0.0, 10.0)))
        val reverse = route(listOf(RouteSegmentSnapshot(second, ViewDirection.AB, 0.0, 10.0),
            RouteSegmentSnapshot(first, ViewDirection.BA, 0.0, 10.0)))
        val resource = StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, definition.blockId)
        assertEquals(resource, contributor.contribute(worldId, intent(5.0, forward)).single().resource)
        assertEquals(resource, contributor.contribute(worldId, intent(18.0, reverse)).single().resource)
        assertEquals(RouteBoundary(18.0), contributor.contribute(worldId, intent(20.0, reverse)).single().releaseAfterTailPasses)
        assertTrue(contributor.exists(worldId, definition.blockId))
    }

    private val worldId = UUID.randomUUID()
    private val first = UUID(0L, 1L)
    private val second = UUID(0L, 2L)
    private val topology = lineTopologyState(7L, first, second)
    private val block = FixedBlockDefinition(
        "test:block",
        3L,
        listOf(
            FixedBlockTraversal(first, ViewDirection.AB, 2.0, 10.0),
            FixedBlockTraversal(second, ViewDirection.AB, 0.0, 8.0)
        )
    )
    private val snapshot = FixedBlockCatalog().also { catalog ->
        assertEquals(
            FixedBlockCatalogStatus.PUBLISHED,
            catalog.publish(topology, 4L, listOf(block)).status
        )
    }.snapshot(topology)
    private val plugin = plugin("SimpleSignals")
    private val policy = fixedBlockPolicy(plugin) { candidateWorld ->
        snapshot.takeIf { candidateWorld == worldId }
    }

    @Test
    fun `full and clipped reverse visits share the authored block token`() {
        val reverseRoute = route(listOf(
            RouteSegmentSnapshot(second, ViewDirection.BA, 0.0, 10.0),
            RouteSegmentSnapshot(first, ViewDirection.BA, 0.0, 10.0)))
        val twoWay = FixedBlockDefinition(block.blockId, block.revision, block.traversals)
        val twoWaySnapshot = FixedBlockCatalog().also {
            assertEquals(FixedBlockCatalogStatus.PUBLISHED, it.publish(topology, 4L, listOf(twoWay)).status)
        }.snapshot(topology)
        val twoWayPolicy = fixedBlockPolicy(plugin) { twoWaySnapshot }
        val windows = (FixedBlockRouteMatcher.matchRoute(twoWaySnapshot, reverseRoute)
            as FixedBlockAuthorityMatchResult.Matched).windows
        assertEquals(listOf(FixedBlockRouteWindow(block.blockId, 2.0, 18.0, reversed = true)), windows)
        assertTrue(twoWayPolicy.contribute(worldId, intent(2.0, reverseRoute)).isEmpty())
        assertEquals(RouteBoundary(12.0), twoWayPolicy.contribute(worldId, intent(12.0, reverseRoute))
            .single().releaseAfterTailPasses)
        assertEquals(policy.contribute(worldId, intent(18.0)).single().resource,
            twoWayPolicy.contribute(worldId, intent(18.0, reverseRoute)).single().resource)
    }

    @Test
    fun `mixed-facing path reverses order and preserves internal start and end`() {
        val mixedTopology = topologyState(8L, listOf(first, second), listOf(
            endpoint(first, RailEndpoint.B) to endpoint(second, RailEndpoint.B)))
        val mixed = FixedBlockDefinition("test:mixed", 1L, listOf(
            FixedBlockTraversal(first, ViewDirection.AB, 2.0, 10.0),
            FixedBlockTraversal(second, ViewDirection.BA, 2.0, 10.0)))
        val mixedSnapshot = FixedBlockCatalog().also {
            assertEquals(FixedBlockCatalogStatus.PUBLISHED, it.publish(mixedTopology, 1L, listOf(mixed)).status)
        }.snapshot(mixedTopology)
        fun match(vararg segments: RouteSegmentSnapshot) = (FixedBlockRouteMatcher.matchRoute(mixedSnapshot,
            RouteSnapshot("test:mixed-route", 1L, 8L, segments.toList())) as FixedBlockAuthorityMatchResult.Matched).windows
        assertEquals(listOf(FixedBlockRouteWindow(mixed.blockId, 2.0, 18.0, reversed = true)), match(
            RouteSegmentSnapshot(second, ViewDirection.AB, 0.0, 10.0),
            RouteSegmentSnapshot(first, ViewDirection.BA, 0.0, 10.0)))
        assertEquals(listOf(FixedBlockRouteWindow(mixed.blockId, 0.0, 11.0, false, true)), match(
            RouteSegmentSnapshot(second, ViewDirection.AB, 5.0, 10.0),
            RouteSegmentSnapshot(first, ViewDirection.BA, 0.0, 6.0)))
        assertEquals(listOf(FixedBlockRouteWindow(mixed.blockId, 2.0, 15.0, reversed = true)), match(
            RouteSegmentSnapshot(second, ViewDirection.AB, 0.0, 10.0),
            RouteSegmentSnapshot(first, ViewDirection.BA, 0.0, 5.0)))
    }

    @Test
    fun `repeated reverse visits retain one token with only the requested latest release`() {
        val third = UUID(0L, 3L)
        val loop = topologyState(8L, listOf(first, second, third), listOf(
            endpoint(first, RailEndpoint.B) to endpoint(second, RailEndpoint.A),
            endpoint(second, RailEndpoint.B) to endpoint(third, RailEndpoint.A),
            endpoint(third, RailEndpoint.B) to endpoint(first, RailEndpoint.A)))
        val twoWay = FixedBlockDefinition("test:loop", 1L,
            listOf(FixedBlockTraversal(first, ViewDirection.AB, 2.0, 8.0)))
        val loopSnapshot = FixedBlockCatalog().also { it.publish(loop, 1L, listOf(twoWay)) }.snapshot(loop)
        val loopPolicy = fixedBlockPolicy(plugin) { loopSnapshot }
        val repeated = RouteSnapshot("test:reverse-loop", 1L, 8L, listOf(
            RouteSegmentSnapshot(first, ViewDirection.BA, 0.0, 10.0),
            RouteSegmentSnapshot(third, ViewDirection.BA, 0.0, 10.0),
            RouteSegmentSnapshot(second, ViewDirection.BA, 0.0, 10.0),
            RouteSegmentSnapshot(first, ViewDirection.BA, 0.0, 10.0)))
        val windows = (FixedBlockRouteMatcher.matchRoute(loopSnapshot, repeated)
            as FixedBlockAuthorityMatchResult.Matched).windows
        assertEquals(listOf(FixedBlockRouteWindow(twoWay.blockId, 2.0, 8.0, reversed = true),
            FixedBlockRouteWindow(twoWay.blockId, 32.0, 38.0, reversed = true)), windows)
        assertEquals(RouteBoundary(8.0), loopPolicy.contribute(worldId, intent(20.0, repeated))
            .single().releaseAfterTailPasses)
        assertEquals(RouteBoundary(35.0), loopPolicy.contribute(worldId, intent(35.0, repeated))
            .single().releaseAfterTailPasses)
    }

    @Test
    fun `shared protection geometry reverse traversal cannot hide an interior branch departure`() {
        val branch = UUID(0L, 3L)
        val fork = topologyState(8L, listOf(first, second, branch), listOf(
            endpoint(first, RailEndpoint.B) to endpoint(second, RailEndpoint.A),
            endpoint(first, RailEndpoint.B) to endpoint(branch, RailEndpoint.A)))
        val twoWay = FixedBlockDefinition(block.blockId, block.revision, block.traversals)
        val forkSnapshot = FixedBlockCatalog(allowTurnoutGeometry = true).also {
            assertEquals(FixedBlockCatalogStatus.PUBLISHED, it.publish(fork, 1L, listOf(twoWay)).status)
        }.snapshot(fork)
        val route = RouteSnapshot("test:reverse-branch", 1L, 8L, listOf(
            RouteSegmentSnapshot(branch, ViewDirection.BA, 0.0, 10.0),
            RouteSegmentSnapshot(first, ViewDirection.BA, 0.0, 10.0)))
        val result = FixedBlockRouteMatcher.matchAuthority(forkSnapshot, route, 5.0)
        assertTrue(result is FixedBlockAuthorityMatchResult.Rejected,
            "joining midway inside the block is not an internal route endpoint")
    }

    @Test
    fun `complete authority traversal contributes one block token held to the exit`() {
        val contributions = policy.contribute(worldId, intent(18.0)).toList()

        assertEquals(1, contributions.size)
        assertEquals(
            StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, block.blockId),
            contributions.single().resource
        )
        assertEquals(RouteBoundary(18.0), contributions.single().releaseAfterTailPasses)
        assertNull(contributions.single().preparation)
    }

    @Test
    fun `authority ending at entry does not claim block while entering claims its whole identity`() {
        assertTrue(policy.contribute(worldId, intent(2.0)).isEmpty())
        val contribution = policy.contribute(worldId, intent(12.0)).single()
        assertEquals(StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, block.blockId), contribution.resource)
        assertEquals(RouteBoundary(12.0), contribution.releaseAfterTailPasses)
    }

    @Test
    fun `operating route may start and stop inside a multi rail fixed block`() {
        val stationRoute = route(listOf(
            RouteSegmentSnapshot(first, ViewDirection.AB, 5.0, 10.0),
            RouteSegmentSnapshot(second, ViewDirection.AB, 0.0, 4.0)
        ))
        val match = FixedBlockRouteMatcher.matchRoute(snapshot, stationRoute) as FixedBlockAuthorityMatchResult.Matched
        assertEquals(FixedBlockRouteWindow(block.blockId, 0.0, 9.0, enteredAtBoundary = false), match.windows.single())
        assertEquals(RouteBoundary(9.0), policy.contribute(worldId, intent(9.0, stationRoute))
            .single().releaseAfterTailPasses)
    }

    @Test
    fun `reverse facing station route keeps directed geometry and allows partial endpoints`() {
        val reverse = FixedBlockDefinition("test:reverse-station", 1L, listOf(
            FixedBlockTraversal(second, ViewDirection.BA, 0.0, 10.0),
            FixedBlockTraversal(first, ViewDirection.BA, 2.0, 10.0)
        ))
        val reverseSnapshot = FixedBlockCatalog().also {
            assertEquals(FixedBlockCatalogStatus.PUBLISHED, it.publish(topology, 1L, listOf(reverse)).status)
        }.snapshot(topology)
        val stationRoute = route(listOf(
            RouteSegmentSnapshot(second, ViewDirection.BA, 4.0, 10.0),
            RouteSegmentSnapshot(first, ViewDirection.BA, 0.0, 5.0)
        ))
        val windows = (FixedBlockRouteMatcher.matchRoute(reverseSnapshot, stationRoute)
            as FixedBlockAuthorityMatchResult.Matched).windows
        assertEquals(FixedBlockRouteWindow(reverse.blockId, 0.0, 11.0, false), windows.single())
    }

    @Test
    fun `shared protection geometry branch departure is not a legal clipped endpoint`() {
        val branch = UUID(0L, 3L)
        val fork = topologyState(8L, listOf(first, second, branch), listOf(
            endpoint(first, RailEndpoint.B) to endpoint(second, RailEndpoint.A),
            endpoint(first, RailEndpoint.B) to endpoint(branch, RailEndpoint.A)
        ))
        val forkSnapshot = FixedBlockCatalog(allowTurnoutGeometry = true).also {
            assertEquals(FixedBlockCatalogStatus.PUBLISHED, it.publish(fork, 1L, listOf(block)).status)
        }.snapshot(fork)
        val branchRoute = RouteSnapshot("test:branch-route", 1L, fork.revision, listOf(
            RouteSegmentSnapshot(first, ViewDirection.AB, 0.0, 10.0),
            RouteSegmentSnapshot(branch, ViewDirection.AB, 0.0, 10.0)
        ))
        val result = FixedBlockRouteMatcher.matchAuthority(forkSnapshot, branchRoute, 6.0)
        assertTrue(result is FixedBlockAuthorityMatchResult.Rejected)
        assertTrue((result as FixedBlockAuthorityMatchResult.Rejected).detail.contains("leaves the directed traversal"))
    }

    @Test
    fun `split route occurrences still form one complete block traversal`() {
        val splitRoute = route(
            listOf(
                RouteSegmentSnapshot(first, ViewDirection.AB, 0.0, 6.0),
                RouteSegmentSnapshot(first, ViewDirection.AB, 6.0, 10.0),
                RouteSegmentSnapshot(second, ViewDirection.AB, 0.0, 10.0)
            )
        )

        val contributions = policy.contribute(worldId, intent(18.0, splitRoute)).toList()

        assertEquals(RouteBoundary(18.0), contributions.single().releaseAfterTailPasses)
    }

    @Test
    fun `BA traversal uses route metres while preserving canonical block geometry`() {
        val reverseBlock = FixedBlockDefinition(
            "test:reverse",
            1L,
            listOf(
                FixedBlockTraversal(second, ViewDirection.BA, 0.0, 10.0),
                FixedBlockTraversal(first, ViewDirection.BA, 2.0, 10.0)
            )
        )
        val reverseSnapshot = FixedBlockCatalog().also { catalog ->
            assertEquals(
                FixedBlockCatalogStatus.PUBLISHED,
                catalog.publish(topology, 1L, listOf(reverseBlock)).status
            )
        }.snapshot(topology)
        val reversePolicy = fixedBlockPolicy(plugin) { reverseSnapshot }
        val reverseRoute = route(
            listOf(
                RouteSegmentSnapshot(second, ViewDirection.BA, 0.0, 10.0),
                RouteSegmentSnapshot(first, ViewDirection.BA, 0.0, 10.0)
            )
        )

        val contribution = reversePolicy.contribute(
            worldId,
            intent(18.0, reverseRoute)
        ).single()

        assertEquals(reverseBlock.blockId, contribution.resource.resourceId)
        assertEquals(RouteBoundary(18.0), contribution.releaseAfterTailPasses)
    }

    @Test
    fun `empty catalog contributes nothing without requiring a derived rail index`() {
        val emptySnapshot = FixedBlockCatalog().also { catalog ->
            assertEquals(
                FixedBlockCatalogStatus.PUBLISHED,
                catalog.publish(topology, 0L, emptyList()).status
            )
        }.snapshot(topology)
        val emptyPolicy = fixedBlockPolicy(plugin) { emptySnapshot }

        assertTrue(emptyPolicy.contribute(worldId, intent(18.0)).isEmpty())
    }

    @Test
    fun `repeated visits contribute one stable token held to last requested exit`() {
        val third = UUID(0L, 3L)
        val loopTopology = topologyState(
            8L,
            listOf(first, second, third),
            listOf(
                endpoint(first, RailEndpoint.B) to endpoint(second, RailEndpoint.A),
                endpoint(second, RailEndpoint.B) to endpoint(third, RailEndpoint.A),
                endpoint(third, RailEndpoint.B) to endpoint(first, RailEndpoint.A)
            )
        )
        val loopBlock = FixedBlockDefinition(
            "test:loop-block",
            1L,
            listOf(FixedBlockTraversal(first, ViewDirection.AB, 2.0, 8.0))
        )
        val loopSnapshot = FixedBlockCatalog().also { catalog ->
            assertEquals(
                FixedBlockCatalogStatus.PUBLISHED,
                catalog.publish(loopTopology, 1L, listOf(loopBlock)).status
            )
        }.snapshot(loopTopology)
        val loopPolicy = fixedBlockPolicy(plugin) { loopSnapshot }
        val loopRoute = RouteSnapshot(
            "test:loop-route",
            1L,
            loopTopology.revision,
            listOf(
                RouteSegmentSnapshot(first, ViewDirection.AB, 0.0, 10.0),
                RouteSegmentSnapshot(second, ViewDirection.AB, 0.0, 10.0),
                RouteSegmentSnapshot(third, ViewDirection.AB, 0.0, 10.0),
                RouteSegmentSnapshot(first, ViewDirection.AB, 0.0, 10.0)
            )
        )
        val loopIntent = RouteResourceIntent(
            "test:loop-request",
            InfrastructureOwner("test:train", 1L),
            "train",
            RailwayStateStamp(worldId.toString(), 9L, loopTopology.revision),
            loopRoute,
            RouteBoundary(38.0)
        )

        val identity = StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, loopBlock.blockId)
        val both = loopPolicy.contribute(worldId, loopIntent).single()
        assertEquals(identity, both.resource)
        assertEquals(RouteBoundary(38.0), both.releaseAfterTailPasses)
        val firstOnly = loopPolicy.contribute(worldId, loopIntent.copy(desiredAuthority = RouteBoundary(20.0))).single()
        assertEquals(identity, firstOnly.resource)
        assertEquals(RouteBoundary(8.0), firstOnly.releaseAfterTailPasses)
        assertEquals(RouteBoundary(34.0), loopPolicy.contribute(worldId,
            loopIntent.copy(desiredAuthority = RouteBoundary(34.0))).single().releaseAfterTailPasses)
        assertEquals(RouteBoundary(8.0), loopPolicy.contribute(worldId,
            loopIntent.copy(desiredAuthority = RouteBoundary(32.0))).single().releaseAfterTailPasses)
    }

    @Test
    fun `provider accepts only owner contributions without preparation`() {
        assertTrue(policy.exists(worldId, block.blockId))
        assertNull(policy.validateRouteContribution(plugin, worldId, block.blockId, null))
        assertEquals(
            RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
            policy.validateRouteContribution(plugin("Other"), worldId, block.blockId, null)
        )
        assertEquals(
            RouteAdmissionFailureCode.INVALID_PREPARATION,
            policy.validateRouteContribution(
                plugin,
                worldId,
                block.blockId,
                ResourcePreparationDescriptor("test:prepare", emptyMap())
            )
        )
    }

    @Test
    fun `missing world runtime rejects contribution instead of widening authority`() {
        val unavailable = fixedBlockPolicy(plugin) { null }
        val failure = assertThrows(IllegalStateException::class.java) {
            unavailable.contribute(worldId, intent(18.0))
        }

        assertTrue(failure.message.orEmpty().contains("runtime is unavailable"))
    }

    @Test
    fun `formation clearance uses complete frozen route beyond short body authority`() {
        val identity = StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, block.blockId)
        val context = formationContext(listOf(identity))
        val request = intent(5.0).copy(formationContext = context)
        assertNull(policy.authorizeFormationRoute(worldId, context, request))
        val result = policy.authorizeFormationResource(worldId, identity.resourceId, context, request)
            as FormationRoutePolicyResult.Accepted
        assertEquals(identity, result.clearances.single().resource)
        assertEquals(RouteBoundary(18.0), result.clearances.single().releaseAfterTailPasses)
        assertEquals(RouteBoundary(5.0), policy.contribute(worldId, request).single().releaseAfterTailPasses)
    }

    @Test
    fun `formation off-route resource explicitly clears but missing resource never clears`() {
        val absent = StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, "missing")
        val context = formationContext(listOf(absent))
        assertTrue(policy.authorizeFormationResource(worldId, absent.resourceId, context,
            intent(5.0).copy(formationContext = context)) is FormationRoutePolicyResult.Rejected)
        val identity = StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, block.blockId)
        val offRoute = route(listOf(RouteSegmentSnapshot(first, ViewDirection.AB, 0.0, 1.0)))
        val retained = formationContext(listOf(identity))
        val result = policy.authorizeFormationResource(worldId, identity.resourceId, retained,
            intent(1.0, offRoute).copy(formationContext = retained)) as FormationRoutePolicyResult.Accepted
        assertNull(result.clearances.single().releaseAfterTailPasses)
    }

    @Test
    fun `formation rejects wrong context and unavailable territory while both geometries share one resource`() {
        val context = formationContext(emptyList())
        assertEquals(RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
            policy.authorizeFormationRoute(worldId, context, intent(5.0)))
        val blocked = FixedBlockRouteResourcePolicy(plugin, validateRouteAdmission = { _, _ -> error("territory unavailable") },
            snapshotForWorld = { snapshot })
        assertEquals(RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
            blocked.authorizeFormationRoute(worldId, context, intent(5.0).copy(formationContext = context)))
        // Operating permission belongs to ATO. This resource policy validates the exact
        // physical path in either orientation without inventing a block-level permission.
        val reverse = route(listOf(RouteSegmentSnapshot(second, ViewDirection.BA, 0.0, 10.0),
            RouteSegmentSnapshot(first, ViewDirection.BA, 0.0, 10.0)))
        val reverseRequest = intent(20.0, reverse).copy(formationContext = context)
        assertNull(policy.authorizeFormationRoute(worldId, context, reverseRequest))
        assertEquals(StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, block.blockId),
            policy.contribute(worldId, reverseRequest).single().resource)
    }

    // Fixture only: production contexts are issued exclusively by Basic's prepared formation owner.
    private fun formationContext(resources: List<StableInfrastructureResource>): FormationRouteContext =
        FormationRouteContext::class.java.constructors.single { it.parameterCount == 6 }.newInstance("test:event", FormationRoutePurpose.OUTPUT_BODY,
            listOf(FormationTrainIdentity("source", listOf("car"), false)),
            listOf(FormationTrainIdentity("train", listOf("car"), false)), resources, null) as FormationRouteContext

    private fun intent(
        desiredAuthorityMeters: Double,
        route: RouteSnapshot = route(
            listOf(
                RouteSegmentSnapshot(first, ViewDirection.AB, 0.0, 10.0),
                RouteSegmentSnapshot(second, ViewDirection.AB, 0.0, 10.0)
            )
        )
    ) = RouteResourceIntent(
        requestId = "test:request",
        owner = InfrastructureOwner("test:train", 1L),
        trainId = "train",
        observationStamp = RailwayStateStamp(worldId.toString(), 9L, route.topologyRevision),
        route = route,
        desiredAuthority = RouteBoundary(desiredAuthorityMeters)
    )

    private fun route(segments: Collection<RouteSegmentSnapshot>) = RouteSnapshot(
        "test:route",
        1L,
        topology.revision,
        segments
    )

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
}
