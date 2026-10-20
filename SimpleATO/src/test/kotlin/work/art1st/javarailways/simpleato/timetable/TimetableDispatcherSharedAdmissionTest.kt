package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

import work.art1st.javarailways.core.physics.train.onOwnerForTest

import work.art1st.javarailways.core.physics.train.setControllerForTest

import work.art1st.javarailways.simpleato.simpleAtoWorldData

import org.joml.Vector3d
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import work.art1st.javarailways.basic.api.infrastructure.*
import work.art1st.javarailways.core.api.physics.infrastructure.*
import work.art1st.javarailways.core.physics.infrastructure.InfrastructureCoordinationRegistry
import work.art1st.javarailways.simpleato.persistence.SavedDispatcherV1
import work.art1st.javarailways.simpleato.persistence.SavedTimetableClockV1
import work.art1st.javarailways.simpleato.persistence.SavedActiveRunCheckpointV1
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.testing.KinematicTrainActuator
import work.art1st.javarailways.simpleato.api.depot.DepotId
import work.art1st.javarailways.simpleato.api.depot.DepotTrainSelector
import work.art1st.javarailways.simpleato.api.depot.RollingStockRecord
import work.art1st.javarailways.simpleato.api.depot.StablingPointRef
import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.simpleato.api.destination.OperationalDestination
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationService
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.observation.RailwayTrainObservation
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailure
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyMutationResult
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyMutationStatus
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode
import work.art1st.javarailways.basic.api.route.RouteAdmissionHandle
import work.art1st.javarailways.basic.api.route.RouteAdmissionResult
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.basic.api.route.RouteAdmissionTestFixtures
import work.art1st.javarailways.basic.api.route.RouteGrantId
import work.art1st.javarailways.basic.api.route.RouteGrantReversalResult
import work.art1st.javarailways.basic.api.route.RouteGrantSnapshot
import work.art1st.javarailways.basic.api.route.RouteResourceAdmissionService
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.basic.controller.ManualController
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import work.art1st.javarailways.simpleato.timetable.Timetable
import work.art1st.javarailways.simpleato.timetable.TimetableAction
import work.art1st.javarailways.simpleato.timetable.TimetableService
import work.art1st.javarailways.simpleato.timetable.TimetableStep
import work.art1st.javarailways.simpleato.timetable.TrainSelector
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailwayOccupancySnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.util.AsyncTickTestSupport
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.api.restriction.CurrentSpeedRestriction
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotStore
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclaration
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclarationBinding
import work.art1st.javarailways.simpleato.api.inspection.InspectionSource

class TimetableDispatcherSharedAdmissionTest {
    private val worldFixtures = mutableListOf<TestWorldFixture>()
    private val profile = AtoSafetyProfile(
        accelerationMetersPerSecondSquared = 1.0,
        serviceDecelerationMetersPerSecondSquared = 1.0,
        reactionTimeSeconds = 0.0,
        safetyDistanceMeters = 2.0,
        stopToleranceMeters = 0.25
    )

    @AfterEach
    fun closeWorlds() {
        val fixtures = worldFixtures.toList()
        worldFixtures.clear()
        assertAll(fixtures.flatMap { fixture ->
            listOf(
                Executable { TestSimpleATOWorldDataRegistry.unregister(fixture.world) },
                Executable { fixture.close() }
            )
        })
    }

    @Test
    fun `automatic run extends the same frozen route with the current observation epoch`() {
        val fixture = fixture()
        fixture.service.onRequest = fixture.service::grant

        fixture.start()
        fixture.dispatch()

        for (ignored in 0 until 400) {
            if (fixture.service.intents.size >= 2) break
            fixture.tickAto()
            fixture.dispatch()
        }

        assertTrue(fixture.service.intents.size >= 2, fixture.status().toString())
        val first = fixture.service.intents[0]
        val extension = fixture.service.intents[1]
        assertTrue(extension.observationStamp.physicsEpoch > first.observationStamp.physicsEpoch)
        assertEquals(first.route, extension.route)
        assertEquals(first.owner, extension.owner)
        assertSame(fixture.service.grantId, fixture.service.grants[0].grantId)
        assertSame(fixture.service.grantId, fixture.service.grants[1].grantId)
        assertEquals(fixture.providerCalls, fixture.dispatchTicks)
    }

    @Test
    fun `complete frozen route is edit protected before Basic admission`() {
        val fixture = fixture()
        fixture.service.onRequest = { intent ->
            val dependency = fixture.dependencies.applied.values.single()
            assertEquals(intent.route.segments.map { it.railId }.toSet(), dependency.dependencies.railIds)
            fixture.service.grant(intent)
        }

        fixture.start()
        fixture.dispatch()

        assertEquals(1, fixture.service.intents.size)
        assertEquals(2, fixture.dependencies.mutations.size)
    }

