package work.art1st.javarailways.simplesignals.block

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
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
    private val policy = FixedBlockRouteResourcePolicy(plugin) { candidateWorld ->
        snapshot.takeIf { candidateWorld == worldId }
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
    fun `authority ending at entry does not claim block while a partial traversal fails closed`() {
        assertTrue(policy.contribute(worldId, intent(2.0)).isEmpty())

        val failure = assertThrows(IllegalStateException::class.java) {
            policy.contribute(worldId, intent(12.0))
        }

        assertTrue(failure.message.orEmpty().contains("partially traverses fixed block test:block"))
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
        val reversePolicy = FixedBlockRouteResourcePolicy(plugin) { reverseSnapshot }
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
        val emptyPolicy = FixedBlockRouteResourcePolicy(plugin) { emptySnapshot }

        assertTrue(emptyPolicy.contribute(worldId, intent(18.0)).isEmpty())
    }

    @Test
    fun `one authority may not traverse the same fixed block twice`() {
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
        val loopPolicy = FixedBlockRouteResourcePolicy(plugin) { loopSnapshot }
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

        val failure = assertThrows(IllegalStateException::class.java) {
            loopPolicy.contribute(worldId, loopIntent)
        }

        assertTrue(failure.message.orEmpty().contains("more than once"))
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
        val unavailable = FixedBlockRouteResourcePolicy(plugin) { null }
        val failure = assertThrows(IllegalStateException::class.java) {
            unavailable.contribute(worldId, intent(18.0))
        }

        assertTrue(failure.message.orEmpty().contains("runtime is unavailable"))
    }

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
        observationStamp = RailwayStateStamp(worldId.toString(), 9L, topology.revision),
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
