package work.art1st.javarailways.simpleato.admission

import work.art1st.javarailways.basic.api.ato.CarDynamicsProfile
import work.art1st.javarailways.basic.api.ato.TrainDynamicsProfile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.api.route.WayPoint
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.*
import work.art1st.javarailways.basic.control.HandleTrainActuator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.train.*
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.api.restriction.CurrentSpeedRestriction
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotStore
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import work.art1st.javarailways.simpleato.timetable.*

/** The admission input is controlled; controller, handle dynamics and Core publication are real. */
class AdmittedSpeedPhysicalIntegrationTest {
    @Test
    fun `pending authority holds actual body and full route grant speed is bounded by independent signal speed`() {
        for (signalCap in listOf<Double?>(null, 0.5)) {
            PhysicsCoordinator(1, automaticFallback = false).use { physics ->
                TestWorldFixture.create(physics).use { fixture ->
                    val world = fixture.world
                    val coordinator = AtoWorldCoordinator(WORLD, reportSink = {})
                    TestSimpleATOWorldDataRegistry.register(world,
                        BasicWorldData(safetyInterlock = coordinator.safetyInterlock), coordinator)
                    try {
                        val rail = createStraightRailFromSegments(world,
                            PrimeCurveGenerator.buildStraight(200.0, segmentCount = 1))
                        val train = createTrainWithConfig("speed-consumer", rail, world, listOf(10.0))
                        val origin = train.head.location.arcLength
                        val basic = world.simpleAtoWorldData
                        // Basic WayPoints are the existing movement references, not synthetic controller inputs.
                        val waypoints = world.basicWorldData
                        waypoints.wayPoints.add(WayPoint("A", rail.id, origin))
                        waypoints.wayPoints.add(WayPoint("B", rail.id, origin + 20.0))
                        basic.timetables.add(Timetable("speed", services = listOf(TimetableService("service",
                            TrainSelector.TrainId(train.id), listOf(TimetableStep.Check("A"),
                                TimetableStep.At(0L, TimetableAction.GoTo("B")))))))
                        val service = ControlledAdmission()
                        val signals = TrainRestrictionSnapshotStore(WORLD)
                        val inspection = InspectionRuntime(signals) { _, _, failure -> throw failure }
                        val profile = AtoSafetyProfile(accelerationMetersPerSecondSquared = 1.0,
                            serviceDecelerationMetersPerSecondSquared = 2.0, reactionTimeSeconds = 0.0,
                            safetyDistanceMeters = 2.0)
                        val dispatcher = TimetableDispatcher(
                            authorityStoppingMarginMeters = profile.safetyDistanceMeters,
                            routeAdmissionContextProvider = TimetableRouteAdmissionContextProvider {
                                TimetableRouteAdmissionContext(RailwayObservation.fromCore(WORLD, world.latestWorldFrame()), service)
                            },
                            signalRestrictionSnapshots = signals,
                            inspectionRuntime = inspection,
                            infrastructureDependencyService = acceptingInfrastructureDependencyService(),
                            atoFactory = TimetableAtoFactory { selectedTrain, selectedWorld ->
                                SimpleATO(selectedTrain, selectedWorld,
                                    HandleTrainActuator(selectedTrain, Dynamics, listOf(CarDynamics), 9.8),
                                    profile, inspection::maximumDeclaredLeadTimeSeconds)
                            })
                        val started = world.onOwnerForTest { dispatcher.startService(world, "speed", "service") }
                        assertTrue(started is TimetableServiceStartResult.Started, started.toString())
                        val ato = train.controller as SimpleATO
                        physics.start()
                        fun tick() {
                            physics.stepSimulation(50.0).await()
                            world.onOwnerForTest { dispatcher.tick(world) }
                            assertFalse(coordinator.isStalled, coordinator.lastStall.toString())
                        }
                        val initialHead = train.publishedFrame.head
                        repeat(12) { tick() }
                        assertNotNull(service.pendingIntent, "the real Dispatcher must have requested admission")
                        assertTrue(service.progressCalls > 0)
                        assertEquals(1, service.requestCalls, "a pending handle is progressed, not resubmitted")
                        assertEquals(initialHead, train.publishedFrame.head)
                        assertEquals(0.0, train.publishedFrame.speedMetersPerSecond, 0.0)

                        if (signalCap != null) {
                            val intent = checkNotNull(service.pendingIntent)
                            signals.publish(TrainRestrictionSnapshot("test:independent-signal", 0L, train.id,
                                intent.observationStamp, FrozenRouteBinding.from(intent.route),
                                listOf(CurrentSpeedRestriction("test:tighter-speed", signalCap))))
                            signals.completeEpoch(intent.observationStamp, setOf(train.id))
                        }
                        service.ready = true
                        val expectedCap = signalCap ?: GRANT_CAP
                        var peakSpeed = 0.0
                        var completeRouteGranted = false
                        var committedPendingTicks = 0
                        repeat(1200) { index ->
                            if (index == 120) service.extensionReady = true
                            tick()
                            if (!service.extensionReady && service.latestGrant != null && service.extensionPending) committedPendingTicks++
                            val grant = service.latestGrant
                            if (grant != null && grant.committedAuthority.distanceFromRouteStartMeters == service.lastIntent!!.route.totalLengthMeters) {
                                completeRouteGranted = true
                                assertEquals(GRANT_CAP, grant.maximumSpeedMetersPerSecond)
                            }
                            if (grant != null) {
                                assertTrue(ato.runtimeDiagnostics.permittedSpeedMetersPerSecond <= expectedCap + 1e-9,
                                    ato.runtimeDiagnostics.toString())
                                peakSpeed = maxOf(peakSpeed, train.publishedFrame.speedMetersPerSecond)
                                assertTrue(train.publishedFrame.speedMetersPerSecond <= expectedCap + 0.01,
                                    "physical speed=${train.publishedFrame.speedMetersPerSecond}, cap=$expectedCap")
                            }
                        }
                        assertTrue(committedPendingTicks > 0, "a pending extension must preserve the committed speed ceiling")
                        assertTrue(completeRouteGranted, "full physical authority must not remove the speed ceiling")
                        assertTrue(peakSpeed > expectedCap * 0.75, "the train must actually accelerate under the selected cap")
                        assertTrue(train.publishedFrame.head.arcLengthInAbMeters > origin + 2.0,
                            "assert actual Core displacement, not only diagnostic constraints")
                    } finally {
                        TestSimpleATOWorldDataRegistry.unregister(world)
                    }
                }
            }
        }
    }

