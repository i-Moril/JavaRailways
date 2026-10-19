package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

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
import work.art1st.javarailways.basic.infrastructure.admission.InfrastructurePreparationActuator
import work.art1st.javarailways.basic.infrastructure.admission.InfrastructurePreparationRequirement
import work.art1st.javarailways.basic.infrastructure.admission.InfrastructurePreparationStart
import work.art1st.javarailways.basic.infrastructure.admission.LiveInfrastructurePreparationActuator
import work.art1st.javarailways.basic.infrastructure.admission.RouteResourceAdmissionEngine

/** Real Core turnout topology and live preparation, with coherent immutable observation inputs. */
class BulbLineAdmissionTest {
    @Test
    fun `rolling suffix grants useful prefix while first turnout is held then prepares second use`() {
        exerciseRollingBulb(sideMeters = 50.0, canClearFirstUse = true)
    }

    @Test
    fun `short loop keeps turnout held when capped authority cannot clear the train tail`() {
        exerciseRollingBulb(sideMeters = 5.0, canClearFirstUse = false)
    }

    private fun exerciseRollingBulb(sideMeters: Double, canClearFirstUse: Boolean) {
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
            IsolatedSimulationThreadMarker.run { world.railTopologyTransaction {
                stem = world.createRailForTest(curve(Vector3d(-340.0, 0.0, 0.0), Vector3d()))
                outbound = world.createRailForTest(curve(Vector3d(), Vector3d(sideMeters, 0.0, 0.0)))
                loop = world.createRailForTest(curve(Vector3d(sideMeters, 0.0, 0.0), Vector3d(sideMeters, 0.0, sideMeters)))
                inbound = world.createRailForTest(curve(Vector3d(sideMeters, 0.0, sideMeters), Vector3d()))
                RailView.connect(stem.viewAB, outbound.viewBA)
                RailView.connect(outbound.viewAB, loop.viewBA)
                RailView.connect(loop.viewAB, inbound.viewBA)
                RailView.connect(inbound.viewAB, stem.viewAB)
            } }
            val topology = world.topologyState
            val turnout = TurnoutResourceIdentity.fromPorts(topology.nodes.values.filterIsInstance<TopologyNode.Turnout>().single().ports)
            val route = RouteSnapshot("test:bulb", 1L, topology.revision, listOf(
                RouteSegmentSnapshot(stem.id, ViewDirection.AB, 0.0, stem.curve.totalArcLength),
                RouteSegmentSnapshot(outbound.id, ViewDirection.AB, 0.0, outbound.curve.totalArcLength),
                RouteSegmentSnapshot(loop.id, ViewDirection.AB, 0.0, loop.curve.totalArcLength),
                RouteSegmentSnapshot(inbound.id, ViewDirection.AB, 0.0, inbound.curve.totalArcLength),
                RouteSegmentSnapshot(stem.id, ViewDirection.BA, 0.0, stem.curve.totalArcLength)
            ))
            val rails = listOf(stem, outbound, loop, inbound).associateBy { it.id }
            var epoch = 0L
            fun frame(tail: Double, head: Double): RailwayObservation {
                fun canonical(segment: RouteSegmentSnapshot, local: Double) = when (segment.facing) {
                    ViewDirection.AB -> local
                    ViewDirection.BA -> rails.getValue(segment.railId).curve.totalArcLength - local
                }
                fun position(meters: Double): RailPositionSnapshot {
                    var start = 0.0
                    for (segment in route.segments) {
                        if (meters <= start + segment.lengthMeters) return RailPositionSnapshot(
                            segment.railId, segment.facing, canonical(segment, meters - start))
                        start += segment.lengthMeters
                    }
                    error("position outside frozen bulb route")
                }
                var start = 0.0
                val intervals = route.segments.mapNotNull { segment ->
                    val end = start + segment.lengthMeters
                    val first = maxOf(tail, start)
                    val last = minOf(head, end)
                    val interval = if (last > first) {
                        val a = canonical(segment, first - start)
                        val b = canonical(segment, last - start)
                        RailInterval(segment.railId, minOf(a, b), maxOf(a, b))
                    } else null
                    start = end
                    interval
                }
                epoch++
                return RailwayObservation.fromCore("bulb", RailwayWorldFrameSnapshot.create(epoch, topology.revision,
                    listOf(TrainFrameSnapshot.create("train", "train", epoch, epoch, 0.0, false, false,
                        emptyList(), position(head), position(tail), intervals,
                        emptyList(), intervals.map {
                            SweptRailSpanSnapshot(it.railId, it.minArcLengthInAbMeters, it.maxArcLengthInAbMeters)
                        },
                        emptyList(), emptyList())), ActiveRailContinuationsSnapshot.empty(topology.revision)))
            }
            var observation = frame(0.0, 50.0)
            val registry = InfrastructureCoordinationRegistry({ topology.revision }, { true })
            val catalog = RouteBoundResourceCatalog { topology }
            catalog.publish(topology, PhysicalTopologyResources.routeBoundBindings(topology))
            val live = LiveInfrastructurePreparationActuator(world) { _, _ -> null }
            var starts = 0
            var secondReady = false
            var secondRequirements: List<InfrastructurePreparationRequirement> = emptyList()
            val engine = RouteResourceAdmissionEngine("bulb", registry, Any(), { topology }, { observation }, catalog,
                { _, _ -> null }, InfrastructurePreparationActuator { requirements ->
                    if (requirements.isEmpty()) live.start(requirements) else if (++starts == 1) live.start(requirements) else {
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
            val secondUseMeters = stem.curve.totalArcLength + outbound.curve.totalArcLength +
                loop.curve.totalArcLength + inbound.curve.totalArcLength
            val ceiling = RouteBoundary(secondUseMeters - 2.0 * Curve.TOLERANCE)
            assertEquals(ceiling, tooLong.failure.retryAtOrBefore)
            val first = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
                engine.request(requester, intent(350.0)))
            assertSame(outbound, stem.viewAB.nextActiveRail?.rail)
            // The rolling horizon reaches the second use before the first tail can clear 340 m.
            for (head in listOf(293.0, 320.0, 350.0)) {
                observation = frame(head - 50.0, head)
                engine.consumeObservation(observation)
                val claim = registry.claimSnapshots().single()
                val admissions = engine.activeAdmissions()
                val rejected = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                    engine.request(requester, intent(head + 300.0)))
                assertEquals(RouteAdmissionFailureCode.REQUEST_RANGE_TOO_LONG, rejected.failure.code)
                assertEquals(ceiling, rejected.failure.retryAtOrBefore)
                assertEquals(claim, registry.claimSnapshots().single())
                assertEquals(admissions, engine.activeAdmissions())
                assertEquals(1, starts)
                assertSame(outbound, stem.viewAB.nextActiveRail?.rail)
            }
            val prefix = assertInstanceOf(RouteAdmissionResult.Granted::class.java,
                engine.request(requester, intent(ceiling.distanceFromRouteStartMeters)))
            assertSame(first.grant.grantId, prefix.grant.grantId)
            assertEquals(ceiling, prefix.grant.committedAuthority)
            assertEquals(1, starts)
            assertSame(outbound, stem.viewAB.nextActiveRail?.rail)
            assertTrue(registry.claimSnapshots().single().footprint.resources.contains(turnout))
            val cappedClaim = registry.claimSnapshots().single()
            val stillHeld = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                engine.request(requester, intent(route.totalLengthMeters)))
            assertEquals(RouteAdmissionFailureCode.REQUEST_RANGE_TOO_LONG, stillHeld.failure.code)
            assertEquals(ceiling, stillHeld.failure.retryAtOrBefore)
            assertEquals(cappedClaim, registry.claimSnapshots().single())
            assertEquals(ceiling, engine.activeAdmissions().single().committedAuthority)
            assertEquals(1, starts)
            assertSame(outbound, stem.viewAB.nextActiveRail?.rail)
            assertTrue(registry.claimSnapshots().single().footprint.resources.contains(turnout))

            // A competing route cannot acquire the turnout while the first visit still holds it.
            val competitor = registry.beginAcquireClaim(Any(), InfrastructureClaimRequest(InfrastructureOwner("test:competitor", 1L),
                work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint(listOf(turnout))))
            assertFalse(competitor.result.successful)
            assertEquals(canClearFirstUse, ceiling.distanceFromRouteStartMeters - 50.0 > 340.0)
            if (!canClearFirstUse) {
                observation = frame(ceiling.distanceFromRouteStartMeters - 50.0, ceiling.distanceFromRouteStartMeters)
                engine.consumeObservation(observation)
                assertTrue(registry.claimSnapshots().single().footprint.resources.contains(turnout))
                val waiting = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                    engine.request(requester, intent(route.totalLengthMeters)))
                assertEquals(ceiling, waiting.failure.retryAtOrBefore)
                assertEquals(ceiling, engine.activeAdmissions().single().committedAuthority)
                assertEquals(1, starts)
                assertSame(outbound, stem.viewAB.nextActiveRail?.rail)
                return
            }
            observation = frame(341.0, 391.0)
            engine.consumeObservation(observation)
            assertFalse(registry.claimSnapshots().single().footprint.resources.contains(turnout))
            val second = assertInstanceOf(RouteAdmissionResult.Pending::class.java,
                engine.request(requester, intent(route.totalLengthMeters)))
            assertEquals(prefix.grant.committedAuthority, second.committedGrant?.committedAuthority)
            assertEquals(2, starts)
            assertSame(outbound, stem.viewAB.nextActiveRail?.rail)
            assertTrue(registry.claimSnapshots().single().footprint.resources.contains(turnout))
            val pendingBusy = assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                engine.request(requester, intent(route.totalLengthMeters)))
            assertEquals(RouteAdmissionFailureCode.REQUEST_BUSY, pendingBusy.failure.code)
            assertNull(pendingBusy.failure.retryAtOrBefore)
            secondReady = true
            val ready = assertInstanceOf(RouteAdmissionResult.Granted::class.java, engine.progress(requester, second.handle))
            assertSame(first.grant.grantId, ready.grant.grantId)
            assertEquals(RouteBoundary(route.totalLengthMeters), ready.grant.committedAuthority)
            assertSame(inbound, stem.viewAB.nextActiveRail?.rail)
            observation = frame(391.0, 441.0)
            engine.consumeObservation(observation)
            observation = frame(441.0, 491.0)
            engine.consumeObservation(observation)
            observation = frame(secondUseMeters + 1.0, secondUseMeters + 51.0)
            engine.consumeObservation(observation)
            assertFalse(registry.claimSnapshots().single().footprint.resources.contains(turnout))
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }
}
