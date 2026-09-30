package work.art1st.javarailways.simplesignals.signal

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.api.ato.ActuationStep
import work.art1st.javarailways.basic.api.ato.ControlDemand
import work.art1st.javarailways.basic.api.ato.TrainActuator
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureDependencyRequest
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import work.art1st.javarailways.basic.api.infrastructure.StableTopologyNodeKey
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.*
import work.art1st.javarailways.basic.infrastructure.InfrastructureCoordinationRegistry
import work.art1st.javarailways.basic.infrastructure.RouteResourceAdmissionRuntime
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.util.plugin.ExtensionData
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.util.AsyncTickTestSupport
import work.art1st.javarailways.simpleato.SimpleATOPlugin
import work.art1st.javarailways.simpleato.SimpleATOWorldData
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.api.restriction.RouteSpeedRestriction
import work.art1st.javarailways.simpleato.api.inspection.InspectionSource
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotStore
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simpleato.timetable.*
import work.art1st.javarailways.simplesignals.block.FIXED_BLOCK_SIGNAL_SOURCE_ID
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.region.*
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionPublicationStatus
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntime
import java.util.UUID

/** Uses the host's real dispatcher, Basic admission and controller; only actuation is stationary. */
@OptIn(ExtensionData::class)
class ProtectionSignalControllerHandoffTest {
    @Test
    fun `zero signal permits Basic request and changed occupancy reaches next controller without dispatcher`() =
        runHandoff(failHandler = false)

    @Test
    fun `inspection handler failure stalls real controller before actuation without dispatcher`() =
        runHandoff(failHandler = true)

