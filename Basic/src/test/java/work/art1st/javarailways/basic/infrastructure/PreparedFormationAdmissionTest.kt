package work.art1st.javarailways.basic.infrastructure

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.*
import work.art1st.javarailways.basic.infrastructure.admission.RouteResourceAdmissionRuntime
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.*
import java.util.concurrent.TimeUnit

class PreparedFormationAdmissionTest {
    @Test
    fun `real split retains source resource owner and both outputs remain fenced after coherent observation`() {
        Fixture().use { f ->
            val grant = f.grant()
            val before = f.claims()
            val splitIntent = f.split(grant)
            val prepared = f.owner { accepted(f.runtime.prepareFormationChange(f.requester, splitIntent)) }
            assertEquals(FormationChangePhase.PREPARED, prepared.phase)
            assertEquals(before, f.claims())
            assertEquals(RouteAdmissionFailureCode.REQUEST_BUSY,
                f.owner { assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                    f.runtime.request(f.requester, f.intent(30.0))).failure.code })
            assertInstanceOf(RouteGrantReversalResult.Rejected::class.java,
                f.owner { f.runtime.suspendReleaseForReversal(f.requester, grant.grantId) })
            assertFalse(f.owner { f.runtime.endGrant(f.requester, grant.grantId) })
            assertInstanceOf(FormationChangeResult.Rejected::class.java,
                f.owner { f.runtime.sealFormationPublication(Any(), prepared.handle) })
            f.owner { accepted(f.runtime.sealFormationPublication(f.requester, prepared.handle)) }
            assertInstanceOf(FormationChangeResult.Rejected::class.java,
                f.owner { f.runtime.abortFormationChange(f.requester, prepared.handle) })
            val future = f.train.splitBefore(splitIntent.beforeCarId, splitIntent.separated.trainId)
            f.owner { f.world.physicsCoordinator.drainBetweenFramesOperations() }
            val separated = future.get(5, TimeUnit.SECONDS) as CoreTrain
            assertTrue(f.train.movementHeld)
            assertTrue(separated.movementHeld)
            assertEquals(FormationChangePhase.PHYSICAL_OBSERVED,
                f.owner { accepted(f.runtime.formationChangeState(f.requester, prepared.handle)).phase })
            f.refresh()
            f.owner { f.runtime.consumeObservation(f.observation) }
            assertEquals(FormationChangePhase.OUTPUTS_HELD,
                f.owner { accepted(f.runtime.formationChangeState(f.requester, prepared.handle)).phase })
            assertEquals(before, f.claims(), "source claim must survive changed source composition")
            assertEquals(1, f.runtime.requesterActivity(f.requester).size)
            assertTrue(f.stalls.isEmpty())
        }
    }

    @Test
    fun `unsealed unchanged event can abort without releasing claims and ordinary source admission resumes`() {
        Fixture().use { f ->
            val grant = f.grant()
            val before = f.claims()
            val prepared = f.owner { accepted(f.runtime.prepareFormationChange(f.requester, f.split(grant))) }
            assertInstanceOf(FormationChangeResult.Rejected::class.java,
                f.owner { f.runtime.prepareFormationChange(f.requester, f.split(grant).copy(eventId = "other")) })
            assertEquals(FormationChangePhase.ABORTED,
                f.owner { accepted(f.runtime.abortFormationChange(f.requester, prepared.handle)).phase })
            assertEquals(before, f.claims())
            assertEquals(FormationChangePhase.ABORTED,
                f.owner { accepted(f.runtime.abortFormationChange(f.requester, prepared.handle)).phase })
            assertInstanceOf(RouteAdmissionResult.Granted::class.java,
                f.owner { f.runtime.request(f.requester, f.intent(30.0)) })
            assertTrue(f.stalls.isEmpty())
        }
    }

    @Test
    fun `wrong split partition and foreign grant reject before freezing or physical publication`() {
        Fixture().use { f ->
            val grant = f.grant()
            val before = f.claims()
            val intent = f.split(grant)
            assertInstanceOf(FormationChangeResult.Rejected::class.java,
                f.owner { f.runtime.prepareFormationChange(Any(), intent) })
            assertInstanceOf(FormationChangeResult.Rejected::class.java,
                f.owner { f.runtime.prepareFormationChange(f.requester,
                    intent.copy(retained = intent.separated)) })
            assertEquals(before, f.claims())
            assertEquals(2, f.train.cars.size)
            assertInstanceOf(RouteAdmissionResult.Granted::class.java,
                f.owner { f.runtime.request(f.requester, f.intent(30.0)) })
        }
    }

    @Test
    fun `live operational grant vetoes an unprepared manual split before mutation`() {
        Fixture().use { f ->
            f.grant()
            val original = f.train.publishedFrame
            val before = f.claims()
            val future = f.train.splitBefore(f.train.cars.last().id, "unprepared")
            f.owner { f.world.physicsCoordinator.drainBetweenFramesOperations() }
            assertThrows(java.util.concurrent.ExecutionException::class.java) { future.get(5, TimeUnit.SECONDS) }
            assertSame(original, f.train.publishedFrame)
            assertNull(f.world.getTrain("unprepared"))
            assertEquals(before, f.claims())
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(QueuedSource::class)
    fun `queued split uses coherent physical publication and rejects changed source despite stale host observation`(mode: QueuedSource) {
        Fixture().use { f ->
            val grant = f.grant()
            val intent = f.split(grant)
            val prepared = f.owner { accepted(f.runtime.prepareFormationChange(f.requester, intent)) }
            f.owner { accepted(f.runtime.sealFormationPublication(f.requester, prepared.handle)) }
            val original = f.train.publishedFrame
            val claims = f.claims()
            f.owner {
                if (mode != QueuedSource.UNCHANGED) {
                    f.train.setLocation(work.art1st.javarailways.core.physics.rail.RailLocationWithFacing(
                        if (mode == QueuedSource.REORIENTED) f.rail.viewBA else f.rail.viewAB,
                        original.head.arcLengthInAbMeters + 1.0))
                    f.train.commitMaintenancePlacement()
                }
            }
            // Host has already drained this frame before scheduling; the queued operation only
            // executes next frame, before the host updates its external RailwayObservation.
            val future = f.train.splitBefore(intent.beforeCarId, intent.separated.trainId)
            f.world.physicsCoordinator.start()
            f.world.physicsCoordinator.stepSimulation(50.0).await()
            assertTrue(f.train.publishedFrame.physicsEpoch > f.observation.stamp.physicsEpoch)
            f.owner { f.world.physicsCoordinator.drainBetweenFramesOperations() }
            if (mode == QueuedSource.UNCHANGED) {
                assertNotNull(future.get(5, TimeUnit.SECONDS))
                assertEquals(2, f.world.allTrains.size)
                assertTrue(f.world.allTrains.all { (it as CoreTrain).movementHeld })
            } else {
                assertThrows(java.util.concurrent.ExecutionException::class.java) { future.get(5, TimeUnit.SECONDS) }
                assertEquals(1, f.world.allTrains.size)
                assertEquals(2, f.train.cars.size)
            }
            assertEquals(claims, f.claims())
        }
    }

    enum class QueuedSource { UNCHANGED, MOVED, REORIENTED }

    private fun accepted(result: FormationChangeResult): FormationChangeSnapshot =
        assertInstanceOf(FormationChangeResult.Accepted::class.java, result, result.toString()).snapshot

    private class Fixture : AutoCloseable {
        private val coordinator = work.art1st.javarailways.core.physics.tick.PhysicsCoordinator(1, automaticFallback = false)
        val physical = TestWorldFixture.create(coordinator)
        val world = physical.world
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(300.0, 1))
        val train = createTrainWithConfig("source", rail, world, listOf(10.0, 10.0))
        val requester = Any()
        val stalls = arrayListOf<String>()
        var observation = RailwayObservation.fromCore("formation-test", world.latestWorldFrame())
        val runtime = RouteResourceAdmissionRuntime.create("formation-test", world,
            world.infrastructureCoordination, Any(), { observation }, { emptyList() }, { _, _ -> null },
            { _, _ -> null }, stallWorld = { reason, _ -> stalls += reason })

        fun <T> owner(action: () -> T): T = world.onOwnerForTest(action)
        fun refresh() { observation = RailwayObservation.fromCore("formation-test", world.latestWorldFrame()) }
        fun claims() = world.infrastructureCoordination.claimSnapshots().map { it.owner to it.footprint.resources }
        fun intent(authority: Double = 20.0) = RouteResourceIntent("test:request/source",
            InfrastructureOwner("test:source", 1), train.id, observation.stamp,
            RouteSnapshot("test:route/source", 1, world.topologyState.revision,
                listOf(RouteSegmentSnapshot(rail.id, ViewDirection.AB, 10.0, 200.0))), RouteBoundary(authority))
        fun grant() = owner { assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            runtime.request(requester, intent())).grant }
        fun split(grant: RouteGrantSnapshot): FixedFormationChangeIntent.Split {
            val ids = train.cars.map { it.id }
            return FixedFormationChangeIntent.Split("fixed:split", grant.grantId,
                FormationTrainIdentity(train.id, ids, false), ids.last(),
                FormationTrainIdentity(train.id, ids.take(1), false),
                FormationTrainIdentity("separated", ids.drop(1), false))
        }
        override fun close() { try { runtime.close() } finally { try { physical.close() } finally { coordinator.close() } } }
    }
}
