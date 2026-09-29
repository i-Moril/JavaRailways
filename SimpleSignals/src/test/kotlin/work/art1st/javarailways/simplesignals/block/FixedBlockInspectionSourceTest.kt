package work.art1st.javarailways.simplesignals.block

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simpleato.api.inspection.InspectionBindingInput
import work.art1st.javarailways.simplesignals.region.lineTopologyState
import java.lang.reflect.Proxy
import java.util.UUID

class FixedBlockInspectionSourceTest {
    private val worldId = UUID.randomUUID()
    private val first = UUID(0L, 1L)
    private val second = UUID(0L, 2L)
    private val topology = lineTopologyState(7L, first, second)

    @Test
    fun `multi rail AB block binds one entry floor and produces a complete contribution`() {
        val block = FixedBlockDefinition(
            "test:ab-block",
            1L,
            listOf(
                FixedBlockTraversal(first, ViewDirection.AB, 2.0, 10.0),
                FixedBlockTraversal(second, ViewDirection.AB, 0.0, 8.0)
            )
        )
        val snapshot = snapshot(block)
        val route = route(
            RouteSegmentSnapshot(first, ViewDirection.AB, 0.0, 10.0),
            RouteSegmentSnapshot(second, ViewDirection.AB, 0.0, 10.0)
        )

        val binding = FixedBlockInspectionSource { snapshot }.source.bindingProvider.bind(
            InspectionBindingInput(stamp(), "train", route)
        )
        val declaration = binding.declarations.single()

        assertEquals(block.blockId, declaration.declarationId)
        assertEquals(first, declaration.railId)
        assertEquals(ViewDirection.AB, declaration.facing)
        assertEquals(2.0, declaration.triggerRouteDistanceMeters, 1.0e-9)
        assertEquals(18.0, requireNotNull(declaration.minimumRequestedAuthorityEndRouteDistanceMeters), 1.0e-9)

        val policy = FixedBlockRouteResourcePolicy(plugin()) { snapshot }
        val contribution = policy.contribute(
            worldId,
            intent(route, declaration.minimumRequestedAuthorityEndRouteDistanceMeters!!)
        ).single()
        assertEquals(block.blockId, contribution.resource.resourceId)
        assertEquals(RouteBoundary(18.0), contribution.releaseAfterTailPasses)
    }

    @Test
    fun `multi rail BA block binds the directed entry and route metre exit`() {
        val block = FixedBlockDefinition(
            "test:ba-block",
            1L,
            listOf(
                FixedBlockTraversal(second, ViewDirection.BA, 0.0, 10.0),
                FixedBlockTraversal(first, ViewDirection.BA, 2.0, 10.0)
            )
        )
        val snapshot = snapshot(block)
        val route = route(
            RouteSegmentSnapshot(second, ViewDirection.BA, 0.0, 10.0),
            RouteSegmentSnapshot(first, ViewDirection.BA, 0.0, 10.0)
        )

        val declaration = FixedBlockInspectionSource { snapshot }.source.bindingProvider.bind(
            InspectionBindingInput(stamp(), "train", route)
        ).declarations.single()

        assertEquals(second, declaration.railId)
        assertEquals(ViewDirection.BA, declaration.facing)
        assertEquals(0.0, declaration.triggerRouteDistanceMeters, 1.0e-9)
        assertEquals(18.0, requireNotNull(declaration.minimumRequestedAuthorityEndRouteDistanceMeters), 1.0e-9)
    }

    @Test
    fun `route ending inside a fixed block rejects binding`() {
        val block = FixedBlockDefinition(
            "test:block",
            1L,
            listOf(FixedBlockTraversal(first, ViewDirection.AB, 2.0, 9.0))
        )
        val snapshot = snapshot(block)
        val partial = route(RouteSegmentSnapshot(first, ViewDirection.AB, 0.0, 5.0))

        assertThrows(IllegalStateException::class.java) {
            FixedBlockInspectionSource { snapshot }.source.bindingProvider.bind(
                InspectionBindingInput(stamp(), "train", partial)
            )
        }
    }

    private fun snapshot(block: FixedBlockDefinition): FixedBlockReadSnapshot =
        FixedBlockCatalog().also { catalog ->
            assertEquals(FixedBlockCatalogStatus.PUBLISHED,
                catalog.publish(topology, 1L, listOf(block)).status)
        }.snapshot(topology)

    private fun route(vararg segments: RouteSegmentSnapshot) = RouteSnapshot(
        "test:route",
        1L,
        topology.revision,
        segments.asList()
    )

    private fun stamp() = RailwayStateStamp(worldId.toString(), 1L, topology.revision)

    private fun intent(route: RouteSnapshot, end: Double) = RouteResourceIntent(
        "test:request",
        InfrastructureOwner("test:train", 1L),
        "train",
        stamp(),
        route,
        RouteBoundary(end)
    )

    private fun plugin(): Plugin = Plugin::class.java.cast(
        Proxy.newProxyInstance(Plugin::class.java.classLoader, arrayOf(Plugin::class.java)) {
                proxy, method, arguments ->
            when (method.name) {
                "isEnabled" -> true
                "getName", "toString" -> "SimpleSignals"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.singleOrNull()
                else -> throw UnsupportedOperationException(method.name)
            }
        }
    )
}