    @Test
    fun `draining preserves plan edits through pending and terminal tail-held admission until end succeeds`() {
        // Admission outcomes are scripted; edit protection below uses the real Basic registry.
        // endResult=true represents Basic confirming tail release, not a physical-release simulation.
        val block = StableInfrastructureResource("simplesignals:fixed-block", "test:planned-block")
        for (pendingInitially in listOf(false, true)) {
            val fixture = fixture(inspectionSources = listOf(staticBlockSource(block)), realEditGuards = true)
            val registry = checkNotNull(fixture.editRegistry)
            val pending = RouteAdmissionTestFixtures.handle()
            var finishPreparation = !pendingInitially
            fixture.service.onRequest = { intent ->
                if (finishPreparation) fixture.service.grant(intent) else RouteAdmissionResult.Pending(pending, null)
            }
            fixture.service.onProgress = { handle ->
                assertSame(pending, handle)
                if (finishPreparation) fixture.service.grant(fixture.service.intents.single())
                else RouteAdmissionResult.Pending(pending, null)
            }
            assertTrue(fixture.world.onOwnerForTest { fixture.dispatcher.beginTimetable(fixture.world, "tt") } is TimetableEpochStartResult.Started)
            val applied = registry.dependencySnapshots().single()
            assertEquals(setOf(fixture.rail.id), applied.dependencies.railIds)
            assertEquals(setOf(block), applied.dependencies.resourceIds)
            fixture.start()
            fixture.dispatch()
            for (ignored in 0 until 20) {
                if (fixture.service.intents.isNotEmpty()) break
                fixture.tickAto()
                fixture.dispatch()
            }
            assertEquals(1, fixture.service.intents.size, fixture.status().toString())
            assertEquals(!pendingInitially, fixture.status().routeGrantActive)
            assertTrue(fixture.world.onOwnerForTest { fixture.dispatcher.stopFutureCycles("tt") })
            assertTrue(fixture.dispatcher.saveCheckpoint().clocks.isEmpty())
            assertFalse(fixture.world.onOwnerForTest { fixture.dispatcher.scheduleInitialCycle("tt") })
            assertTrue(registry.dependencySnapshots().any { it == applied })
            assertEquals(TopologyEditStatus.CONFLICT, fixture.editDecision(TopologyEditImpact(emptyList(), emptyList(), listOf(block))))
            assertEquals(TopologyEditStatus.CONFLICT, fixture.editDecision(TopologyEditImpact(listOf(fixture.rail.id), emptyList(), emptyList())))
            assertTrue(fixture.world.onOwnerForTest { fixture.dispatcher.stopService(fixture.world, TimetableServiceRunId("tt", "service")) })
            for (ignored in 0 until 200) {
                if (fixture.status().state == TimetableServiceRunState.STOPPED) break
                fixture.tickAto()
                fixture.dispatch()
            }
            assertEquals(TimetableServiceRunState.STOPPED, fixture.status().state,
                "pending=$pendingInitially speed=${fixture.train.speed} " +
                    "stall=${fixture.world.simpleAtoWorldData.atoCoordinator.lastStall?.message}")
            fixture.dispatch()
            assertTrue(registry.dependencySnapshots().any { it == applied })
            if (pendingInitially) {
                assertFalse(fixture.status().routeGrantActive)
                assertTrue(fixture.world.onOwnerForTest { fixture.dispatcher.beginTimetable(fixture.world, "tt") } is TimetableEpochStartResult.Failure)
                assertThrows(IllegalStateException::class.java) { fixture.world.onOwnerForTest { fixture.dispatcher.forceDiscardTimetable(fixture.world, "tt") } }
                assertTrue(registry.dependencySnapshots().any { it == applied })
            }
            finishPreparation = true
            fixture.dispatch()
            assertTrue(fixture.status().routeGrantActive)
            assertTrue(registry.dependencySnapshots().any { it == applied })
            val rejected = fixture.world.onOwnerForTest { fixture.dispatcher.beginTimetable(fixture.world, "tt") }
            assertTrue(rejected is TimetableEpochStartResult.Failure, rejected.toString())
            assertThrows(IllegalStateException::class.java) { fixture.world.onOwnerForTest { fixture.dispatcher.forceDiscardTimetable(fixture.world, "tt") } }
            assertTrue(registry.dependencySnapshots().any { it == applied })
            assertTrue(fixture.dispatcher.saveCheckpoint().clocks.isEmpty())
            assertEquals(1, fixture.service.intents.size)

            fixture.service.endResult = true
            fixture.dispatch()

            assertFalse(fixture.status().routeGrantActive)
            assertTrue(registry.dependencySnapshots().isEmpty())
            assertEquals(TopologyEditStatus.GRANTED, fixture.editDecision(TopologyEditImpact(emptyList(), emptyList(), listOf(block))))
            assertEquals(TopologyEditStatus.GRANTED, fixture.editDecision(TopologyEditImpact(listOf(fixture.rail.id), emptyList(), emptyList())))
            repeat(24_001) { fixture.dispatch() }
            assertEquals(1, fixture.dispatcher.statuses().size, "drained timetable must not depart in a later automatic cycle")
            assertEquals(0L, fixture.status().cycleIndex)
            assertEquals(1, fixture.service.intents.size)
            assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
        }
    }

