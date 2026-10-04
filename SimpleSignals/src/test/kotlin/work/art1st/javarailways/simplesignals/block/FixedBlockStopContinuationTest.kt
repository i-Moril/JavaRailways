package work.art1st.javarailways.simplesignals.block

import work.art1st.javarailways.simplesignals.testing.fixedBlockPolicy

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.basic.api.route.RouteGrantReversalResult
import work.art1st.javarailways.basic.api.route.ContributedRouteResource
import work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor
import work.art1st.javarailways.basic.api.route.ResourcePreparationSnapshot
import work.art1st.javarailways.basic.api.route.ResourcePreparationState
import work.art1st.javarailways.basic.api.route.ResourcePreparationTicket
import work.art1st.javarailways.basic.infrastructure.InfrastructureCoordinationRegistry
import work.art1st.javarailways.basic.infrastructure.RouteResourceAdmissionRuntime
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createLoopRailFromSegments
import work.art1st.javarailways.core.physics.train.getViewsInOrder
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import java.lang.reflect.Proxy
import java.util.UUID

/** Real Basic claims and cursor release, using the concrete plugin contributor. */
class FixedBlockStopContinuationTest {
    @Test
    fun `active single reverse refusal makes no new claim and known disable preserves held token until tail release`() {
        BidirectionalFixture(false, prepareDevice = true).use { fixture ->
            val requester = Any()
            val reverse = fixture.route("test:reverse", 1L,
                listOf(RouteSegmentSnapshot(fixture.first, ViewDirection.BA, 5.0, 40.0)))
            fixture.observe(1L, fixture.train("reverse", 1L, fixture.first, 95.0, 90.0, ViewDirection.BA))
            val denied = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                fixture.runtime.request(requester, fixture.intent("reverse", reverse, 35.0)))
            assertEquals(RouteAdmissionFailureCode.INVALID_CONTRIBUTION, denied.failure.code)
            assertTrue(fixture.registry.claimSnapshots().isEmpty())
            assertTrue(fixture.runtime.activeAdmissions().isEmpty(), "refusal cannot leave a pending resource request")
            assertEquals(0, fixture.preparationResolutions)
            assertEquals(0, fixture.preparationStarts, "refusal precedes every preparation side effect")

            val forward = fixture.route("test:forward", 1L, listOf(
                RouteSegmentSnapshot(fixture.first, ViewDirection.AB, 5.0, 100.0),
                RouteSegmentSnapshot(fixture.second, ViewDirection.AB, 0.0, 100.0)))
            fixture.observe(2L, fixture.train("forward", 2L, fixture.first, 5.0, 10.0))
            val grant = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
                fixture.runtime.request(requester, fixture.intent("forward", forward, 80.0))).grant
            assertTrue(fixture.token in fixture.held())
            assertEquals(1, fixture.preparationStarts, "the same fixture actually prepares an accepted request")
            fixture.directions.put(fixture.first, null) // isolated policy/lifecycle evidence; production edits retain their guard
            val extension = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
                fixture.runtime.request(requester, fixture.intent("forward", forward, 115.0))).grant
            assertSame(grant.grantId, extension.grantId)
            assertTrue(fixture.token in fixture.held(), "the contributor omits new control but cannot erase a held token")
            fixture.observe(3L, fixture.train("forward", 3L, fixture.first, 79.0, 89.0))
            assertTrue(fixture.token in fixture.held())
            fixture.observe(4L, fixture.train("forward", 4L, fixture.second, 5.0, 15.0))
            assertFalse(fixture.token in fixture.held())
        }
    }

    @Test
    fun `opposing disjoint prefixes conflict on one token atomically and regrant after tail clear`() {
        BidirectionalFixture().use { fixture ->
            val forward = fixture.route("test:forward", 1L, listOf(
                RouteSegmentSnapshot(fixture.first, ViewDirection.AB, 5.0, 100.0),
                RouteSegmentSnapshot(fixture.second, ViewDirection.AB, 0.0, 100.0)))
            val reverse = fixture.route("test:reverse", 1L,
                listOf(RouteSegmentSnapshot(fixture.first, ViewDirection.BA, 5.0, 40.0)))
            fixture.observe(1L, fixture.train("forward", 1L, fixture.first, 5.0, 10.0),
                fixture.train("reverse", 1L, fixture.first, 95.0, 90.0, ViewDirection.BA))
            val firstRequester = Any()
            val oppositeRequester = Any()
            val grant = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
                fixture.runtime.request(firstRequester, fixture.intent("forward", forward, 40.0))).grant
            val before = fixture.registry.claimSnapshots().single().footprint
            assertTrue(fixture.token in before.resources)
            val denied = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                fixture.runtime.request(oppositeRequester, fixture.intent("reverse", reverse, 35.0)))
            assertEquals(RouteAdmissionFailureCode.RESOURCE_CONFLICT, denied.failure.code)
            assertEquals(listOf(before), fixture.registry.claimSnapshots().map { it.footprint },
                "the opposing request must not retain any partial rail claim")
            fixture.observe(2L, fixture.train("forward", 2L, fixture.first, 5.0, 10.0))
            val extended = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
                fixture.runtime.request(firstRequester, fixture.intent("forward", forward, 115.0))).grant
            assertSame(grant.grantId, extended.grantId)
            fixture.observe(3L, fixture.train("forward", 3L, fixture.first, 79.0, 89.0))
            assertTrue(fixture.token in fixture.held(), "head exit is insufficient")
            fixture.observe(4L, fixture.train("forward", 4L, fixture.second, 5.0, 15.0),
                fixture.train("reverse", 4L, fixture.first, 95.0, 90.0, ViewDirection.BA))
            assertFalse(fixture.token in fixture.held())
            assertInstanceOf(RouteAdmissionResult.Granted::class.java,
                fixture.runtime.request(oppositeRequester, fixture.intent("reverse", reverse, 35.0)))
            assertEquals(1, fixture.held().count { it == fixture.token })
        }
    }

    @Test
    fun `stopped reversal retains shared token and releases it using the new route tail`() {
        BidirectionalFixture().use { fixture ->
            val forward = fixture.route("test:return", 1L,
                listOf(RouteSegmentSnapshot(fixture.first, ViewDirection.AB, 10.0, 60.0)))
            fixture.observe(1L, fixture.train("train", 1L, fixture.first, 10.0, 20.0))
            val requester = Any()
            val grant = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
                fixture.runtime.request(requester, fixture.intent("train", forward, 50.0))).grant
            fixture.observe(2L, fixture.train("train", 2L, fixture.first, 40.0, 50.0))
            assertTrue(fixture.token in fixture.held())
            assertFalse(fixture.runtime.endGrant(requester, grant.grantId))
            val retained = fixture.registry.claimSnapshots().single().footprint
            assertInstanceOf(RouteGrantReversalResult.Accepted::class.java,
                fixture.runtime.suspendReleaseForReversal(requester, grant.grantId))
            fixture.observe(3L, fixture.train("train", 3L, fixture.first, 50.0, 40.0, ViewDirection.BA, true))
            assertEquals(retained, fixture.registry.claimSnapshots().single().footprint)
            val reverse = fixture.route("test:return", 2L,
                listOf(RouteSegmentSnapshot(fixture.first, ViewDirection.BA, 50.0, 100.0)))
            val rebased = assertInstanceOf(RouteGrantReversalResult.Accepted::class.java,
                fixture.runtime.rebaseAfterReversal(requester, fixture.intent("train", reverse, 10.0))).grant
            assertSame(grant.grantId, rebased.grantId)
            assertEquals(retained, fixture.registry.claimSnapshots().single().footprint,
                "rebase cannot drop or reacquire the token")
            val extension = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
                fixture.runtime.request(requester, fixture.intent("train", reverse, 50.0))).grant
            assertSame(grant.grantId, extension.grantId)
            assertEquals(1, fixture.held().count { it == fixture.token })
            fixture.observe(4L, fixture.train("train", 4L, fixture.first, 21.0, 11.0, ViewDirection.BA, true))
            assertTrue(fixture.token in fixture.held())
            fixture.observe(5L, fixture.train("train", 5L, fixture.first, 19.0, 9.0, ViewDirection.BA, true))
            assertFalse(fixture.token in fixture.held(), "the reversed tail now clears the original entry")
        }
    }

    private class BidirectionalFixture(bidirectional: Boolean = true, prepareDevice: Boolean = false) : AutoCloseable {
        val world = createTestWorld()
        private val firstRail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, 2))
        private val views = getViewsInOrder(firstRail, false)
        val first = views[0].rail.id
        val second = views[1].rail.id
        private val topology = world.topologyState
        private val worldId = UUID.randomUUID()
        private val plugin = Plugin::class.java.cast(Proxy.newProxyInstance(Plugin::class.java.classLoader,
            arrayOf(Plugin::class.java)) { proxy, method, arguments ->
            when (method.name) {
                "isEnabled" -> true
                "getName", "toString" -> "SimpleSignals"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.singleOrNull()
                else -> throw UnsupportedOperationException(method.name)
            }
        })
        private val block = FixedBlockDefinition("test:two-way", 1L,
            listOf(FixedBlockTraversal(first, ViewDirection.AB, 20.0, 80.0)), bidirectional)
        private val snapshot = FixedBlockCatalog().also {
            assertEquals(FixedBlockCatalogStatus.PUBLISHED, it.publish(topology, 1L, listOf(block)).status)
        }.snapshot(topology)
        val directions = work.art1st.javarailways.simpleato.routing.OperatingDirectionStore().also {
            if (!bidirectional) it.put(first, ViewDirection.AB)
        }
        private val policy = FixedBlockRouteResourcePolicy(plugin, { FixedBlockDirectionPolicy(directions::snapshot) }) { snapshot }
        val token = StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, block.blockId)
        private val device = StableInfrastructureResource("test:device", "test:prepared-device")
        private val preparation = ResourcePreparationDescriptor("test:prepare-device", emptyMap())
        var preparationResolutions = 0
            private set
        var preparationStarts = 0
            private set
        val registry = InfrastructureCoordinationRegistry({ topology.revision }, { resource ->
            resource == token || resource == device || resource is RailIntervalResource && resource.railId in setOf(first, second)
        })
        private var observation = RailwayObservation.fromCore(worldId.toString(),
            RailwayWorldFrameSnapshot.create(0L, topology.revision, emptyList(),
                ActiveRailContinuationsSnapshot.empty(topology.revision)))
        val runtime = RouteResourceAdmissionRuntime.create(worldId.toString(), world, registry, Any(),
            { observation }, { intent ->
                val deviceContribution = if (prepareDevice) listOf(plugin to
                    ContributedRouteResource(device, intent.desiredAuthority, preparation)) else emptyList()
                deviceContribution + policy.contribute(worldId, intent).map { plugin to it }
            },
            { _, contribution -> if (contribution.resource == device) null else
                policy.validateRouteContribution(plugin, worldId, contribution.resource.resourceId, contribution.preparation)?.let {
                work.art1st.javarailways.basic.api.route.RouteAdmissionFailure(it, "invalid")
            } }, { resource, descriptor ->
                assertEquals(device, resource)
                assertEquals(preparation, descriptor)
                preparationResolutions++
                val start: () -> ResourcePreparationTicket = {
                    preparationStarts++
                    object : ResourcePreparationTicket {
                        override fun snapshot() = ResourcePreparationSnapshot(ResourcePreparationState.READY)
                        override fun abort() = Unit
                    }
                }
                start
            }) { _, failure -> throw failure }

        fun route(id: String, revision: Long, segments: List<RouteSegmentSnapshot>) =
            RouteSnapshot(id, revision, topology.revision, segments)
        fun intent(train: String, route: RouteSnapshot, end: Double) = RouteResourceIntent("test:request/$train",
            InfrastructureOwner("test:owner/$train", 1L), train, observation.stamp, route, RouteBoundary(end))
        fun held() = registry.claimSnapshots().flatMap { it.footprint.resources }.toSet()
        fun train(id: String, epoch: Long, rail: UUID, tail: Double, head: Double,
            facing: ViewDirection = ViewDirection.AB, reversed: Boolean = false) = TrainFrameSnapshot.create(
            id, id, epoch, epoch, 0.0, false, reversed, emptyList(),
            RailPositionSnapshot(rail, facing, head), RailPositionSnapshot(rail, facing, tail),
            listOf(RailInterval(rail, minOf(tail, head), maxOf(tail, head))),
            emptyList(), emptyList(), emptyList(), emptyList())
        fun observe(epoch: Long, vararg trains: TrainFrameSnapshot) {
            observation = RailwayObservation.fromCore(worldId.toString(), RailwayWorldFrameSnapshot.create(epoch,
                topology.revision, trains.toList(), ActiveRailContinuationsSnapshot.empty(topology.revision)))
            runtime.consumeObservation(observation)
        }
        override fun close() {
            runtime.close()
            world.physicsCoordinator.close()
            (world.tickManager as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `degree two loop extends same token to second visit and releases after last tail clearance`() {
        val world = createTestWorld()
        val firstRail = createLoopRailFromSegments(world, PrimeCurveGenerator.buildCircle(20.0, 4))
        val views = getViewsInOrder(firstRail, true)
        assertEquals(4, views.size)
        val topology = world.topologyState
        val worldId = UUID.randomUUID()
        val plugin = Plugin::class.java.cast(Proxy.newProxyInstance(Plugin::class.java.classLoader,
            arrayOf(Plugin::class.java)) { proxy, method, arguments ->
            when (method.name) {
                "isEnabled" -> true
                "getName", "toString" -> "SimpleSignals"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.singleOrNull()
                else -> throw UnsupportedOperationException(method.name)
            }
        })
        val block = FixedBlockDefinition("test:loop-block", 1L,
            listOf(FixedBlockTraversal(firstRail.id, ViewDirection.AB, 2.0, 8.0)))
        val snapshot = FixedBlockCatalog().also {
            assertEquals(FixedBlockCatalogStatus.PUBLISHED, it.publish(topology, 1L, listOf(block)).status)
        }.snapshot(topology)
        val policy = fixedBlockPolicy(plugin) { snapshot }
        val token = StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, block.blockId)
        val railIds = views.map { it.rail.id }.toSet()
        val registry = InfrastructureCoordinationRegistry({ topology.revision }, { resource ->
            resource == token || resource is RailIntervalResource && resource.railId in railIds
        })
        fun observe(epoch: Long, railIndex: Int, tail: Double) = RailwayObservation.fromCore(worldId.toString(),
            RailwayWorldFrameSnapshot.create(epoch, topology.revision, listOf(TrainFrameSnapshot.create(
                "train", "Train", epoch, epoch, 0.0, false, false, emptyList(),
                RailPositionSnapshot(views[railIndex].rail.id, ViewDirection.AB, tail + 1.0),
                RailPositionSnapshot(views[railIndex].rail.id, ViewDirection.AB, tail),
                listOf(RailInterval(views[railIndex].rail.id, tail, tail + 1.0)),
                emptyList(), emptyList(), emptyList(), emptyList()
            )), ActiveRailContinuationsSnapshot.empty(topology.revision)))
        var observation = observe(1L, 0, 0.0)
        val runtime = RouteResourceAdmissionRuntime.create(worldId.toString(), world, registry, Any(),
            { observation }, { intent -> policy.contribute(worldId, intent).map { plugin to it } },
            { _, contribution -> policy.validateRouteContribution(plugin, worldId, contribution.resource.resourceId,
                contribution.preparation)?.let { work.art1st.javarailways.basic.api.route.RouteAdmissionFailure(it, "invalid") } },
            { _, _ -> null }) { _, failure -> throw failure }
        val requester = Any()
        val segments = (views + views.first()).map {
            RouteSegmentSnapshot(it.rail.id, ViewDirection.AB, 0.0, it.curve.totalArcLength)
        }
        val route = RouteSnapshot("test:loop-route", 1L, topology.revision, segments)
        val circumference = views.sumOf { it.curve.totalArcLength }
        fun intent(authority: Double) = RouteResourceIntent("test:request", InfrastructureOwner("test:owner", 1L),
            "train", observation.stamp, route, RouteBoundary(authority))
        fun held() = registry.claimSnapshots().flatMap { it.footprint.resources }.toSet()
        fun advance(epoch: Long, railIndex: Int, tail: Double) {
            observation = observe(epoch, railIndex, tail)
            runtime.consumeObservation(observation)
        }
        try {
            val first = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
                runtime.request(requester, intent(8.0))).grant
            assertTrue(token in held())
            val extensionIntent = intent(circumference + 10.0)
            assertEquals(circumference + 8.0, policy.contribute(worldId, extensionIntent).single()
                .releaseAfterTailPasses.distanceFromRouteStartMeters, 1e-6)
            val extended = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
                runtime.request(requester, extensionIntent)).grant
            assertSame(first.grantId, extended.grantId)
            assertEquals(1, held().count { it == token })

            advance(2L, 0, 9.0)
            assertTrue(token in held(), "the first traversal cannot release the token reserved for the second")
            for (index in 1 until views.size) advance(index + 2L, index, 5.0)
            advance(6L, 0, 7.0)
            assertTrue(token in held(), "the second head has reached the exit but its tail has not")
            advance(7L, 0, 9.0)
            assertFalse(token in held(), "the shared token releases after the final traversal's tail clears")
        } finally {
            runtime.close()
            world.physicsCoordinator.close()
            (world.tickManager as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `stop inside block retains token and forward continuation releases only after tail clears exit`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        val topology = world.topologyState
        val worldId = UUID.randomUUID()
        val plugin = Plugin::class.java.cast(Proxy.newProxyInstance(Plugin::class.java.classLoader,
            arrayOf(Plugin::class.java)) { proxy, method, arguments ->
            when (method.name) {
                "isEnabled" -> true
                "getName", "toString" -> "SimpleSignals"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.singleOrNull()
                else -> throw UnsupportedOperationException(method.name)
            }
        })
        val block = FixedBlockDefinition("test:station-block", 1L,
            listOf(FixedBlockTraversal(rail.id, ViewDirection.AB, 20.0, 80.0)))
        val snapshot = FixedBlockCatalog().also {
            assertEquals(FixedBlockCatalogStatus.PUBLISHED, it.publish(topology, 1L, listOf(block)).status)
        }.snapshot(topology)
        val policy = fixedBlockPolicy(plugin) { snapshot }
        val token = StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, block.blockId)
        val registry = InfrastructureCoordinationRegistry({ topology.revision }, { resource ->
            resource == token || resource is RailIntervalResource && resource.railId == rail.id
        })
        fun observe(epoch: Long, tail: Double, head: Double) = RailwayObservation.fromCore(worldId.toString(),
            RailwayWorldFrameSnapshot.create(epoch, topology.revision, listOf(TrainFrameSnapshot.create(
                "train", "Train", epoch, epoch, 0.0, false, false, emptyList(),
                RailPositionSnapshot(rail.id, ViewDirection.AB, head),
                RailPositionSnapshot(rail.id, ViewDirection.AB, tail),
                listOf(RailInterval(rail.id, tail, head)), emptyList(), emptyList(), emptyList(), emptyList()
            )), ActiveRailContinuationsSnapshot.empty(topology.revision)))
        var observation = observe(1L, 10.0, 20.0)
        val runtime = RouteResourceAdmissionRuntime.create(worldId.toString(), world, registry, Any(),
            { observation }, { intent -> policy.contribute(worldId, intent).map { plugin to it } },
            { _, contribution -> policy.validateRouteContribution(plugin, worldId, contribution.resource.resourceId,
                contribution.preparation)?.let { work.art1st.javarailways.basic.api.route.RouteAdmissionFailure(it, "invalid") } },
            { _, _ -> null }) { _, failure -> throw failure }
        val requester = Any()
        fun intent(route: RouteSnapshot, authority: Double) = RouteResourceIntent("test:request",
            InfrastructureOwner("test:owner", 1L), "train", observation.stamp, route, RouteBoundary(authority))
        fun held() = registry.claimSnapshots().single().footprint.resources.toSet()
        try {
            val firstLeg = RouteSnapshot("test:route", 1L, topology.revision,
                listOf(RouteSegmentSnapshot(rail.id, ViewDirection.AB, 10.0, 50.0)))
            val first = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
                runtime.request(requester, intent(firstLeg, 40.0))).grant
            assertTrue(token in held())
            observation = observe(2L, 40.0, 50.0)
            runtime.consumeObservation(observation)
            assertTrue(token in held(), "stopping or task end cannot release the occupied block")
            assertFalse(runtime.endGrant(requester, first.grantId))

            val continued = RouteSnapshot("test:route", 2L, topology.revision,
                firstLeg.segments + RouteSegmentSnapshot(rail.id, ViewDirection.AB, 50.0, 100.0))
            val next = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
                runtime.request(requester, intent(continued, 80.0))).grant
            assertSame(first.grantId, next.grantId)
            observation = observe(3L, 79.0, 89.0)
            runtime.consumeObservation(observation)
            assertTrue(token in held(), "head past block exit is not tail clearance")
            observation = observe(4L, 81.0, 89.0)
            runtime.consumeObservation(observation)
            assertFalse(token in held())

            // The next prefix still includes the old block; Basic must not reacquire it behind the tail.
            val farther = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
                runtime.request(requester, intent(continued, 90.0))).grant
            assertSame(first.grantId, farther.grantId)
            assertFalse(token in held())
        } finally {
            runtime.close()
            world.physicsCoordinator.close()
            (world.tickManager as? AutoCloseable)?.close()
        }
    }
}
