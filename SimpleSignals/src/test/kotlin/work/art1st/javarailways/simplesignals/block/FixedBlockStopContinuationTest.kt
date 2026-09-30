package work.art1st.javarailways.simplesignals.block

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
import work.art1st.javarailways.basic.infrastructure.InfrastructureCoordinationRegistry
import work.art1st.javarailways.basic.infrastructure.RouteResourceAdmissionRuntime
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import java.lang.reflect.Proxy
import java.util.UUID

/** Real Basic claims and cursor release, using the concrete plugin contributor. */
class FixedBlockStopContinuationTest {
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
        val policy = FixedBlockRouteResourcePolicy(plugin) { snapshot }
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
