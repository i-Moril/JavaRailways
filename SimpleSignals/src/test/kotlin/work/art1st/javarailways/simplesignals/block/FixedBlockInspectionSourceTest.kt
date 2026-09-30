package work.art1st.javarailways.simplesignals.block

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
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
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.region.lineTopologyState
import work.art1st.javarailways.simplesignals.region.topologyState
import work.art1st.javarailways.simplesignals.region.endpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntime
import work.art1st.javarailways.simplesignals.runtime.PublishedSectionSignal
import work.art1st.javarailways.simplesignals.runtime.SectionSignalOutput
import work.art1st.javarailways.simplesignals.runtime.SectionSignalState
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionPublicationStatus
import work.art1st.javarailways.simplesignals.signal.ConfiguredSignalOutput
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalClearLevel
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalDefinition
import work.art1st.javarailways.simplesignals.signal.fixedBlockSignalDeclarationId
import java.lang.reflect.Proxy
import java.util.UUID

class FixedBlockInspectionSourceTest {
    private val worldId = UUID.randomUUID()
    private val first = UUID(0L, 1L)
    private val second = UUID(0L, 2L)
    private val topology = lineTopologyState(7L, first, second)

    @Test
    fun `repeated entrance uses stable invalidation and nearest active restriction with separate floors`() {
        val third = UUID(0L, 3L)
        val loop = topologyState(7L, listOf(first, second, third), listOf(
            endpoint(first, RailEndpoint.B) to endpoint(second, RailEndpoint.A),
            endpoint(second, RailEndpoint.B) to endpoint(third, RailEndpoint.A),
            endpoint(third, RailEndpoint.B) to endpoint(first, RailEndpoint.A)))
        val block = FixedBlockDefinition("test:loop", 1L,
            listOf(FixedBlockTraversal(first, ViewDirection.AB, 2.0, 8.0)))
        val snapshot = FixedBlockCatalog().also {
            assertEquals(FixedBlockCatalogStatus.PUBLISHED, it.publish(loop, 1L, listOf(block)).status)
        }.snapshot(loop)
        val route = route(RouteSegmentSnapshot(first, ViewDirection.AB, 0.0, 10.0),
            RouteSegmentSnapshot(second, ViewDirection.AB, 0.0, 10.0),
            RouteSegmentSnapshot(third, ViewDirection.AB, 0.0, 10.0),
            RouteSegmentSnapshot(first, ViewDirection.AB, 0.0, 10.0))
        var signal: PublishedSectionSignal? = null
        val source = FixedBlockInspectionSource({ snapshot }, { signal }, { true }).source
        val declarations = source.bindingProvider.bind(InspectionBindingInput(stamp(), "train", route)).declarations
        assertEquals(listOf(2.0, 32.0), declarations.map { it.triggerRouteDistanceMeters })
        assertEquals(listOf(8.0, 38.0), declarations.map { it.minimumRequestedAuthorityEndRouteDistanceMeters })
        assertEquals(setOf(fixedBlockSignalDeclarationId(block.blockId)), declarations.map { it.declarationId }.toSet())
        val publications = arrayListOf<TrainRestrictionSnapshot>()
        val runtime = InspectionRuntime({ publication ->
            publications += publication
            TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, publication.revision)
        }) { _, _, failure -> throw failure }
        runtime.replaceSources(listOf(source))
        runtime.synchronizeRoute(stamp(), "train", route)
        val binding = FrozenRouteBinding.from(route)
        assertEquals(RouteBoundary(8.0), runtime.adjustRequestedAuthorityBoundary("train", binding, RouteBoundary(3.0)))
        assertEquals(RouteBoundary(38.0), runtime.adjustRequestedAuthorityBoundary("train", binding, RouteBoundary(33.0)))
        val inspection = runtime.createTrainInspection("train")
        fun update(epoch: Long, head: Double) = inspection.update(stamp(epoch), binding, head,
            40.0, 20.0, 1.0, perception(route))
        fun restriction() = publications.last().restrictions.single() as RouteSpeedRestriction
        update(1L, 0.0)
        assertEquals(2.0, restriction().routeDistanceMeters)
        assertEquals(0.0, restriction().maximumSpeedMetersPerSecond, "missing baseline stays red")
        signal = PublishedSectionSignal(stamp(2L), SectionSignalState.Available(SectionSignalOutput(9.5, "custom-open")))
        runtime.markDirty(FIXED_BLOCK_SIGNAL_SOURCE_ID, listOf(fixedBlockSignalDeclarationId(block.blockId)))
        update(2L, 0.0)
        assertEquals(9.5, restriction().maximumSpeedMetersPerSecond)
        update(2L, 3.0)
        assertEquals(32.0, restriction().routeDistanceMeters)
        signal = PublishedSectionSignal(stamp(3L), SectionSignalState.Available(SectionSignalOutput(0.0, "custom-red")))
        runtime.markDirty(FIXED_BLOCK_SIGNAL_SOURCE_ID, listOf(fixedBlockSignalDeclarationId(block.blockId)))
        update(3L, 3.0)
        assertEquals(32.0, restriction().routeDistanceMeters)
        assertEquals(0.0, restriction().maximumSpeedMetersPerSecond)
        update(4L, 33.0)
        assertEquals(emptyList<Any>(), publications.last().restrictions)
    }

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

        val binding = FixedBlockInspectionSource(
            snapshot = { snapshot },
            inspectionLeadTimeSeconds = { _, blockId ->
                if (blockId == block.blockId) 12.0 else 0.0
            }
        ).source.bindingProvider.bind(
            InspectionBindingInput(stamp(), "train", route)
        )
        val declaration = binding.declarations.single()

        assertEquals(fixedBlockSignalDeclarationId(block.blockId), declaration.declarationId)
        assertEquals(first, declaration.railId)
        assertEquals(ViewDirection.AB, declaration.facing)
        assertEquals(2.0, declaration.triggerRouteDistanceMeters, 1.0e-9)
        assertEquals(12.0, declaration.inspectionLeadTimeSeconds, 0.0)
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
    fun `route ending at a station inside a fixed block clamps the floor to the stop`() {
        val block = FixedBlockDefinition(
            "test:block",
            1L,
            listOf(FixedBlockTraversal(first, ViewDirection.AB, 2.0, 9.0))
        )
        val snapshot = snapshot(block)
        val partial = route(RouteSegmentSnapshot(first, ViewDirection.AB, 0.0, 5.0))

        val declaration = FixedBlockInspectionSource(snapshot = { snapshot }).source.bindingProvider.bind(
                InspectionBindingInput(stamp(), "train", partial)
            ).declarations.single()
        assertEquals(2.0, declaration.triggerRouteDistanceMeters)
        assertEquals(5.0, declaration.minimumRequestedAuthorityEndRouteDistanceMeters)
    }

    @Test
    fun `starting within a block creates no entrance declaration or floor but still contributes its token`() {
        val block = FixedBlockDefinition("test:station", 1L,
            listOf(FixedBlockTraversal(first, ViewDirection.AB, 2.0, 9.0)))
        val route = route(RouteSegmentSnapshot(first, ViewDirection.AB, 5.0, 10.0))
        val publications = arrayListOf<TrainRestrictionSnapshot>()
        val source = FixedBlockInspectionSource(snapshot = { snapshot(block) },
            signalState = { error("entrance signal behind the occupant must not be read") },
            hasConfiguredSignal = { true },
            inspectionLeadTimeSeconds = { _, _ -> error("there is no entrance to prepare") }).source
        assertEquals(emptyList<Any>(), source.bindingProvider.bind(
            InspectionBindingInput(stamp(), "train", route)).declarations)
        val runtime = InspectionRuntime(
            publisher = { publication ->
                publications += publication
                TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, publication.revision)
            }, sourceUnavailable = { _, _, failure -> throw failure })
        runtime.replaceSources(listOf(source))
        runtime.synchronizeRoute(stamp(), "train", route)
        val binding = FrozenRouteBinding.from(route)
        assertEquals(RouteBoundary(1.0), runtime.adjustRequestedAuthorityBoundary("train", binding, RouteBoundary(1.0)))
        runtime.createTrainInspection("train").update(stamp(), binding, 0.0, 3.0, 20.0, 1.0, perception(route))
        assertEquals(emptyList<Any>(), publications.single().restrictions)
        assertEquals(block.blockId, FixedBlockRouteResourcePolicy(plugin()) { snapshot(block) }
            .contribute(worldId, intent(route, 1.0)).single().resource.resourceId)
    }

    @Test
    fun `tail before real entrance and head after it naturally excludes the passed red signal`() {
        val block = FixedBlockDefinition("test:passed-entry", 1L,
            listOf(FixedBlockTraversal(first, ViewDirection.AB, 2.0, 9.0)))
        val route = route(RouteSegmentSnapshot(first, ViewDirection.AB, 0.0, 10.0))
        val source = FixedBlockInspectionSource(snapshot = { snapshot(block) },
            signalState = { error("signal behind the head must not be inspected") },
            hasConfiguredSignal = { true }).source
        val declaration = source.bindingProvider.bind(InspectionBindingInput(stamp(), "train", route))
            .declarations.single()
        assertEquals(fixedBlockSignalDeclarationId(block.blockId), declaration.declarationId)
        assertEquals(2.0, declaration.triggerRouteDistanceMeters)
        assertEquals(9.0, declaration.minimumRequestedAuthorityEndRouteDistanceMeters)
        val publications = arrayListOf<TrainRestrictionSnapshot>()
        val runtime = InspectionRuntime(
            publisher = { publication ->
                publications += publication
                TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, publication.revision)
            }, sourceUnavailable = { _, _, failure -> throw failure })
        runtime.replaceSources(listOf(source))
        runtime.synchronizeRoute(stamp(), "train", route)
        runtime.createTrainInspection("train").update(stamp(), FrozenRouteBinding.from(route),
            5.0, 3.0, 20.0, 1.0, perception(route, headAbMeters = 5.0))
        assertEquals(emptyList<Any>(), publications.single().restrictions)
    }

    @Test
    fun `real entrance after an internal start block still supplies floor and red limit`() {
        val internal = FixedBlockDefinition("test:internal", 1L,
            listOf(FixedBlockTraversal(first, ViewDirection.AB, 2.0, 9.0)))
        val future = FixedBlockDefinition("test:future", 1L,
            listOf(FixedBlockTraversal(second, ViewDirection.AB, 2.0, 8.0)))
        val snapshot = snapshot(internal, future)
        val route = route(RouteSegmentSnapshot(first, ViewDirection.AB, 5.0, 10.0),
            RouteSegmentSnapshot(second, ViewDirection.AB, 0.0, 10.0))
        val source = FixedBlockInspectionSource(snapshot = { snapshot }, signalState = { id ->
            assertEquals(future.blockId, id, "internal start must not read the rear entry signal")
            PublishedSectionSignal(stamp(), SectionSignalState.Available(SectionSignalOutput(0.0, "custom-red")))
        }, hasConfiguredSignal = { true }, inspectionLeadTimeSeconds = { _, id ->
            assertEquals(future.blockId, id)
            12.0
        }).source
        val declaration = source.bindingProvider.bind(InspectionBindingInput(stamp(), "train", route))
            .declarations.single()
        assertEquals(fixedBlockSignalDeclarationId(future.blockId), declaration.declarationId)
        assertEquals(second, declaration.railId)
        assertEquals(7.0, declaration.triggerRouteDistanceMeters)
        assertEquals(12.0, declaration.inspectionLeadTimeSeconds)
        assertEquals(13.0, declaration.minimumRequestedAuthorityEndRouteDistanceMeters)
        val publications = arrayListOf<TrainRestrictionSnapshot>()
        val runtime = InspectionRuntime(
            publisher = { publication ->
                publications += publication
                TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, publication.revision)
            }, sourceUnavailable = { _, _, failure -> throw failure })
        runtime.replaceSources(listOf(source))
        runtime.synchronizeRoute(stamp(), "train", route)
        val binding = FrozenRouteBinding.from(route)
        assertEquals(RouteBoundary(1.0), runtime.adjustRequestedAuthorityBoundary("train", binding, RouteBoundary(1.0)))
        assertEquals(RouteBoundary(13.0), runtime.adjustRequestedAuthorityBoundary("train", binding, RouteBoundary(7.1)))
        runtime.createTrainInspection("train").update(stamp(), binding, 0.0, 10.0, 20.0, 1.0,
            perception(route, headAbMeters = 5.0, tailAbMeters = 5.0))
        val restriction = publications.single().restrictions.single() as RouteSpeedRestriction
        assertEquals(7.0, restriction.routeDistanceMeters)
        assertEquals(0.0, restriction.maximumSpeedMetersPerSecond)
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
        val trainInspection = runtime.createTrainInspection("train")

        trainInspection.update(stamp(1L), routeBinding, 0.0, 3.0, 20.0, 1.0, perception)
        val proceed = publications.last().restrictions.single() as RouteSpeedRestriction
        assertEquals(fixedBlockSignalDeclarationId(block.blockId), proceed.restrictionId)
        assertEquals(2.0, proceed.routeDistanceMeters, 0.0)
        assertEquals(17.5, proceed.maximumSpeedMetersPerSecond, 0.0)

        signal = PublishedSectionSignal(
            stamp(2L),
            SectionSignalState.Unavailable("configured rule failed")
        )
        runtime.markDirty(FIXED_BLOCK_SIGNAL_SOURCE_ID, listOf(fixedBlockSignalDeclarationId(block.blockId)))
        trainInspection.update(stamp(2L), routeBinding, 0.0, 3.0, 20.0, 1.0, perception)
        val unavailable = publications.last().restrictions.single() as RouteSpeedRestriction
        assertEquals(0.0, unavailable.maximumSpeedMetersPerSecond, 0.0)

        trainInspection.update(stamp(3L), routeBinding, 3.0, 3.0, 20.0, 1.0, perception)
        assertEquals(0, publications.last().restrictions.size)
    }

    @Test
    fun `configured signal without a published baseline applies zero speed at its entry`() {
        val block = FixedBlockDefinition(
            "test:signal-block",
            1L,
            listOf(FixedBlockTraversal(first, ViewDirection.AB, 2.0, 9.0))
        )
        val route = route(RouteSegmentSnapshot(first, ViewDirection.AB, 0.0, 10.0))
        val worldRuntime = SimpleSignalsWorldRuntime(worldId)
        worldRuntime.initializeDefinitions(
            topology,
            SimpleSignalsWorldDefinitions(
                worldId,
                1L,
                emptyList(),
                emptyList(),
                listOf(block),
                listOf(
                    FixedBlockSignalDefinition(
                        "test:signal",
                        1L,
                        block.blockId,
                        0.0,
                        ConfiguredSignalOutput(0.0, "private/occupied"),
                        listOf(
                            FixedBlockSignalClearLevel(
                                0.0,
                                ConfiguredSignalOutput(20.0, "private/open")
                            )
                        ),
                        emptyList()
                    )
                )
            )
        )
        worldRuntime.initializeSectionOccupancy()
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
        runtime.replaceSources(listOf(worldRuntime.fixedBlockInspectionSource))
        runtime.synchronizeRoute(stamp(), "train", route)

        runtime.createTrainInspection("train").update(
            stamp(),
            FrozenRouteBinding.from(route),
            0.0,
            3.0,
            20.0,
            1.0,
            perception(route)
        )

        val restriction = publications.single().restrictions.single() as RouteSpeedRestriction
        assertEquals(fixedBlockSignalDeclarationId(block.blockId), restriction.restrictionId)
        assertEquals(2.0, restriction.routeDistanceMeters, 0.0)
        assertEquals(0.0, restriction.maximumSpeedMetersPerSecond, 0.0)
    }

    @Test
    fun `block without a configured signal adds no speed restriction`() {
        val block = FixedBlockDefinition(
            "test:resource-only-block",
            1L,
            listOf(FixedBlockTraversal(first, ViewDirection.AB, 2.0, 9.0))
        )
        val route = route(RouteSegmentSnapshot(first, ViewDirection.AB, 0.0, 10.0))
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
        runtime.replaceSources(
            listOf(FixedBlockInspectionSource(snapshot = { snapshot(block) }).source)
        )
        runtime.synchronizeRoute(stamp(), "train", route)

        runtime.createTrainInspection("train").update(
            stamp(),
            FrozenRouteBinding.from(route),
            0.0,
            3.0,
            20.0,
            1.0,
            perception(route)
        )

        assertEquals(0, publications.single().restrictions.size)
    }

    @Test
    fun `ended route drops its cached lead time and the next binding uses the new definition`() {
        val block = FixedBlockDefinition(
            "test:signal-block",
            1L,
            listOf(FixedBlockTraversal(first, ViewDirection.AB, 2.0, 9.0))
        )
        val route = route(RouteSegmentSnapshot(first, ViewDirection.AB, 0.0, 10.0))
        val worldRuntime = SimpleSignalsWorldRuntime(worldId)
        assertEquals(
            SimpleSignalsDefinitionPublicationStatus.PUBLISHED,
            worldRuntime.initializeDefinitions(
                topology,
                definitions(block, signal(block.blockId, revision = 1L, leadTimeSeconds = 300.0), 1L)
            ).status
        )
        val inspectionRuntime = InspectionRuntime(
            publisher = { publication ->
                TrainRestrictionPublicationResult(
                    TrainRestrictionPublicationStatus.ACCEPTED,
                    publication.revision
                )
            },
            sourceUnavailable = { _, _, failure -> throw failure }
        )
        inspectionRuntime.replaceSources(listOf(worldRuntime.fixedBlockInspectionSource))

        assertEquals(
            300.0,
            inspectionRuntime.synchronizeRoute(stamp(), "train", route),
            0.0
        )
        inspectionRuntime.retainTrains(emptySet())
        assertEquals(0.0, inspectionRuntime.maximumDeclaredLeadTimeSeconds("train"), 0.0)

        assertEquals(
            SimpleSignalsDefinitionPublicationStatus.PUBLISHED,
            worldRuntime.initializeDefinitions(
                topology,
                definitions(block, signal(block.blockId, revision = 2L, leadTimeSeconds = 45.0), 2L)
            ).status
        )

        assertEquals(
            45.0,
            inspectionRuntime.synchronizeRoute(stamp(), "train", route),
            0.0
        )
        assertEquals(45.0, inspectionRuntime.maximumDeclaredLeadTimeSeconds("train"), 0.0)
    }

    private fun definitions(
        block: FixedBlockDefinition,
        signal: FixedBlockSignalDefinition,
        revision: Long
    ) = SimpleSignalsWorldDefinitions(
        worldId,
        revision,
        emptyList(),
        emptyList(),
        listOf(block),
        listOf(signal)
    )

    private fun signal(
        blockId: String,
        revision: Long,
        leadTimeSeconds: Double
    ) = FixedBlockSignalDefinition(
        "test:signal",
        revision,
        blockId,
        leadTimeSeconds,
        ConfiguredSignalOutput(0.0, "private/occupied"),
        listOf(
            FixedBlockSignalClearLevel(
                0.0,
                ConfiguredSignalOutput(20.0, "private/open")
            )
        )
    )

    private fun snapshot(vararg blocks: FixedBlockDefinition): FixedBlockReadSnapshot =
        FixedBlockCatalog().also { catalog ->
            assertEquals(FixedBlockCatalogStatus.PUBLISHED,
                catalog.publish(topology, 1L, blocks.asList()).status)
        }.snapshot(topology)

    private fun route(vararg segments: RouteSegmentSnapshot) = RouteSnapshot(
        "test:route",
        1L,
        topology.revision,
        segments.asList()
    )

    private fun stamp(epoch: Long = 1L) =
        RailwayStateStamp(worldId.toString(), epoch, topology.revision)

    private fun perception(route: RouteSnapshot, headAbMeters: Double = 0.0, tailAbMeters: Double = 0.0) = PerceptionSnapshot(
        1L,
        TrainSnapshot(
            "train",
            1L,
            0.0,
            false,
            1,
            RailPositionSnapshot(first, ViewDirection.AB, headAbMeters),
            RailPositionSnapshot(first, ViewDirection.AB, tailAbMeters)
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