    private fun runHandoff(failHandler: Boolean) {
        val worldId = UUID.randomUUID()
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0, 1))
        val train = createTrainWithConfig("protection-handoff", rail, world, listOf(10.0))
        val coordinator = AtoWorldCoordinator(worldId.toString(), reportSink = {})
        val basic = BasicWorldData(safetyInterlock = coordinator.safetyInterlock)
        val basicPlugin = BasicPlugin.instanceOrNull() ?: BasicPlugin(0).also { BasicPlugin.INSTANCE = it }
        world.registerExtensionData(basicPlugin, basic)

        val restrictions = TrainRestrictionSnapshotStore(worldId.toString())
        val inspection = InspectionRuntime(restrictions) { _, _, _ -> }
        val invalidations = arrayListOf<Pair<String, Set<String>>>()
        val epoch = SignalEpochRuntime(worldId.toString(), restrictions, restrictions::completeEpoch) { sourceId, ids ->
            invalidations += sourceId to ids
            inspection.markDirty(sourceId, ids)
        }
        var latestObservation: RailwayObservation? = null
        val requester = Any()
        val registry = InfrastructureCoordinationRegistry(
            { world.topologyState.revision },
            { resource -> resource is RailIntervalResource && world.getRail(resource.railId) != null },
            { world.getRail(it) != null },
            { key -> world.topologyState.nodes.values.any { StableTopologyNodeKey(it.ports) == key } }
        )
        val admission = RouteResourceAdmissionRuntime.create(
            worldId.toString(), world, registry, Any(), { latestObservation },
            { emptyList() }, { _, _ -> null }, { _, _ -> null }, epoch::publishAdmissionUpdate
        ) { _, failure -> throw failure }
        var speedAtFirstRequest: Double? = null
        var requestCount = 0
        val service = object : RouteResourceAdmissionService {
            override fun request(intent: RouteResourceIntent): RouteAdmissionResult {
                if (requestCount++ == 0) speedAtFirstRequest = restrictions.snapshotsFor(train.id)
                    .flatMap { it.restrictions }.filterIsInstance<RouteSpeedRestriction>()
                    .single().maximumSpeedMetersPerSecond
                return admission.request(requester, intent)
            }
            override fun progress(handle: RouteAdmissionHandle) = admission.progress(requester, handle)
            override fun suspendReleaseForReversal(grantId: RouteGrantId) =
                admission.suspendReleaseForReversal(requester, grantId)
            override fun rebaseAfterReversal(intent: RouteResourceIntent) =
                admission.rebaseAfterReversal(requester, intent)
            override fun endGrant(grantId: RouteGrantId) = admission.endGrant(requester, grantId)
        }
        val dependencies = object : InfrastructureDependencyService {
            override fun set(request: InfrastructureDependencyRequest) = registry.setInfrastructureDependencies(requester, request)
            override fun remove(owner: InfrastructureOwner) = registry.removeInfrastructureDependencies(requester, owner)
        }
        val profile = AtoSafetyProfile(
            accelerationMetersPerSecondSquared = 1.0, serviceDecelerationMetersPerSecondSquared = 1.0,
            reactionTimeSeconds = 0.0, safetyDistanceMeters = 2.0, stopToleranceMeters = 0.25
        )
        val actuator = StationaryActuator()
        val dispatcher = TimetableDispatcher(
            TimetableRouteAdmissionContextProvider { latestObservation?.let { TimetableRouteAdmissionContext(it, service) } },
            restrictions, inspection, dependencies,
            atoFactory = TimetableAtoFactory { selectedTrain, selectedWorld ->
                SimpleATO(selectedTrain, selectedWorld, actuator, profile,
                    inspection::maximumDeclaredLeadTimeSeconds)
            }
        )
        val atoData = SimpleATOWorldData(coordinator, dispatcher, epoch, restrictions, inspection,
            basic.railObjects, basic.safetyInterlock)
        val atoPlugin = SimpleATOPlugin.instanceOrNull() ?: SimpleATOPlugin(101).also { SimpleATOPlugin.INSTANCE = it }
        world.registerExtensionData(atoPlugin, atoData)

        val headMeters = train.headLocation.arcLengthIn(ViewDirection.AB)
        // Keep the signal ahead of the stopped train, so its own conservative envelope does not
        // already occupy the protected route. The host braking curve owns the 2 m approach.
        val entryMeters = headMeters + 2.0
        val exitMeters = entryMeters + 60.0
        val region = ProtectionRegionDefinition("test:handoff-region", 1L, listOf(
            ProtectionBoundaryDefinition("test:handoff-entry", RailPositionSnapshot(rail.id, ViewDirection.AB, entryMeters)),
            ProtectionBoundaryDefinition("test:handoff-exit", RailPositionSnapshot(rail.id, ViewDirection.BA, exitMeters))
        ))
        val route = ProtectionRouteDefinition("test:handoff-route", "test:handoff-entry", "test:handoff-exit",
            listOf(ProtectionRouteTraversal(rail.id, ViewDirection.AB, entryMeters, exitMeters)))
        val signal = ProtectionSignalDefinition("test:handoff-signal", 1L, ProtectionSignalKind.CURRENT_ENTRY,
            "test:handoff-entry", 0.0, ConfiguredSignalOutput(0.0, "custom/unready"),
            ConfiguredSignalOutput(0.0, "custom/occupied"),
            listOf(FixedBlockSignalClearLevel(0.0, ConfiguredSignalOutput(9.25, "custom/clear"))))
        val signals = SimpleSignalsWorldRuntime(worldId)
        assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED,
            signals.initializeDefinitions(world.topologyState, SimpleSignalsWorldDefinitions(worldId, 1L,
                listOf(region), listOf(ProtectionRouteTableDefinition("test:handoff-table", 1L,
                    region.regionId, region.revision, listOf(route))), protectionSignals = listOf(signal))).status)
        epoch.replaceSources(signals.initializeSectionOccupancy().sources)
        var failProjection = false
        val signalSource = signals.fixedBlockInspectionSource
        inspection.replaceSources(listOf(InspectionSource(signalSource.sourceId, signalSource.bindingProvider, { input ->
            check(!failProjection) { "inspection projection failed" }
            signalSource.updateHandler.update(input)
        }), signals.protectionRegionInspectionSource))
        basic.wayPoints.add(WayPoint("handoff-origin", "Origin", rail.id, headMeters))
        basic.wayPoints.add(WayPoint("handoff-target", "Target", rail.id, entryMeters + 250.0))
        atoData.timetables.add(Timetable("handoff-timetable", services = listOf(TimetableService("handoff-service",
            TrainSelector.TrainId(train.id), listOf(TimetableStep.Check("handoff-origin"),
                TimetableStep.At(0L, TimetableAction.GoTo("handoff-target")))))))

        var physicsEpoch = 0L
        fun advanceEpoch(occupied: Boolean = false) {
            val next = ++physicsEpoch
            val head = RailPositionSnapshot.capture(train.headLocation, ViewDirection.AB)
            val tail = RailPositionSnapshot.capture(train.tailLocation, ViewDirection.AB)
            val frames = mutableListOf(
                TrainFrameSnapshot.create(train.id, train.name, next, next, train.speed,
                    train.isDerailed, train.isReversed, emptyList(), head, tail, train.occupiedRailIntervals,
                    listOf(RailTraversalSegmentSnapshot(rail.id, ViewDirection.AB,
                        tail.arcLengthInAbMeters, head.arcLengthInAbMeters)),
                    emptyList(), emptyList(), emptyList())
            )
            // An immutable physical observation of a second occupant; the controlled train's
            // shared perception remains unchanged, isolating the signal-to-inspection handoff.
            if (occupied) frames += TrainFrameSnapshot.create(
                "test:occupant", "Occupant", next, next, 0.0, false, false, emptyList(),
                RailPositionSnapshot(rail.id, ViewDirection.AB, entryMeters + 20.0),
                RailPositionSnapshot(rail.id, ViewDirection.AB, entryMeters + 10.0),
                listOf(RailInterval(rail.id, entryMeters + 10.0, entryMeters + 20.0)),
                emptyList(), emptyList(), emptyList(), emptyList()
            )
            val frame = RailwayWorldFrameSnapshot.create(next, world.topologyState.revision, frames,
                ActiveRailContinuationsSnapshot.empty(world.topologyState.revision))
            latestObservation = RailwayObservation.fromCore(worldId.toString(), frame)
            epoch.process(frame)
        }
        fun tickController() {
            val ato = train.controller as SimpleATO
            AsyncTickTestSupport.run { ato.tick(0.0) }
            ato.commit()
        }
        try {
            advanceEpoch()
            assertTrue(dispatcher.startService(world, "handoff-timetable", "handoff-service") is TimetableServiceStartResult.Started)
            repeat(5) {
                tickController()
                dispatcher.tick(world)
                advanceEpoch()
            }
            assertTrue(requestCount > 0)
            assertEquals(0.0, speedAtFirstRequest)
            assertFalse(coordinator.isStalled, coordinator.lastStall?.message)
            val committed = admission.activeAdmissions().single()
            assertNotNull(committed.committedAuthority)
            assertTrue(signals.protectionRouteSelectionSnapshot()!!.selections.getValue("test:handoff-entry").routeReady)
            assertTrue(invalidations.any { (sourceId, ids) ->
                sourceId == FIXED_BLOCK_SIGNAL_SOURCE_ID && protectionSignalDeclarationId(signal.signalId) in ids
            })
            // From here on Dispatcher is stopped. Both directions of the safety update must be
            // consumed by the next control calculation alone.
            tickController()
            val ato = train.controller as SimpleATO
            val approachLimit = kotlin.math.sqrt(9.25 * 9.25 + 2.0 * 2.0)
            assertEquals(approachLimit, ato.runtimeDiagnostics.permittedSpeedMetersPerSecond, 1e-9)
            val revision = restrictions.snapshotsFor(train.id)
                .single { it.sourceId == FIXED_BLOCK_SIGNAL_SOURCE_ID }.revision
            advanceEpoch()
            tickController()
            assertEquals(revision, restrictions.snapshotsFor(train.id)
                .single { it.sourceId == FIXED_BLOCK_SIGNAL_SOURCE_ID }.revision)
            advanceEpoch(occupied = true)
            tickController()
            assertEquals(0.0, (restrictions.snapshotsFor(train.id)
                .single { it.sourceId == FIXED_BLOCK_SIGNAL_SOURCE_ID }.restrictions.single()
                as RouteSpeedRestriction).maximumSpeedMetersPerSecond, 1e-9)
            assertEquals(2.0, ato.runtimeDiagnostics.permittedSpeedMetersPerSecond, 1e-9)
            assertEquals(2.0, actuator.lastDemand!!.targetSpeedMetersPerSecond, 1e-9)
            advanceEpoch()
            tickController()
            assertEquals(approachLimit, ato.runtimeDiagnostics.permittedSpeedMetersPerSecond, 1e-9)
            ato.setSpeedLimit("test:operating-limit", 3.0)
            tickController()
            assertEquals(3.0, ato.runtimeDiagnostics.permittedSpeedMetersPerSecond, 1e-9)
            if (failHandler) {
                failProjection = true
                inspection.markDirty(FIXED_BLOCK_SIGNAL_SOURCE_ID,
                    listOf(protectionSignalDeclarationId(signal.signalId)))
                val steps = actuator.steps
                tickController()
                assertTrue(coordinator.isStalled)
                assertEquals(steps, actuator.steps)
                assertTrue(coordinator.lastStall!!.message.orEmpty().contains("inspection projection failed"))
                return
            }
            train.moveTrainForward(3.0)
            ato.commit()
            advanceEpoch()
            tickController()
            assertTrue(restrictions.snapshotsFor(train.id)
                .single { it.sourceId == FIXED_BLOCK_SIGNAL_SOURCE_ID }.restrictions.isEmpty())
            assertFalse(coordinator.isStalled, coordinator.lastStall?.message)
        } finally {
            admission.close()
            world.physicsCoordinator.close()
            (world.tickManager as? AutoCloseable)?.close()
        }
    }

    /** Captures the real controller demand while leaving geometry fixed for exact entry assertions. */
    private class StationaryActuator : TrainActuator {
        var steps = 0
        var lastDemand: ControlDemand? = null
        override val maximumSpeedMetersPerSecond = 10.0
        override fun step(currentSpeedMetersPerSecond: Double, demand: ControlDemand, deltaTimeSeconds: Double): ActuationStep {
            steps++
            lastDemand = demand
            return ActuationStep(0.0, 0.0)
        }
    }
}
