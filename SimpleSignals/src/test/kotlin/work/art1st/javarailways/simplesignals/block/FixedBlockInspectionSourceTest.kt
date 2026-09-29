package work.art1st.javarailways.simplesignals.block

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.PerceptionSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainSnapshot
import work.art1st.javarailways.simpleato.api.inspection.InspectionBindingInput
import work.art1st.javarailways.simpleato.api.restriction.RouteSpeedRestriction
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationResult
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simplesignals.region.lineTopologyState
import work.art1st.javarailways.simplesignals.runtime.PublishedSectionSignal
import work.art1st.javarailways.simplesignals.runtime.SectionSignalOutput
import work.art1st.javarailways.simplesignals.runtime.SectionSignalState
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

        val binding = FixedBlockInspectionSource(snapshot = { snapshot }).source.bindingProvider.bind(
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

        val declaration = FixedBlockInspectionSource(snapshot = { snapshot }).source.bindingProvider.bind(
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
            FixedBlockInspectionSource(snapshot = { snapshot }).source.bindingProvider.bind(
                InspectionBindingInput(stamp(), "train", partial)
            )
        }
    }

    @Test
    fun `active fixed block publishes configured speed and clears it after passing the signal`() {
        val block = FixedBlockDefinition(
            "test:signal-block",
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
        var signal = PublishedSectionSignal(
            stamp(1L),
            SectionSignalState.Available(SectionSignalOutput(17.5, "custom-proceed"))
        )
        val source = FixedBlockInspectionSource(
            snapshot = { snapshot },
            signalState = { blockId -> signal.takeIf { blockId == block.blockId } }
        ).source
        val publications = arrayListOf<TrainRestrictionSnapshot>()
        val runtime = InspectionRuntime(
            publisher = { publication ->
                publications += publication
                TrainRestrictionPublicationResult(
                    TrainRestrictionPublicationStatus.ACCEPTED,
                    publication.revision
                )
            },
            sourceUnavailable = { _, _, failure -> throw failure }
        )
        runtime.replaceSources(listOf(source))
        runtime.synchronizeRoute(stamp(1L), "train", route)
        val routeBinding = FrozenRouteBinding.from(route)
        val perception = perception(route)

        runtime.update(stamp(1L), routeBinding, 0.0, 3.0, perception)
        val proceed = publications.last().restrictions.single() as RouteSpeedRestriction
        assertEquals(block.blockId, proceed.restrictionId)
        assertEquals(2.0, proceed.routeDistanceMeters, 0.0)
        assertEquals(17.5, proceed.maximumSpeedMetersPerSecond, 0.0)

        signal = PublishedSectionSignal(
            stamp(2L),
            SectionSignalState.Unavailable("configured rule failed")
        )
        runtime.markDirty(FIXED_BLOCK_SIGNAL_SOURCE_ID, listOf(block.blockId))
        runtime.update(stamp(2L), routeBinding, 0.0, 3.0, perception)
        val unavailable = publications.last().restrictions.single() as RouteSpeedRestriction
        assertEquals(0.0, unavailable.maximumSpeedMetersPerSecond, 0.0)

        runtime.update(stamp(3L), routeBinding, 3.0, 3.0, perception)
        assertEquals(0, publications.last().restrictions.size)
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

    private fun stamp(epoch: Long = 1L) =
        RailwayStateStamp(worldId.toString(), epoch, topology.revision)

    private fun perception(route: RouteSnapshot) = PerceptionSnapshot(
        1L,
        TrainSnapshot(
            "train",
            1L,
            0.0,
            false,
            1,
            RailPositionSnapshot(first, ViewDirection.AB, 0.0),
            RailPositionSnapshot(first, ViewDirection.AB, 0.0)
        ),
        route,
        emptyList()
    )

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