    @Test
    fun `drain dependency removal denial retains protection and stalls without retrying operational work`() {
        val block = StableInfrastructureResource("simplesignals:fixed-block", "test:planned-block")
        val fixture = fixture(inspectionSources = listOf(staticBlockSource(block)), realEditGuards = true)
        val registry = checkNotNull(fixture.editRegistry)
        assertTrue(fixture.world.onOwnerForTest { fixture.dispatcher.beginTimetable(fixture.world, "tt") } is TimetableEpochStartResult.Started)
        val applied = registry.dependencySnapshots().single()
        fixture.dependencies.removeFailure = InfrastructureDependencyMutationResult(
            InfrastructureDependencyMutationStatus.SERVICE_CLOSED, "test dependency service closed")

        assertTrue(fixture.world.onOwnerForTest { fixture.dispatcher.stopFutureCycles("tt") })

        assertEquals(listOf(applied), registry.dependencySnapshots())
        assertTrue(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
        assertEquals("InfrastructureDependencyFailure", fixture.world.simpleAtoWorldData.atoCoordinator.lastStall?.errorType)
        assertEquals(TopologyEditStatus.CONFLICT, fixture.editDecision(TopologyEditImpact(emptyList(), emptyList(), listOf(block))))
        assertThrows(IllegalStateException::class.java) {
            fixture.dispatcher.restoreCheckpoint(fixture.world,
                SavedDispatcherV1(1, 0L, 0L, emptyList(), emptyList()))
        }
        repeat(5) { fixture.dispatch() }
        assertEquals(listOf(applied), registry.dependencySnapshots())
        assertTrue(fixture.service.intents.isEmpty())
    }

    @Test
    fun `stopped recovered clock retains exact plan until explicit recovery discard without recreating admission`() {
        val block = StableInfrastructureResource("simplesignals:fixed-block", "test:planned-block")
        val fixture = fixture(inspectionSources = listOf(staticBlockSource(block)), realEditGuards = true)
        val registry = checkNotNull(fixture.editRegistry)
        val saved = SavedDispatcherV1(1, 500L, 3L,
            listOf(SavedTimetableClockV1("tt", 0L, 24_000L, 1L, emptySet(), emptyMap())),
            listOf(SavedActiveRunCheckpointV1("tt", "service", 0L, fixture.train.id, 2,
                "A", "B", "RUNNING", 500L, 0L)))
        assertEquals(TimetableCheckpointRestoreResult.Restored, fixture.dispatcher.restoreCheckpoint(fixture.world, saved))
        val applied = registry.dependencySnapshots().single()
        assertEquals(setOf(fixture.rail.id), applied.dependencies.railIds)
        assertEquals(setOf(block), applied.dependencies.resourceIds)
        assertEquals(setOf("service"), fixture.dispatcher.saveCheckpoint().clocks.single().disabledServiceIds)

        assertTrue(fixture.world.onOwnerForTest { fixture.dispatcher.stopFutureCycles("tt") })
        repeat(5) { fixture.dispatch() }

        assertTrue(fixture.dispatcher.saveCheckpoint().clocks.isEmpty())
        assertEquals(saved.runs, fixture.dispatcher.recoveryRequiredRuns())
        assertEquals(listOf(applied), registry.dependencySnapshots())
        assertEquals(TopologyEditStatus.CONFLICT, fixture.editDecision(TopologyEditImpact(emptyList(), emptyList(), listOf(block))))
        assertTrue(registry.claimSnapshots().isEmpty())
        assertTrue(fixture.service.intents.isEmpty())
        assertTrue(fixture.dispatcher.statuses().isEmpty())
        assertEquals(null, fixture.train.controller)

        assertEquals(1, fixture.world.onOwnerForTest { fixture.dispatcher.forceDiscardTimetable(fixture.world, "tt") })

        assertTrue(fixture.dispatcher.recoveryRequiredRuns().isEmpty())
        assertTrue(registry.dependencySnapshots().isEmpty())
        assertEquals(TopologyEditStatus.GRANTED, fixture.editDecision(TopologyEditImpact(emptyList(), emptyList(), listOf(block))))
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    // A neutral resource-identity source, not the SimpleSignals matcher (covered in its own module).
    private fun staticBlockSource(block: StableInfrastructureResource): InspectionSource {
        var revision = 0L
        return InspectionSource("test:static-block",
            { input -> InspectionDeclarationBinding("test:static-block", input.trainId, FrozenRouteBinding.from(input.route),
                1L, 0L, emptyList()) },
            { input -> listOf(TrainRestrictionSnapshot("test:static-block", revision++, input.perception.train.trainId,
                input.observationStamp, input.route, emptyList())) },
            { listOf(block) })
    }

    @Test
    fun `dependency failure preserves fail closed route progression`() {
        val fixture = fixture()
        fixture.dependencies.setFailure = InfrastructureDependencyMutationResult(
            InfrastructureDependencyMutationStatus.EDIT_IN_PROGRESS,
            "test edit overlaps the route"
        )

        fixture.start()
        fixture.dispatch()

        assertTrue(fixture.service.intents.isEmpty())
        assertTrue(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
        assertTrue(fixture.status().message.orEmpty().contains("could not be protected"))
    }

    @Test
    fun `transient rejection retries with a newly observed epoch instead of a cached stamp`() {
        val fixture = fixture()
        var attempts = 0
        fixture.service.onRequest = { intent ->
            if (attempts++ == 0) {
                RouteAdmissionResult.Rejected(
                    RouteAdmissionFailure(
                        RouteAdmissionFailureCode.STALE_OBSERVATION,
                        "test observation expired"
                    )
                )
            } else {
                fixture.service.grant(intent)
            }
        }

        fixture.start()
        fixture.dispatch()
        val rejectedEpoch = fixture.service.intents.single().observationStamp.physicsEpoch
        for (ignored in 0 until 40) {
            if (fixture.service.intents.size >= 2) break
            fixture.dispatch()
        }

        assertEquals(2, fixture.service.intents.size)
        assertTrue(fixture.service.intents[1].observationStamp.physicsEpoch > rejectedEpoch)
        assertEquals(TimetableServiceRunState.RUNNING, fixture.status().state)
    }

    @Test
    fun `pending admission progresses its handle without resubmitting`() {
        val fixture = fixture()
        val handle = RouteAdmissionTestFixtures.handle()
        var progressCalls = 0
        fixture.service.onRequest = { RouteAdmissionResult.Pending(handle, null) }
        fixture.service.onProgress = { supplied ->
            assertSame(handle, supplied)
            if (progressCalls++ == 0) RouteAdmissionResult.Pending(handle, null)
            else fixture.service.grant(fixture.service.intents.single())
        }

        fixture.start()
        fixture.dispatch()
        fixture.dispatch()
        fixture.dispatch()

        assertEquals(1, fixture.service.intents.size)
        assertEquals(2, fixture.service.progressed.size)
        assertEquals(TimetableServiceRunState.RUNNING, fixture.status().state)
    }

    @Test
    fun `controller consumes latest signal snapshot without another dispatcher tick`() {
        val fixture = fixture()
        fixture.service.onRequest = fixture.service::grant
        fixture.start()
        fixture.dispatch()
        val admitted = fixture.service.intents.single()
        fixture.signalRestrictions.publish(
            TrainRestrictionSnapshot(
                sourceId = "test:signal",
                revision = 0L,
                trainId = fixture.train.id,
                observationStamp = admitted.observationStamp,
                route = FrozenRouteBinding.from(admitted.route),
                restrictions = listOf(CurrentSpeedRestriction("test:stop", 0.0))
            )
        )
        fixture.signalRestrictions.completeEpoch(
            admitted.observationStamp,
            setOf(fixture.train.id)
        )

        fixture.tickAto()

        assertEquals(0.0, fixture.ato.runtimeDiagnostics.permittedSpeedMetersPerSecond, 1.0e-9)
        assertTrue(fixture.ato.runtimeDiagnostics.constraints.any { "test:signal/test:stop" in it })
    }

    @Test
    fun `inspection widens the existing controller scan before route admission`() {
        var updates = 0
        var restrictionRevision = 0L
        val source = InspectionSource(
            sourceId = "test:inspection",
            bindingProvider = { input ->
                val segment = input.route.segments.first()
                InspectionDeclarationBinding(
                    sourceId = "test:inspection",
                    trainId = input.trainId,
                    route = FrozenRouteBinding.from(input.route),
                    definitionRevision = 1L,
                    trainProfileRevision = 1L,
                    declarations = listOf(InspectionDeclaration(
                        declarationId = "test:inspection-entry",
                        railId = segment.railId,
                        facing = segment.facing,
                        triggerRouteDistanceMeters = 100.0,
                        inspectionLeadTimeSeconds = 30.0
                    ))
                )
            },
            updateHandler = { input ->
                updates++
                listOf(TrainRestrictionSnapshot(
                    sourceId = "test:inspection",
                    revision = ++restrictionRevision,
                    trainId = "T1",
                    observationStamp = input.observationStamp,
                    route = input.route,
                    restrictions = emptyList()
                ))
            }
        )
        val fixture = fixture(inspectionSources = listOf(source))
        fixture.service.onRequest = fixture.service::grant

        fixture.start()
        fixture.dispatch()

        assertTrue(fixture.service.intents.isEmpty())
        assertEquals(TimetableServiceRunState.WAITING_ROUTE, fixture.status().state)

        fixture.tickAto()
        fixture.dispatch()

        assertEquals(1, fixture.service.intents.size)
        assertEquals(1, updates)
        assertTrue(fixture.ato.publishedState.perceptionHorizonMeters >= 300.0)
    }

    @Test
    fun `inspection floor expands the Basic request without granting it directly`() {
        var revision = 0L
        val source = InspectionSource(
            sourceId = "test:authority-floor",
            bindingProvider = { input ->
                val segment = input.route.segments.first()
                InspectionDeclarationBinding(
                    sourceId = "test:authority-floor",
                    trainId = input.trainId,
                    route = FrozenRouteBinding.from(input.route),
                    definitionRevision = 1L,
                    trainProfileRevision = 1L,
                    declarations = listOf(InspectionDeclaration(
                        declarationId = "test:whole-route-window",
                        railId = segment.railId,
                        facing = segment.facing,
                        triggerRouteDistanceMeters = 0.0,
                        inspectionLeadTimeSeconds = 0.0,
                        minimumRequestedAuthorityEndRouteDistanceMeters = input.route.totalLengthMeters
                    ))
                )
            },
            updateHandler = { input ->
                listOf(TrainRestrictionSnapshot(
                    sourceId = "test:authority-floor",
                    revision = revision++,
                    trainId = input.perception.train.trainId,
                    observationStamp = input.observationStamp,
                    route = input.route,
                    restrictions = emptyList()
                ))
            }
        )
        val fixture = fixture(inspectionSources = listOf(source))
        fixture.service.onRequest = fixture.service::grant

        fixture.start()
        fixture.dispatch()

        assertTrue(fixture.service.intents.isEmpty())
        fixture.tickAto()
        fixture.dispatch()

        val intent = fixture.service.intents.single()
        assertEquals(intent.route.totalLengthMeters,
            intent.desiredAuthority.distanceFromRouteStartMeters, 1.0e-9)
    }

    // These are production Dispatcher-composition tests with scripted Basic results, not proofs
    // of bulb-line physical admission. Basic's real-world loop tests cover that separately.
    @Test
    fun `overlong request retreats before atomic region and retries exactly once`() {
        val fixture = fixture(inspectionSources = listOf(atomicRequestFloor()))
        fixture.service.onRequest = { intent ->
            if (fixture.service.intents.size == 1) overlongRequest(70.0)
            else fixture.service.grant(intent)
        }

        fixture.start()
        fixture.dispatch()
        assertTrue(fixture.service.intents.isEmpty())
        fixture.tickAto()
        fixture.dispatch()

        val intents = fixture.service.intents
        assertEquals(2, intents.size)
        assertEquals(intents.first().route.totalLengthMeters,
            intents.first().desiredAuthority.distanceFromRouteStartMeters, 1.0e-9)
        assertEquals(40.0, intents.last().desiredAuthority.distanceFromRouteStartMeters, 1.0e-9)
        assertEquals(intents.first().route, intents.last().route)
        assertEquals(intents.first().observationStamp, intents.last().observationStamp)
        assertEquals(40.0, fixture.service.grants.single().committedAuthority.distanceFromRouteStartMeters)
        assertEquals(TimetableServiceRunState.RUNNING, fixture.status().state)
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `second overlong rejection does not start a third local retry`() {
        val fixture = fixture(inspectionSources = listOf(atomicRequestFloor()))
        fixture.service.onRequest = {
            if (fixture.service.intents.size == 1) overlongRequest(70.0) else overlongRequest(30.0)
        }

        fixture.start()
        fixture.dispatch()
        fixture.tickAto()
        fixture.dispatch()

        assertEquals(listOf(fixture.service.intents.first().route.totalLengthMeters, 40.0),
            fixture.service.intents.map { it.desiredAuthority.distanceFromRouteStartMeters })
        assertEquals(TimetableServiceRunState.WAITING_ROUTE, fixture.status().state)
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `unusable request ceiling waits without resubmitting or granting authority`() {
        val fixture = fixture(inspectionSources = listOf(atomicRequestFloor()))
        fixture.service.onRequest = { overlongRequest(0.0) }

        fixture.start()
        fixture.dispatch()
        fixture.tickAto()
        fixture.dispatch()
        fixture.dispatch()
        fixture.tickAto()

        assertEquals(1, fixture.service.intents.size)
        assertTrue(fixture.service.grants.isEmpty())
        assertEquals(TimetableServiceRunState.WAITING_ROUTE, fixture.status().state)
        assertEquals(0.0, fixture.ato.runtimeDiagnostics.permittedSpeedMetersPerSecond, 1.0e-9)
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `pending shortened first request keeps zero authority and progresses only its handle`() {
        val fixture = fixture(inspectionSources = listOf(atomicRequestFloor()))
        val handle = RouteAdmissionTestFixtures.handle()
        fixture.service.onRequest = {
            if (fixture.service.intents.size == 1) overlongRequest(70.0)
            else RouteAdmissionResult.Pending(handle, null)
        }
        fixture.service.onProgress = {
            assertSame(handle, it)
            RouteAdmissionResult.Pending(handle, null)
        }

        fixture.start()
        fixture.dispatch()
        fixture.tickAto()
        fixture.dispatch()
        fixture.tickAto()
        fixture.dispatch()

        assertEquals(2, fixture.service.intents.size)
        assertEquals(40.0, fixture.service.intents.last().desiredAuthority.distanceFromRouteStartMeters)
        assertEquals(listOf(handle), fixture.service.progressed)
        assertTrue(fixture.service.grants.isEmpty())
        assertEquals(0.0, fixture.ato.runtimeDiagnostics.permittedSpeedMetersPerSecond, 1.0e-9)
        assertEquals(TimetableServiceRunState.WAITING_ROUTE, fixture.status().state)
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `pending shortened extension retains prior authority without granting its hint`() {
        val fixture = fixture(inspectionSources = listOf(atomicRequestFloor()))
        val handle = RouteAdmissionTestFixtures.handle()
        fixture.service.onRequest = { intent ->
            when (fixture.service.intents.size) {
                1 -> overlongRequest(70.0)
                2 -> fixture.service.grant(intent)
                3 -> overlongRequest(140.0)
                4 -> RouteAdmissionResult.Pending(handle, fixture.service.grants.single())
                else -> error("unexpected request during preparation")
            }
        }
        fixture.service.onProgress = {
            assertSame(handle, it)
            RouteAdmissionResult.Pending(handle, fixture.service.grants.single())
        }

        fixture.start()
        fixture.dispatch()
        fixture.tickAto()
        fixture.dispatch()
        val routeStart = fixture.train.tail.location.arcLength
        for (ignored in 0 until 400) {
            if (fixture.service.intents.size == 4) break
            fixture.tickAto()
            fixture.dispatch()
        }
        assertEquals(4, fixture.service.intents.size, fixture.status().toString())
        assertEquals(140.0, fixture.service.intents.last().desiredAuthority.distanceFromRouteStartMeters)
        assertEquals(40.0, fixture.service.grants.single().committedAuthority.distanceFromRouteStartMeters)

        // Without further Dispatcher progress the controller must still stop at the old grant,
        // not the retry hint or the prepared request boundary.
        repeat(400) { fixture.tickAto() }
        assertTrue(fixture.train.head.location.arcLength <= routeStart + 40.0 + profile.stopToleranceMeters)
        assertEquals(0.0, fixture.train.speed, 1.0e-6)
        fixture.dispatch()
        assertEquals(4, fixture.service.intents.size)
        assertEquals(listOf(handle), fixture.service.progressed)
        assertEquals(TimetableServiceRunState.WAITING_ROUTE, fixture.status().state)
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    private fun overlongRequest(ceilingMeters: Double) = RouteAdmissionResult.Rejected(
        RouteAdmissionFailure(RouteAdmissionFailureCode.REQUEST_RANGE_TOO_LONG,
            "scripted Basic request ceiling", retryAtOrBefore = RouteBoundary(ceilingMeters))
    )

    private fun atomicRequestFloor(): InspectionSource {
        var revision = 0L
        return InspectionSource(
            sourceId = "test:atomic-request-floor",
            bindingProvider = { input ->
                val segment = input.route.segments.first()
                InspectionDeclarationBinding(
                    sourceId = "test:atomic-request-floor", trainId = input.trainId,
                    route = FrozenRouteBinding.from(input.route), definitionRevision = 1L,
                    trainProfileRevision = 1L, declarations = listOf(InspectionDeclaration(
                        declarationId = "test:region-entry", railId = segment.railId,
                        facing = segment.facing, triggerRouteDistanceMeters = 40.0,
                        inspectionLeadTimeSeconds = 30.0,
                        minimumRequestedAuthorityEndRouteDistanceMeters = 100.0
                    ))
                )
            },
            updateHandler = { input ->
                listOf(TrainRestrictionSnapshot(
                    sourceId = "test:atomic-request-floor", revision = revision++,
                    trainId = input.perception.train.trainId, observationStamp = input.observationStamp,
                    route = input.route, restrictions = emptyList()
                ))
            }
        )
    }

    @Test
    fun `full manual starts no new admission but lets an existing handle reach terminal state`() {
        val fixture = fixture()
        val handle = RouteAdmissionTestFixtures.handle()
        fixture.service.onRequest = { RouteAdmissionResult.Pending(handle, null) }
        fixture.service.onProgress = {
            fixture.service.grant(fixture.service.intents.single())
        }
        fixture.service.endResult = false

        fixture.start()
        fixture.dispatch()
        val transfer = fixture.world.onOwnerForTest { fixture.dispatcher.suspendForControlTransfer(
            fixture.world,
            fixture.train.id,
            "test:driver"
        ) }
        assertTrue(transfer is TimetableControlTransferResult.Suspended)

        fixture.dispatch()
        fixture.dispatch()

        assertEquals(1, fixture.service.intents.size)
        assertEquals(1, fixture.service.progressed.size)
        assertTrue(fixture.service.ended.isNotEmpty())
        assertEquals(TimetableControlMode.FULL_MANUAL, fixture.status().controlMode)
    }

    @Test
    fun `manual routed keeps explicit route assistance without treating the detached ATO as driver`() {
        val fixture = fixture()
        fixture.service.onRequest = fixture.service::grant
        fixture.start()

        val transfer = fixture.world.onOwnerForTest { fixture.dispatcher.enterManualRoutedControl(
            fixture.world,
            fixture.train.id,
            "test:driver"
        ) }
        assertTrue(transfer is TimetableControlTransferResult.ManualRouted)
        val manual = ManualController(fixture.train, maxSpeed = 5.0)
        fixture.train.setControllerForTest(manual)
        assertTrue(fixture.world.onOwnerForTest { fixture.dispatcher.bindManualRoutedController(
            fixture.train.id,
            "test:driver",
            manual
        ) })

        fixture.dispatch()

        assertEquals(1, fixture.service.intents.size)
        assertEquals(TimetableControlMode.MANUAL_ROUTED, fixture.status().controlMode)
        assertSame(manual, fixture.train.controller)
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `topology mismatch requires explicit replan and does not retry the failed route`() {
        val fixture = fixture()
        fixture.service.onRequest = {
            RouteAdmissionResult.Rejected(
                RouteAdmissionFailure(
                    RouteAdmissionFailureCode.STALE_TOPOLOGY,
                    "test topology changed"
                )
            )
        }

        fixture.start()
        fixture.dispatch()
        repeat(10) { fixture.dispatch() }

        assertEquals(1, fixture.service.intents.size)
        assertEquals(TimetableServiceRunState.REPLAN_REQUIRED, fixture.status().state)
        assertTrue(fixture.status().message.orEmpty().contains("explicit replan"))
    }

    @Test
    fun `unrelated live topology edit preserves the admitted frozen route and continues running`() {
        val fixture = fixture()
        fixture.service.onRequest = fixture.service::grant
        fixture.start()
        fixture.dispatch()
        val admittedRevision = fixture.service.intents.single().route.topologyRevision

        createStraightRailFromSegments(
            fixture.world,
            PrimeCurveGenerator.buildStraight(20.0, segmentCount = 1)
        )
        assertTrue(fixture.world.topologyState.revision > admittedRevision)
        fixture.dispatch()

        assertEquals(1, fixture.service.intents.size)
        assertEquals(TimetableServiceRunState.RUNNING, fixture.status().state)
        assertEquals(admittedRevision, fixture.service.intents.single().route.topologyRevision)
    }

    @Test
    fun `ordinary stop appends the next mission to the same grant and owner`() {
        val fixture = fixture(withOrdinaryStop = true)
        fixture.service.onRequest = fixture.service::grant

        fixture.start()
        fixture.dispatch()
        val initialRevision = fixture.service.intents.single().route.revision
        for (ignored in 0 until 4_000) {
            if (fixture.service.intents.any { it.route.revision > initialRevision }) break
            fixture.dispatch()
            fixture.tickAto()
        }

        val first = fixture.service.intents.first()
        val appended = fixture.service.intents.firstOrNull { it.route.revision > first.route.revision }
        assertTrue(appended != null, fixture.status().toString())
        appended!!
        assertEquals(first.route.revision + 1L, appended.route.revision)
        assertEquals(first.route.segments, appended.route.segments.take(first.route.segments.size))
        assertEquals(first.owner, appended.owner)
        assertTrue(fixture.service.grants.all { it.grantId === fixture.service.grantId })
        assertTrue(fixture.dependencies.mutations.size >= 2)
        assertEquals(0L, fixture.dependencies.mutations.first().owner.revision)
        assertEquals(3L, fixture.dependencies.mutations.last().owner.revision)
    }

    @Test
    fun `denied extension retains the prior grant and waits before another request`() {
        val fixture = fixture()
        var requestNumber = 0
        fixture.service.onRequest = { intent ->
            if (requestNumber++ == 0) fixture.service.grant(intent)
            else RouteAdmissionResult.Rejected(
                RouteAdmissionFailure(
                    RouteAdmissionFailureCode.STALE_OBSERVATION,
                    "test extension observation expired"
                )
            )
        }

        fixture.start()
        fixture.dispatch()
        for (ignored in 0 until 400) {
            if (fixture.service.intents.size >= 2) break
            fixture.tickAto()
            fixture.dispatch()
        }
        val requestsAtDenial = fixture.service.intents.size
        val grantedBoundary = fixture.service.grants.single().committedAuthority
        fixture.dispatch()

        assertEquals(2, requestsAtDenial)
        assertEquals(requestsAtDenial, fixture.service.intents.size)
        assertEquals(1, fixture.service.grants.size)
        assertTrue(fixture.service.intents.last().desiredAuthority.distanceFromRouteStartMeters >
            grantedBoundary.distanceFromRouteStartMeters)
        assertEquals(TimetableServiceRunState.WAITING_ROUTE, fixture.status().state)
    }

    @Test
    fun `stored train transfers its tail held grant to the next forward service`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val coordinator = AtoWorldCoordinator(WORLD_ID, reportSink = {})
        val data = BasicWorldData(safetyInterlock = coordinator.safetyInterlock)
        TestSimpleATOWorldDataRegistry.register(world, data, coordinator)
        val rail = world.createRailForTest(straightCurve(
            Vector3d(0.0, 0.0, 0.0),
            Vector3d(240.0, 0.0, 0.0)
        ))
        val returnRail = world.createRailForTest(straightCurve(
            Vector3d(240.0, 0.0, 0.0),
            Vector3d(0.0, 0.0, 0.0)
        ))
        IsolatedSimulationThreadMarker.run { CoreRail.connect(rail, returnRail) }
        IsolatedSimulationThreadMarker.run { CoreRail.connect(returnRail, rail) }
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val origin = train.head.location.arcLength
        val depotId = DepotId("north")
        val destinations = OperationalDestinationService(world)
        destinations.register(OperationalDestination(
            "north@out",
            "North out",
            rail.id,
            origin,
            ViewDirection.AB,
            DestinationRole.DepotStablingPointRole("north", "out", usableLengthMeters = 20.0)
        ))
        destinations.register(OperationalDestination(
            "north@in",
            "North in",
            rail.id,
            origin + 100.0,
            ViewDirection.AB,
            DestinationRole.DepotStablingPointRole("north", "in", usableLengthMeters = 20.0)
        ))
        data.wayPoints.add(WayPoint("B", "B", rail.id, origin + 50.0))
        data.wayPoints.add(WayPoint("C", "C", rail.id, origin + 160.0))
        world.simpleAtoWorldData.depots.registerRollingStock(
            RollingStockRecord(
                train.id,
                depotId,
                "test/s100",
                setOf("metro"),
                1,
                10.0,
                20.0,
                emptySet()
            ),
            StablingPointRef(depotId, "out")
        )
        world.simpleAtoWorldData.timetables.add(Timetable(
            "store",
            services = listOf(TimetableService(
                "first",
                steps = listOf(
                    TimetableStep.At(0L, TimetableAction.TakeFromDepot(DepotTrainSelector(depotId))),
                    TimetableStep.At(0L, TimetableAction.GoTo("B")),
                    TimetableStep.At(0L, TimetableAction.StoreAtDepot(depotId, setOf("in")))
                )
            ))
        ))
        world.simpleAtoWorldData.timetables.add(Timetable(
            "depart",
            services = listOf(TimetableService(
                "second",
                steps = listOf(
                    TimetableStep.At(0L, TimetableAction.TakeFromDepot(DepotTrainSelector(depotId))),
                    TimetableStep.At(0L, TimetableAction.GoTo("C"))
                )
            ))
        ))
        val service = FakeAdmissionService().also { it.onRequest = it::grant }
        val signalRestrictions = TrainRestrictionSnapshotStore(WORLD_ID)
        val dependencies = RecordingInfrastructureDependencyService()
        lateinit var fixture: Fixture
        val dispatcher = sharedDispatcher(
            TimetableRouteAdmissionContextProvider {
                fixture.providerCalls++
                TimetableRouteAdmissionContext(fixture.observation(), service)
            },
            signalRestrictions,
            dependencies
        )
        fixture = Fixture(world, data, rail, train, dispatcher, service, signalRestrictions, dependencies)

        val storageStart = world.onOwnerForTest { dispatcher.startService(world, "store", "first") }
        assertTrue(storageStart is TimetableServiceStartResult.Started, storageStart.toString())
        val originalAto = fixture.ato
        fixture.tickAto()
        for (ignored in 0 until 8_000) {
            fixture.dispatch()
            fixture.tickAto()
            if (dispatcher.status(TimetableServiceRunId("store", "first"))?.state ==
                TimetableServiceRunState.COMPLETED
            ) break
        }
        assertEquals(
            TimetableServiceRunState.COMPLETED,
            dispatcher.status(TimetableServiceRunId("store", "first"))?.state
        )
        fixture.dispatch()
        assertTrue(service.ended.isNotEmpty())
        val owner = service.intents.first().owner
        val revisionBeforeDeparture = service.intents.maxOf { it.route.revision }

        val departure = world.onOwnerForTest { dispatcher.startService(world, "depart", "second") }
        assertTrue(departure is TimetableServiceStartResult.Started, departure.toString())
        for (ignored in 0 until 100) {
            fixture.dispatch()
            if (service.intents.any { it.route.revision > revisionBeforeDeparture }) break
            fixture.tickAto()
        }

        val continued = service.intents.firstOrNull { it.route.revision > revisionBeforeDeparture }
        assertTrue(continued != null, dispatcher.status(TimetableServiceRunId("depart", "second")).toString())
        assertEquals(owner, continued!!.owner)
        assertTrue(service.grants.all { it.grantId === service.grantId })
        assertSame(originalAto, train.controller)
    }

    @Test
    fun `sequential services reuse the restriction stream of an externally owned ATO`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val coordinator = AtoWorldCoordinator(WORLD_ID, reportSink = {})
        val data = BasicWorldData(safetyInterlock = coordinator.safetyInterlock)
        TestSimpleATOWorldDataRegistry.register(world, data, coordinator)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(180.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val origin = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", "A", rail.id, origin))
        data.wayPoints.add(WayPoint("B", "B", rail.id, origin + 45.0))
        data.wayPoints.add(WayPoint("C", "C", rail.id, origin + 110.0))
        world.simpleAtoWorldData.timetables.add(Timetable(
            "first",
            services = listOf(TimetableService(
                id = "run",
                trainSelector = TrainSelector.TrainId(train.id),
                steps = listOf(
                    TimetableStep.Check("A"),
                    TimetableStep.At(0L, TimetableAction.GoTo("B"))
                )
            ))
        ))
        world.simpleAtoWorldData.timetables.add(Timetable(
            "second",
            services = listOf(TimetableService(
                id = "run",
                trainSelector = TrainSelector.TrainId(train.id),
                steps = listOf(
                    TimetableStep.Check("B"),
                    TimetableStep.At(0L, TimetableAction.GoTo("C"))
                )
            ))
        ))
        val externalAto = SimpleATO(
            train,
            world,
            KinematicTrainActuator(10.0, profile),
            profile,
            { 0.0 }
        )
        train.setControllerForTest(externalAto)
        val service = FakeAdmissionService().also {
            it.onRequest = it::grant
            it.endResult = true
        }
        val signalRestrictions = TrainRestrictionSnapshotStore(WORLD_ID)
        val dependencies = RecordingInfrastructureDependencyService()
        lateinit var fixture: Fixture
        val dispatcher = sharedDispatcher(
            TimetableRouteAdmissionContextProvider {
                fixture.providerCalls++
                TimetableRouteAdmissionContext(fixture.observation(), service)
            },
            signalRestrictions,
            dependencies
        )
        fixture = Fixture(world, data, rail, train, dispatcher, service, signalRestrictions, dependencies)

        assertTrue(
            world.onOwnerForTest { dispatcher.startService(world, "first", "run") } is TimetableServiceStartResult.Started
        )
        fixture.tickAto()
        for (ignored in 0 until 4_000) {
            fixture.dispatch()
            fixture.tickAto()
            if (dispatcher.status(TimetableServiceRunId("first", "run"))?.state ==
                TimetableServiceRunState.COMPLETED
            ) break
        }
        assertEquals(
            TimetableServiceRunState.COMPLETED,
            dispatcher.status(TimetableServiceRunId("first", "run"))?.state
        )
        fixture.dispatch()
        assertTrue(dependencies.applied.isEmpty())
        val firstOwner = service.intents.first().owner
        val requestCountAfterFirst = service.intents.size

        val secondStart = world.onOwnerForTest { dispatcher.startService(world, "second", "run") }
        assertTrue(secondStart is TimetableServiceStartResult.Started, secondStart.toString())
        fixture.tickAto()
        fixture.dispatch()

        assertTrue(service.intents.size > requestCountAfterFirst)
        assertTrue(service.intents.drop(requestCountAfterFirst).all { it.owner == firstOwner })
        assertEquals(1, dependencies.applied.size)
        assertFalse(world.simpleAtoWorldData.atoCoordinator.isStalled)
        assertSame(externalAto, train.controller)
    }

    private fun fixture(
        withOrdinaryStop: Boolean = false,
        inspectionSources: List<InspectionSource> = emptyList(),
        realEditGuards: Boolean = false
    ): Fixture {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val coordinator = AtoWorldCoordinator(WORLD_ID, reportSink = {})
        val data = BasicWorldData(safetyInterlock = coordinator.safetyInterlock)
        TestSimpleATOWorldDataRegistry.register(world, data, coordinator)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(400.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val origin = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", "A", rail.id, origin))
        data.wayPoints.add(WayPoint("B", "B", rail.id, origin + if (withOrdinaryStop) 60.0 else 250.0))
        if (withOrdinaryStop) data.wayPoints.add(WayPoint("C", "C", rail.id, origin + 180.0))
        val steps = mutableListOf<TimetableStep>(
            TimetableStep.Check("A"),
            TimetableStep.At(0L, TimetableAction.GoTo("B"))
        )
        if (withOrdinaryStop) {
            steps += TimetableStep.At(0L, TimetableAction.Stop(1L))
            steps += TimetableStep.At(0L, TimetableAction.GoTo("C"))
        }
        world.simpleAtoWorldData.timetables.add(Timetable(
            "tt",
            services = listOf(TimetableService(
                id = "service",
                trainSelector = TrainSelector.TrainId(train.id),
                steps = steps
            ))
        ))
        val service = FakeAdmissionService()
        val dependencies = RecordingInfrastructureDependencyService()
        val registry = if (realEditGuards) InfrastructureCoordinationRegistry(world::topologyRevision,
            { resource -> when (resource) {
                is RailIntervalResource -> world.getRail(resource.railId) != null
                is StableInfrastructureResource -> resource.typeId == "simplesignals:fixed-block" &&
                    resource.resourceId == "test:planned-block"
            } }, { world.getRail(it) != null }, { world.topologyState.structuralNodeWithPorts(it.ports) != null }) else null
        val requester = Any()
        val dependencyService = if (registry == null) dependencies else object : InfrastructureDependencyService {
            override fun set(request: InfrastructureDependencyRequest): InfrastructureDependencyMutationResult {
                val result = registry.setInfrastructureDependencies(requester, request)
                if (result.successful) dependencies.set(request)
                return result
            }
            override fun remove(owner: InfrastructureOwner): InfrastructureDependencyMutationResult {
                dependencies.removeFailure?.let { return it }
                val result = registry.removeInfrastructureDependencies(requester, owner)
                if (result.successful) dependencies.remove(owner)
                return result
            }
        }
        val signalRestrictions = TrainRestrictionSnapshotStore(WORLD_ID)
        lateinit var fixture: Fixture
        val dispatcher = sharedDispatcher(
            TimetableRouteAdmissionContextProvider {
                fixture.providerCalls++
                TimetableRouteAdmissionContext(fixture.observation(), service)
            },
            signalRestrictions,
            dependencyService
        ) { inspectionRuntime ->
            world.registerTopologyChangeListener(inspectionRuntime::invalidateTopology)
            inspectionRuntime.replaceSources(inspectionSources)
        }
        fixture = Fixture(world, data, rail, train, dispatcher, service, signalRestrictions, dependencies, registry)
        return fixture
    }

    private fun sharedDispatcher(
        contextProvider: TimetableRouteAdmissionContextProvider,
        signalRestrictions: TrainRestrictionSnapshotStore,
        infrastructureDependencies: InfrastructureDependencyService = acceptingInfrastructureDependencyService(),
        configureInspection: (InspectionRuntime) -> Unit = {}
    ): TimetableDispatcher {
        val inspectionRuntime = InspectionRuntime(signalRestrictions) { _, _, failure -> throw failure }
        configureInspection(inspectionRuntime)
        return TimetableDispatcher(
            authorityStoppingMarginMeters = profile.safetyDistanceMeters,
            routeAdmissionContextProvider = contextProvider,
            signalRestrictionSnapshots = signalRestrictions,
            inspectionRuntime = inspectionRuntime,
            infrastructureDependencyService = infrastructureDependencies,
            atoFactory = TimetableAtoFactory { selectedTrain, selectedWorld ->
                SimpleATO(
                    selectedTrain,
                    selectedWorld,
                    KinematicTrainActuator(10.0, profile),
                    profile,
                    inspectionRuntime::maximumDeclaredLeadTimeSeconds
                )
            }
        )
    }

    private fun straightCurve(from: Vector3d, to: Vector3d): PrimeCurve {
        val tangent = Vector3d(to).sub(from).normalize()
        val normal = Vector3d(0.0, 1.0, 0.0)
        val length = from.distance(to)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(from, tangent, normal, 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(to, tangent, normal, length, Curve.SegmentType.LINE_END)
        ))
    }

    private inner class Fixture(
        val world: CoreWorld,
        val data: BasicWorldData,
        val rail: CoreRail,
        val train: CoreTrain,
        val dispatcher: TimetableDispatcher,
        val service: FakeAdmissionService,
        val signalRestrictions: TrainRestrictionSnapshotStore,
        val dependencies: RecordingInfrastructureDependencyService,
        val editRegistry: InfrastructureCoordinationRegistry? = null
    ) {
        var epoch = 0L
        var providerCalls = 0
        var dispatchTicks = 0

        val ato: SimpleATO
            get() = train.controller as SimpleATO

        fun start() {
            assertTrue(
                world.onOwnerForTest { dispatcher.startService(world, "tt", "service") } is TimetableServiceStartResult.Started
            )
            tickAto()
        }

        fun tickAto() {
            AsyncTickTestSupport.run { ato.tick(50.0) }
            ato.commit()
        }

        fun dispatch() {
            dispatchTicks++
            world.onOwnerForTest { dispatcher.tick(world) }
        }

        fun status(): TimetableServiceRunSnapshot =
            checkNotNull(dispatcher.status(TimetableServiceRunId("tt", "service")))

        fun editDecision(impact: TopologyEditImpact): TopologyEditStatus {
            val registry = checkNotNull(editRegistry)
            val editor = Any()
            val permit = registry.acquireEditPermit(editor,
                TopologyEditRequest(world.topologyState.revision, InfrastructureOwner("test:editor", 1L), impact))
            try { return permit.decision.status }
            finally { registry.releaseEditPermit(editor, permit.id) }
        }

        fun observation(): RailwayObservation {
            val nextEpoch = ++epoch
            val headLocation = train.head.location
            val tailLocation = train.tail.location
            val direction = headLocation.facing
            val head = RailPositionSnapshot.capture(headLocation, direction)
            val tail = RailPositionSnapshot.capture(tailLocation, direction)
            val body = RailTraversalSegmentSnapshot(
                rail.id,
                direction,
                tail.arcLengthInAbMeters,
                head.arcLengthInAbMeters
            )
            val frame = TrainFrameSnapshot.create(
                trainId = train.id,
                trainName = train.name,
                revision = nextEpoch,
                physicsEpoch = nextEpoch,
                speedMetersPerSecond = train.speed,
                derailed = train.isDerailed,
                reversed = train.isReversed,
                cars = emptyList(),
                head = head,
                tail = tail,
                occupiedIntervals = emptyList(),
                bodyTraversal = listOf(body),
                sweptSpans = emptyList(),
                headTraversal = emptyList(),
                tailTraversal = emptyList()
            )
            return RailwayObservation.create(
                RailwayStateStamp(WORLD_ID, nextEpoch, world.topologyState.revision),
                listOf(RailwayTrainObservation.create(
                    trainId = frame.trainId,
                    trainName = frame.trainName,
                    frameRevision = frame.revision,
                    speedMetersPerSecond = frame.speedMetersPerSecond,
                    derailed = frame.derailed,
                    reversed = frame.reversed,
                    head = frame.head,
                    tail = frame.tail,
                    occupiedIntervals = frame.occupiedIntervals,
                    bodyTraversal = frame.bodyTraversal,
                    sweptSpans = frame.sweptSpans,
                    headTraversal = frame.headTraversal,
                    tailTraversal = frame.tailTraversal
                )),
                RailwayOccupancySnapshot.create(nextEpoch, listOf(frame)),
                ActiveRailContinuationsSnapshot.empty(world.topologyState.revision)
            )
        }
    }

    private class FakeAdmissionService : RouteResourceAdmissionService {
        val grantId: RouteGrantId = RouteAdmissionTestFixtures.grantId()
        var onRequest: (RouteResourceIntent) -> RouteAdmissionResult = { error("unexpected request") }
        var onProgress: (RouteAdmissionHandle) -> RouteAdmissionResult = { error("unexpected progress") }
        var onSuspendReversal: (RouteGrantId) -> RouteGrantReversalResult = {
            error("unexpected reversal suspension")
        }
        var onRebaseReversal: (RouteResourceIntent) -> RouteGrantReversalResult = {
            error("unexpected reversal rebase")
        }
        var endResult = false
        val intents = mutableListOf<RouteResourceIntent>()
        val grants = mutableListOf<RouteGrantSnapshot>()
        val progressed = mutableListOf<RouteAdmissionHandle>()
        val ended = mutableListOf<RouteGrantId>()

        override fun request(intent: RouteResourceIntent): RouteAdmissionResult {
            intents += intent
            return onRequest(intent)
        }

        override fun progress(handle: RouteAdmissionHandle): RouteAdmissionResult {
            progressed += handle
            return onProgress(handle)
        }

        override fun suspendReleaseForReversal(grantId: RouteGrantId): RouteGrantReversalResult =
            onSuspendReversal(grantId)

        override fun rebaseAfterReversal(intent: RouteResourceIntent): RouteGrantReversalResult =
            onRebaseReversal(intent)

        override fun endGrant(grantId: RouteGrantId): Boolean {
            ended += grantId
            return endResult
        }

        fun grant(intent: RouteResourceIntent): RouteAdmissionResult.Granted {
            val grant = RouteGrantSnapshot(
                grantId = grantId,
                owner = intent.owner,
                trainId = intent.trainId,
                routeId = intent.route.routeId,
                routeRevision = intent.route.revision,
                observationStamp = intent.observationStamp,
                committedAuthority = intent.desiredAuthority
            )
            grants += grant
            return RouteAdmissionResult.Granted(grant)
        }
    }

    private companion object {
        const val WORLD_ID = "dispatcher-shared-admission"
    }
}
