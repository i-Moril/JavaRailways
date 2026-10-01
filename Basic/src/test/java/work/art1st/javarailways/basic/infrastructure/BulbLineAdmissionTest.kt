package work.art1st.javarailways.basic.infrastructure

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.*
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.*
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.physics.train.SweptRailSpanSnapshot
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailView
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.createRailForTest
import java.util.UUID

/** Real Core turnout topology and live preparation, with coherent immutable observation inputs. */
class BulbLineAdmissionTest {
    @Test
    fun `long request splits and second turnout preparation waits for first tail clearance`() {
        val world = createTestWorld()
        try {
            lateinit var stem: CoreRail
            lateinit var outbound: CoreRail
            lateinit var loop: CoreRail
            lateinit var inbound: CoreRail
            fun curve(a: Vector3d, b: Vector3d): PrimeCurve {
                val tangent = Vector3d(b).sub(a).normalize()
                return PrimeCurve.fromPoints(listOf(
                    Curve.RawPoint(a, Vector3d(tangent), Vector3d(0.0, 1.0, 0.0), 0.0),
                    Curve.RawPoint(b, Vector3d(tangent), Vector3d(0.0, 1.0, 0.0), a.distance(b))
                ))
            }
            world.railTopologyTransaction {
                stem = world.createRailForTest(curve(Vector3d(-20.0, 0.0, 0.0), Vector3d()))
                outbound = world.createRailForTest(curve(Vector3d(), Vector3d(20.0, 0.0, 0.0)))
                loop = world.createRailForTest(curve(Vector3d(20.0, 0.0, 0.0), Vector3d(20.0, 0.0, 20.0)))
                inbound = world.createRailForTest(curve(Vector3d(20.0, 0.0, 20.0), Vector3d()))
                RailView.connect(stem.viewAB, outbound.viewBA)
                RailView.connect(outbound.viewAB, loop.viewBA)
                RailView.connect(loop.viewAB, inbound.viewBA)
                RailView.connect(inbound.viewAB, stem.viewAB)
            }
            val topology = world.topologyState
            val turnout = TurnoutResourceIdentity.fromPorts(topology.nodes.values.filterIsInstance<TopologyNode.Turnout>().single().ports)
            val route = RouteSnapshot("test:bulb", 1L, topology.revision, listOf(
                RouteSegmentSnapshot(stem.id, ViewDirection.AB, 0.0, stem.curve.totalArcLength),
                RouteSegmentSnapshot(outbound.id, ViewDirection.AB, 0.0, outbound.curve.totalArcLength),
                RouteSegmentSnapshot(loop.id, ViewDirection.AB, 0.0, loop.curve.totalArcLength),
                RouteSegmentSnapshot(inbound.id, ViewDirection.AB, 0.0, inbound.curve.totalArcLength),
                RouteSegmentSnapshot(stem.id, ViewDirection.BA, 0.0, stem.curve.totalArcLength)
            ))
            var epoch = 0L
            fun frame(rail: CoreRail, facing: ViewDirection, tail: Double, head: Double): RailwayObservation {
                epoch++
                return RailwayObservation.fromCore("bulb", RailwayWorldFrameSnapshot.create(epoch, topology.revision,
                    listOf(TrainFrameSnapshot.create("train", "train", epoch, epoch, 0.0, false, false,
                        emptyList(), RailPositionSnapshot(rail.id, facing, head), RailPositionSnapshot(rail.id, facing, tail),
                        listOf(RailInterval(rail.id, minOf(tail, head), maxOf(tail, head))),
                        emptyList(), listOf(SweptRailSpanSnapshot(rail.id, minOf(tail, head), maxOf(tail, head))),
                        emptyList(), emptyList())), ActiveRailContinuationsSnapshot.empty(topology.revision)))
            }
            var observation = frame(stem, ViewDirection.AB, 0.0, 1.0)
            val registry = InfrastructureCoordinationRegistry({ topology.revision }, { true })
            val catalog = RouteBoundResourceCatalog { topology }
            catalog.publish(topology, PhysicalTopologyResources.routeBoundBindings(topology))
            val live = LiveInfrastructurePreparationActuator(world) { _, _ -> null }
            var starts = 0
            var secondReady = false
            var secondRequirements: List<InfrastructurePreparationRequirement> = emptyList()
            val engine = RouteResourceAdmissionEngine("bulb", registry, Any(), { topology }, { observation }, catalog,
                { _, _ -> null }, InfrastructurePreparationActuator { requirements ->
                    starts++
                    if (starts == 1) live.start(requirements) else {
                        secondRequirements = requirements.toList()
                        InfrastructurePreparationStart.Accepted(object : ResourcePreparationTicket {
                            private var actual: ResourcePreparationTicket? = null
                            override fun snapshot(): ResourcePreparationSnapshot {
                                if (!secondReady) return ResourcePreparationSnapshot(ResourcePreparationState.PREPARING)
                                if (actual == null) actual = (live.start(secondRequirements) as InfrastructurePreparationStart.Accepted).ticket
                                return actual!!.snapshot()
                            }
                            override fun abort() = Unit
                        })
                    }
                }, { _, failure -> throw failure })
            val requester = Any()
            val owner = InfrastructureOwner("test:bulb/train", 1L)
            fun intent(end: Double) = RouteResourceIntent("test:request", owner, "train", observation.stamp, route, RouteBoundary(end))
            val tooLong = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                engine.request(requester, intent(route.totalLengthMeters)))
            assertEquals(RouteAdmissionFailureCode.REQUEST_RANGE_TOO_LONG, tooLong.failure.code)
            assertTrue(registry.claimSnapshots().isEmpty())
            assertEquals(0, starts)
            val ceiling = requireNotNull(tooLong.failure.retryAtOrBefore)
            val first = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
                engine.request(requester, intent(ceiling.distanceFromRouteStartMeters)))
            assertSame(outbound, stem.viewAB.nextActiveRail?.rail)
            val stillHeld = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                engine.request(requester, intent(route.totalLengthMeters)))
            assertEquals(RouteAdmissionFailureCode.REQUEST_BUSY, stillHeld.failure.code)
            assertEquals(1, starts)
            assertSame(outbound, stem.viewAB.nextActiveRail?.rail)
            assertTrue(registry.claimSnapshots().single().footprint.resources.contains(turnout))

            // A competing route cannot acquire the turnout while the first visit still holds it.
            val competitor = registry.beginAcquireClaim(Any(), InfrastructureClaimRequest(
                topology.revision, InfrastructureOwner("test:competitor", 1L),
                work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint(listOf(turnout))))
            assertFalse(competitor.result.successful)
            observation = frame(outbound, ViewDirection.AB, 1.0, 2.0)
            engine.consumeObservation(observation)
            assertFalse(registry.claimSnapshots().single().footprint.resources.contains(turnout))
            val second = assertInstanceOf(RouteAdmissionResult.Pending::class.java,
                engine.request(requester, intent(route.totalLengthMeters)))
            assertEquals(first.grant.committedAuthority, second.committedGrant?.committedAuthority)
            assertEquals(2, starts)
            assertSame(outbound, stem.viewAB.nextActiveRail?.rail)
            assertTrue(registry.claimSnapshots().single().footprint.resources.contains(turnout))
            secondReady = true
            val ready = assertInstanceOf(RouteAdmissionResult.Granted::class.java, engine.progress(requester, second.handle))
            assertSame(first.grant.grantId, ready.grant.grantId)
            assertEquals(RouteBoundary(route.totalLengthMeters), ready.grant.committedAuthority)
            assertSame(inbound, stem.viewAB.nextActiveRail?.rail)
            observation = frame(loop, ViewDirection.AB, 1.0, 2.0)
            engine.consumeObservation(observation)
            observation = frame(inbound, ViewDirection.AB, 1.0, 2.0)
            engine.consumeObservation(observation)
            observation = frame(stem, ViewDirection.BA, 19.0, 18.0)
            engine.consumeObservation(observation)
            assertFalse(registry.claimSnapshots().single().footprint.resources.contains(turnout))
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }
}