    private class ControlledAdmission : RouteResourceAdmissionService {
        val handle = RouteAdmissionTestFixtures.handle()
        val grantId = RouteAdmissionTestFixtures.grantId()
        var ready = false
        var extensionReady = false
        var extensionPending = false
        var requestCalls = 0
        var progressCalls = 0
        var pendingIntent: RouteResourceIntent? = null
        var lastIntent: RouteResourceIntent? = null
        var latestGrant: RouteGrantSnapshot? = null
        override fun request(intent: RouteResourceIntent): RouteAdmissionResult {
            requestCalls++
            lastIntent = intent
            pendingIntent = intent
            if (!ready || latestGrant != null && !extensionReady) {
                extensionPending = latestGrant != null
                return RouteAdmissionResult.Pending(handle, latestGrant)
            }
            return grant(intent)
        }
        override fun progress(handle: RouteAdmissionHandle): RouteAdmissionResult {
            assertSame(this.handle, handle)
            progressCalls++
            return if (ready && (latestGrant == null || extensionReady)) grant(checkNotNull(pendingIntent))
                else RouteAdmissionResult.Pending(handle, latestGrant)
        }
        private fun grant(intent: RouteResourceIntent): RouteAdmissionResult {
            lastIntent = intent
            val boundary = if (latestGrant == null) RouteBoundary(minOf(18.0, intent.desiredAuthority.distanceFromRouteStartMeters))
                else intent.desiredAuthority
            val result = RouteGrantSnapshot(grantId, intent.owner, intent.trainId, intent.route.routeId,
                intent.route.revision, intent.observationStamp, boundary,
                maximumSpeedMetersPerSecond = GRANT_CAP)
            latestGrant = result
            return RouteAdmissionResult.Granted(result)
        }
        override fun suspendReleaseForReversal(grantId: RouteGrantId): RouteGrantReversalResult = error("no reversal")
        override fun rebaseAfterReversal(intent: RouteResourceIntent): RouteGrantReversalResult = error("no reversal")
        override fun endGrant(grantId: RouteGrantId) = true
    }

    private object Dynamics : TrainDynamicsProfile {
        override val maxSpeed = 10.0
        override val powerHandles = listOf("P1")
        override val brakeHandles = listOf("B1", "EB")
        override val brakeApplyTime = 0.0
        override val brakeReleaseTime = 0.0
        override val adhesionCoefficient = 1.0
        override val tractionJerk = 100.0
        override val brakeJerk = 100.0
        override fun normalizedPowerScale(handle: String) = if (handle == "P1") 1.0 else 0.0
        override fun normalizedBrakeScale(handle: String) = if (handle in brakeHandles) 1.0 else 0.0
        override fun resistanceForceAt(speed: Double) = 0.0
    }
    private object CarDynamics : CarDynamicsProfile {
        override val mass = 10_000.0
        override val effectiveMass = mass
        override val brakeMaxForce = 20_000.0
        override val motorAdhesionMassRatio = 1.0
        override val brakeAdhesionMassRatio = 1.0
        override fun motorForceAt(speed: Double) = 10_000.0
        override fun resistanceForceAt(speed: Double, defaultResistance: (Double) -> Double) = 0.0
    }
    private companion object {
        const val WORLD = "admitted-speed-physical"
        const val GRANT_CAP = 2.0
    }
}
