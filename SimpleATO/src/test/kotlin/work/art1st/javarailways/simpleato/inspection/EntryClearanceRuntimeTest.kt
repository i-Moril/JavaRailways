package work.art1st.javarailways.simpleato.inspection

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.simpleato.api.inspection.*
import work.art1st.javarailways.simpleato.api.restriction.*
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.simpleato.api.signal.*
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import java.util.UUID

class EntryClearanceRuntimeTest {
    private val rail = UUID(0, 501)
    private val route = RouteSnapshot("test:route", 1, 7,
        listOf(RouteSegmentSnapshot(rail, ViewDirection.AB, 0.0, 100.0)))
    private val frozen = FrozenRouteBinding.from(route)
    private val requirement = InspectionEntryClearance("test:entry", 10.0, 25.0)
    private val publisher = TrainRestrictionPublisher { TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, it.revision) }
    private fun stamp(epoch: Long = 1) = RailwayStateStamp("world", epoch, 7)
    private fun declaration(train: String) = InspectionDeclarationBinding("test:inspection", train, frozen, 1, 1,
        listOf(InspectionDeclaration("test:entry", rail, ViewDirection.AB, 10.0, 0.0)),
        entryClearances = listOf(requirement))
    private fun runtime(onBind: () -> Unit = {}) = InspectionRuntime(publisher) { _, _, failure -> throw failure }.also { host ->
        host.replaceSources(listOf(InspectionSource("test:inspection", { input -> onBind(); declaration(input.trainId) }, { emptyList() })))
        host.synchronizeRoute(stamp(), "train", route, 10.0, 5.0)
    }
    private fun snapshot(epoch: Long, ready: Boolean) = InspectionClearanceSnapshot("test:inspection", "train", stamp(epoch), frozen,
        listOf(InspectionEntryClearanceState("test:entry", 10.0, 25.0, 35.0, 40.0, ready)))
    private fun frame(epoch: Long, live: Boolean = true): RailwayWorldFrameSnapshot {
        val point = RailPositionSnapshot(rail, ViewDirection.AB, 0.0)
        return RailwayWorldFrameSnapshot.create(epoch, 7, if (!live) emptyList() else listOf(
            TrainFrameSnapshot.create("train", "Train", epoch, epoch, 0.0, false, false, emptyList(), point, point,
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList())), ActiveRailContinuationsSnapshot.empty(7))
    }

    @Test fun `geometry and stopping profile refresh only this train floor without compiling bindings again`() {
        var bindCount = 0
        val host = runtime { bindCount++ }
        host.synchronizeRoute(stamp(), "other", route, 10.0, 5.0)
        val old = host.bindingFor("train")
        val other = host.bindingFor("other")
        assertEquals(40.01, host.adjustRequestedAuthorityBoundary("train", frozen, RouteBoundary(11.0)).distanceFromRouteStartMeters, 1e-9)
        host.synchronizeRoute(stamp(2), "train", route, 20.0, 7.0)
        assertEquals(52.01, host.adjustRequestedAuthorityBoundary("train", frozen, RouteBoundary(11.0)).distanceFromRouteStartMeters, 1e-9)
        assertSame(old, host.bindingFor("train"))
        assertSame(old.entryClearances, host.bindingFor("train").entryClearances)
        assertSame(other, host.bindingFor("other"))
        assertEquals(40.01, host.adjustRequestedAuthorityBoundary("other", frozen, RouteBoundary(11.0)).distanceFromRouteStartMeters, 1e-9)
        assertEquals(2, bindCount)
        host.synchronizeRoute(stamp(3), "train", route)
        assertEquals(100.0, host.adjustRequestedAuthorityBoundary("train", frozen, RouteBoundary(11.0)).distanceFromRouteStartMeters)
    }

    @Test fun `readiness changes alone wake watching sources coalesce and unchanged ticks stay idle`() {
        val host = runtime()
        val epoch = SignalEpochRuntime("world", publisher).also { it.attachInspectionRuntime(host) }
        val received = mutableListOf<SignalEpochInput>()
        var unrelatedCalls = 0
        fun watching() = SignalEpochSource("test:signals", emptyList(), emptyList(), emptyList(), emptyList(),
            listOf("test:inspection")) { received += it; SignalEpochResult.empty() }
        val unrelated = SignalEpochSource("test:unrelated", emptyList(), emptyList(), listOf(rail)) {
            unrelatedCalls++; SignalEpochResult.empty()
        }
        epoch.replaceSources(listOf(watching(), unrelated))
        host.publishClearance(snapshot(1, false))
        epoch.process(frame(1))
        host.publishClearance(snapshot(2, false))
        epoch.process(frame(2))
        assertEquals(1, received.size)
        host.publishClearance(snapshot(3, false))
        host.publishClearance(snapshot(3, true))
        epoch.process(frame(3))
        assertTrue(received.last().entryClearanceUpdates.single().clearances.single().movementReady)
        assertEquals(2, received.size)
        assertEquals(1, unrelatedCalls)
        epoch.replaceSources(listOf(watching()))
        epoch.process(frame(4))
        assertTrue(received.last().entryClearanceUpdates.single().clearances.single().movementReady)
        epoch.process(frame(5, live = false))
        assertTrue(received.last().entryClearanceUpdates.single().clearances.isEmpty())
        epoch.process(frame(6))
        assertEquals(4, received.size, "reappearance alone cannot revive withdrawn readiness")
        host.publishClearance(snapshot(6, true))
        epoch.process(frame(7))
        assertEquals(5, received.size)
        host.removeTrain("train")
        epoch.replaceSources(emptyList())
        epoch.process(frame(8))
        assertTrue(host.takeClearanceUpdates(false).isEmpty(), "no consumers must still drain withdrawals")
    }

    @Test fun `neutral values defensively copy and reject inconsistent proof identities and bounds`() {
        val states = mutableListOf(snapshot(1, true).clearances.single())
        val copy = InspectionClearanceSnapshot("test:inspection", "train", stamp(), frozen, states)
        states.clear()
        assertEquals(1, copy.clearances.size)
        assertThrows(UnsupportedOperationException::class.java) { (copy.clearances as MutableList).clear() }
        assertThrows(IllegalArgumentException::class.java) {
            InspectionEntryClearanceState("test:entry", 10.0, 25.0, 25.0, 25.0, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            InspectionClearanceSnapshot("test:inspection", "train", stamp(), frozen,
                listOf(InspectionEntryClearanceState("test:entry", 10.0, 25.0, 95.0, 101.0, true)))
        }
        val beyond = InspectionClearanceSnapshot("test:inspection", "train", stamp(), frozen,
            listOf(InspectionEntryClearanceState("test:entry", 10.0, 25.0, 95.0, 101.0, false)))
        assertFalse(beyond.clearances.single().movementReady)
        assertThrows(IllegalArgumentException::class.java) {
            InspectionDeclarationBinding("test:inspection", "train", frozen, 1, 1, emptyList(), entryClearances = listOf(requirement))
        }
        assertThrows(IllegalArgumentException::class.java) {
            InspectionDeclarationBinding("test:inspection", "train", frozen, 1, 1,
                listOf(InspectionDeclaration("test:zero", rail, ViewDirection.AB, 0.0, 0.0)),
                entryClearances = listOf(InspectionEntryClearance("test:zero", 0.0, 25.0),
                    InspectionEntryClearance("test:zero", -0.0, 25.0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            InspectionClearanceSnapshot("test:inspection", "train", stamp(), frozen, listOf(
                InspectionEntryClearanceState("test:zero", 0.0, 25.0, 35.0, 40.0, true),
                InspectionEntryClearanceState("test:zero", -0.0, 25.0, 35.0, 40.0, true)))
        }
    }

    @Test fun `real straight moving coupler geometry retains floors and unchanged signal epoch stays idle`() {
        val world = work.art1st.javarailways.core.physics.train.createTestWorld(
            work.art1st.javarailways.core.physics.tick.PhysicsCoordinator(1, automaticFallback = false))
        try {
            val track = work.art1st.javarailways.core.physics.train.createStraightRailFromSegments(world,
                work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator.buildStraight(500.0, 1))
            val train = work.art1st.javarailways.core.physics.train.createTrainWithConfig("moving-clearance", track, world,
                listOf(10.0, 10.0), connectorLength = 1.0)
            val selected = RouteSnapshot("test:moving-route", 1, world.topologyState.revision,
                listOf(RouteSegmentSnapshot(track.id, ViewDirection.AB, 0.0, 500.0)))
            val binding = FrozenRouteBinding.from(selected)
            val clearance = InspectionEntryClearance("test:entry", 100.0, 200.0)
            val host = InspectionRuntime(publisher) { _, _, failure -> throw failure }
            host.replaceSources(listOf(InspectionSource("test:inspection", { input ->
                InspectionDeclarationBinding("test:inspection", input.trainId, binding, 1, 1,
                    listOf(InspectionDeclaration("test:entry", track.id, ViewDirection.AB, 100.0, 0.0)),
                    entryClearances = listOf(clearance))
            }, { emptyList() })))
            var calls = 0
            val epoch = SignalEpochRuntime("moving-world", publisher).also { it.attachInspectionRuntime(host) }
            epoch.replaceSources(listOf(SignalEpochSource("test:signals", emptyList(), emptyList(), emptyList(), emptyList(),
                listOf("test:inspection")) { calls++; SignalEpochResult.empty() }))
            val rawLengths = mutableListOf<Double>()
            val envelopeLengths = mutableListOf<Double>()
            val floorTokens = mutableListOf<Any>()
            val floorField = InspectionRuntime::class.java.getDeclaredField("authorityRequestFloorsByTrain").apply { isAccessible = true }
            world.physicsCoordinator.registerRailwayEpochProcessor { frame ->
                val physical = frame.trains.single()
                val stamp = RailwayStateStamp("moving-world", frame.physicsEpoch, frame.topologyRevision)
                host.synchronizeRoute(stamp, train.id, selected, authorityStoppingMarginMeters = 2.0, publishedFrame = physical)
                val length = requireNotNull(host.bindingFor(train.id).currentTrainLength(physical, train.id, frame.physicsEpoch, physical.head))
                val raw = requireNotNull(conservativeTrainLengthMeters(physical.bodyTraversal))
                assertTrue(length >= raw)
                rawLengths += raw
                envelopeLengths += length
                floorTokens += requireNotNull((floorField.get(host) as Map<*, *>)[train.id])
                val ready = entryClearanceState(clearance, length, physical.head.arcLengthInAbMeters,
                    500.0, 500.0 - physical.head.arcLengthInAbMeters,
                    work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile(safetyDistanceMeters = 2.0), emptyList())
                assertTrue(ready.movementReady)
                host.publishClearance(InspectionClearanceSnapshot("test:inspection", train.id, stamp, binding, listOf(ready)))
                epoch.process(frame)
            }
            world.physicsCoordinator.start()
            world.physicsCoordinator.stepSimulation(1.0).await()
            repeat(100) {
                train.moveTrainForward(0.1)
                train.commit()
                world.physicsCoordinator.stepSimulation(1.0).await()
            }
            assertTrue(rawLengths.distinct().size > 1, "real coupler solver must exercise nonconstant raw lengths")
            assertEquals(1, envelopeLengths.distinct().size)
            floorTokens.forEach { assertSame(floorTokens.first(), it, "unchanged conservative geometry must reuse compiled floors") }
            assertEquals(1, calls, "continuous straight motion alone must not wake unchanged signal readiness")
            assertNull(host.bindingFor(train.id).currentTrainLength(null, train.id, train.publishedFrame.physicsEpoch, train.publishedFrame.head))
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as? AutoCloseable)?.close()
        }
    }
}
