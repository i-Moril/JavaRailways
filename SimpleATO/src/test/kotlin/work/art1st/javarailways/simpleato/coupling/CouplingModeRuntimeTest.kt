package work.art1st.javarailways.simpleato.coupling

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.*
import work.art1st.javarailways.basic.control.HandleTrainActuator
import work.art1st.javarailways.basic.control.RealisticManualController
import work.art1st.javarailways.basic.control.TrainDriveDirection
import work.art1st.javarailways.basic.infrastructure.admission.RouteResourceAdmissionRuntime
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.train.*
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.api.coupling.*
import work.art1st.javarailways.simpleato.api.mission.*
import work.art1st.javarailways.simpleato.api.restriction.CurrentSpeedRestriction
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.simpleato.control.ImmediateEquipmentFixture
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import work.art1st.javarailways.simpleato.timetable.TimetableRouteAdmissionContext
import work.art1st.javarailways.simpleato.timetable.TimetableRouteAdmissionContextProvider

/** Real Basic reservations, physical handle dynamics and Core contact, with no Depot duty. */
class CouplingModeRuntimeTest {
    @ParameterizedTest
    @CsvSource("false,0.0", "true,0.0", "false,0.9", "true,0.9")
    fun `ordinary manual or ATO mode links actual unassigned trains then permits fresh manual takeover`(automatic: Boolean, gap: Double) {
        Fixture(automatic, gap).use { f ->
            val before = f.moving.publishedFrame.head
            val entered = f.owner { f.mode.enter(f.moving.id, f.target.id) }
            assertInstanceOf(CouplingModeEntryResult.Accepted::class.java, entered, entered.toString())
            f.physics.start()
            var peak = 0.0
            var steps = 0
            while (f.mode.status(f.moving.id)?.phase != CouplingModePhase.COMPLETED && steps++ < 1000) {
                f.manual?.setHandle(if (f.moving.speed < 0.1) "P" else "N")
                f.targetManual.setHandle("P") // The operation's target hold must retain stationarity.
                f.tick()
                peak = maxOf(peak, f.moving.speed)
                assertTrue(f.moving.speed <= CAP + 0.02, "peak=${f.moving.speed}")
            }
            assertEquals(CouplingModePhase.COMPLETED, f.mode.status(f.moving.id)?.phase,
                "${f.mode.status(f.moving.id)}; stalls=${f.stalls}; ato=${f.ato?.runtimeDiagnostics}")
            assertEquals(1, f.world.allTrains.size)
            assertEquals(2, f.moving.cars.size)
            if (gap > 0.0) {
                assertTrue(peak > 0.0, "real physical actuation must approach the target")
                assertNotEquals(before, f.moving.publishedFrame.head)
            }
            assertEquals(0.0, f.moving.speed)
            assertNull(f.moving.controller)
            assertFalse(f.moving.movementHeld)
            assertEquals(listOf(f.moving, f.target), f.releasedControls)
            assertTrue(f.world.infrastructureCoordination.claimSnapshots().isEmpty())
            assertTrue(f.runtime.activeAdmissions().isEmpty())
            assertTrue(f.data.depots.allFixedUnitAssignments().isEmpty())
            assertTrue(f.data.depots.unresolvedFormationChanges().isEmpty())
            assertTrue(f.stalls.isEmpty(), f.stalls.toString())
            assertFalse(f.coordinator.isStalled)
            // RealisticManualController must drive the actual new body after normal re-acquisition.
            val driver = f.owner { f.newManual(f.moving).also { f.moving.setController(it) } }
            driver.setHandle("P")
            val stoppedHead = f.moving.publishedFrame.head
            repeat(20) { f.tick() }
            assertNotEquals(stoppedHead, f.moving.publishedFrame.head)
            assertTrue(f.moving.speed > 0.0)
            assertFalse(driver.isAtpAcknowledgementRequired)
        }
    }

    @Test
    fun `world Stall retains an in flight coupling session and its operational holds until reset`() {
        Fixture(true, 0.0).use { f ->
            f.owner { assertInstanceOf(CouplingModeEntryResult.Accepted::class.java,
                f.mode.enter(f.moving.id, f.target.id)) }
            val phase = f.mode.status(f.moving.id)?.phase
            val controllers = f.moving.controller to f.target.controller
            f.coordinator.stall(work.art1st.javarailways.simpleato.api.ato.AtoStallReport(
                WORLD, f.moving.id, 1, "test", "world stalled", "test", null, null, null, null, emptyMap()))
            f.physics.start()
            repeat(10) { f.tick() }
            assertEquals(phase, f.mode.status(f.moving.id)?.phase)
            assertSame(controllers.first, f.moving.controller)
            assertSame(controllers.second, f.target.controller)
            assertTrue(f.releasedControls.isEmpty())
            assertTrue(f.data.controlRuntime.isMovementHeld(f.target))
            f.coordinator.reset()
            var steps = 0
            while (f.mode.status(f.moving.id)?.phase != CouplingModePhase.COMPLETED && steps++ < 100) f.tick()
            assertEquals(CouplingModePhase.COMPLETED, f.mode.status(f.moving.id)?.phase,
                f.mode.status(f.moving.id).toString())
            assertEquals(listOf(f.moving, f.target), f.releasedControls)
        }
    }

    @Test
    fun `contact mission follows an accepted ordinary mission before its first publication`() {
        Fixture(true).use { f ->
            val ato = checkNotNull(f.ato)
            f.owner {
                f.world.wayPoints.add(WayPoint("queued-origin", f.rail.id, 99.0))
                f.world.wayPoints.add(WayPoint("queued-destination", f.rail.id, 150.0))
                val queued = ato.submitMission(AtoMissionRequest("queued-ordinary", 41L,
                    WayPointRef("queued-origin"), listOf(AtoMissionPoint.Stop(WayPointRef("queued-destination")))))
                assertInstanceOf(AtoMissionSubmissionResult.Accepted::class.java, queued)
                assertNull(ato.currentMission, "the accepted mission has not reached a physics publication")
                val entered = f.mode.enter(f.moving.id, f.target.id)
                assertInstanceOf(CouplingModeEntryResult.Accepted::class.java, entered, entered.toString())
                assertEquals(CouplingModePhase.APPROACHING, f.mode.status(f.moving.id)?.phase)
            }
            f.physics.start()
            f.tick()
            assertEquals(42L, ato.currentMission?.revision)
            assertInstanceOf(AtoMissionOrigin.FormationContact::class.java, ato.currentMission?.origin)
            var steps = 0
            while (f.mode.status(f.moving.id)?.phase != CouplingModePhase.COMPLETED && steps++ < 1000) f.tick()
            assertEquals(CouplingModePhase.COMPLETED, f.mode.status(f.moving.id)?.phase,
                f.mode.status(f.moving.id).toString())
            assertEquals(1, f.world.allTrains.size)
            assertEquals(2, f.moving.cars.size)
            assertTrue(f.world.infrastructureCoordination.claimSnapshots().isEmpty())
            assertTrue(f.runtime.activeAdmissions().isEmpty())
            assertTrue(f.stalls.isEmpty(), f.stalls.toString())
            assertFalse(f.coordinator.isStalled)
        }
    }

    @Test
    fun `automatic ordinary contact still waits for an independent zero speed restriction`() {
        Fixture(true).use { f ->
            f.owner { assertInstanceOf(CouplingModeEntryResult.Accepted::class.java, f.mode.enter(f.moving.id, f.target.id)) }
            f.red(true)
            val before = f.moving.publishedFrame.head
            f.physics.start()
            repeat(20) { f.tick() }
            assertEquals(before, f.moving.publishedFrame.head)
            assertEquals(0.0, f.moving.speed)
            assertEquals(2, f.world.allTrains.size)
            f.red(false)
            var steps = 0
            while (f.world.allTrains.size == 2 && steps++ < 1000) f.tick()
            repeat(2) { f.tick() }
            assertEquals(CouplingModePhase.COMPLETED, f.mode.status(f.moving.id)?.phase)
            assertTrue(f.stalls.isEmpty(), f.stalls.toString())
        }
    }

    @Test
    fun `entry distance and existing assignment rejection do not change controllers or resources`() {
        Fixture(false, 6.0).use { f ->
            val frame = f.moving.publishedFrame
            val controller = f.moving.controller
            assertInstanceOf(CouplingModeEntryResult.Rejected::class.java, f.owner { f.mode.enter(f.moving.id, f.target.id) })
            assertSame(frame, f.moving.publishedFrame)
            assertSame(controller, f.moving.controller)
            assertTrue(f.world.infrastructureCoordination.claimSnapshots().isEmpty())
            assertFalse(f.data.controlRuntime.isMovementHeld(f.moving))
            val wider = CouplingModeRuntime(f.world, CouplingModeSettings(7.0, CAP), f.provider)
            f.owner { f.data.depots.claimFixedFormation(f.moving.id, f.moving.cars.map { it.id }, "existing-duty") }
            val claims = f.data.depots.allFixedUnitAssignments()
            assertInstanceOf(CouplingModeEntryResult.Rejected::class.java, f.owner { wider.enter(f.moving.id, f.target.id) })
            assertEquals(claims, f.data.depots.allFixedUnitAssignments())
            assertSame(controller, f.moving.controller)
            assertFalse(f.coordinator.isStalled)
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `ordinary source or contact admission denial cleans this operation without stalling the world`(contact: Boolean) {
        Fixture(false).use { f ->
            val controllers = f.moving.controller to f.target.controller
            if (contact) f.policyAllowed = false else f.rejectSourceId = f.target.id
            val denied = f.owner { f.mode.enter(f.moving.id, f.target.id) }
            assertInstanceOf(CouplingModeEntryResult.Rejected::class.java, denied, denied.toString())
            assertEquals(CouplingModePhase.REJECTED, f.mode.status(f.moving.id)?.phase)
            assertSame(controllers.first, f.moving.controller)
            assertSame(controllers.second, f.target.controller)
            assertTrue(f.world.infrastructureCoordination.claimSnapshots().isEmpty())
            assertTrue(f.runtime.activeAdmissions().isEmpty())
            assertFalse(f.data.controlRuntime.isMovementHeld(f.moving))
            assertFalse(f.data.controlRuntime.isMovementHeld(f.target))
            assertTrue(f.data.depots.allFixedUnitAssignments().isEmpty())
            assertTrue(f.stalls.isEmpty(), f.stalls.toString())
            assertFalse(f.coordinator.isStalled)
            f.policyAllowed = true
            f.rejectSourceId = null
            assertInstanceOf(CouplingModeEntryResult.Accepted::class.java, f.owner { f.mode.enter(f.moving.id, f.target.id) },
                "a denied entry must not leak a source grant or prevent retry")
        }
    }

    private class Fixture(automatic: Boolean, gap: Double = 0.9) : AutoCloseable {
        val physics = PhysicsCoordinator(1, automaticFallback = false)
        private val physical = TestWorldFixture.create(physics)
        val world = physical.world
        val coordinator = AtoWorldCoordinator(WORLD, reportSink = {})
        init { TestSimpleATOWorldDataRegistry.register(world, BasicWorldData(safetyInterlock = coordinator.safetyInterlock), coordinator) }
        val data get() = world.simpleAtoWorldData
        private val equipment = ImmediateEquipmentFixture()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, 1))
        private fun train(id: String, head: Double): CoreTrain = owner {
            val anchor = RailLocationWithFacing(rail.viewAB, head)
            val car = CoreCar.create("$id-car", CoreBogie.create(anchor), CoreBogie.create(anchor),
                length = 10.0, bogieAOffset = 3.0, bogieBOffset = -3.0,
                connectorLengthAtA = 0.3, connectorLengthAtB = 0.3, world = world)
            CoreTrain.create(id, listOf(car), world).also { it.setLocation(anchor); it.commitMaintenancePlacement() }
        }
        val moving = train("moving", 99.0)
        val target = train("target", 109.6 + gap)
        fun newManual(train: CoreTrain) = RealisticManualController(train, equipment.profile,
            List(train.cars.size) { equipment.car }, 9.80665).also { it.setDirection(TrainDriveDirection.FORWARD) }
        val manual = if (automatic) null else owner { newManual(moving).also(moving::setController) }
        val ato = if (!automatic) null else SimpleATO(moving, world,
            HandleTrainActuator(moving, equipment.profile, listOf(equipment.car), 9.80665),
            AtoSafetyProfile(accelerationMetersPerSecondSquared = 1.0, serviceDecelerationMetersPerSecondSquared = 1.0,
                reactionTimeSeconds = 0.0, safetyDistanceMeters = 0.1), inspectionMaximumLeadTimeSeconds = { 0.0 }).also {
            owner { moving.setController(it) }
        }
        val targetManual = owner { newManual(target).also(target::setController) }
        init { world.basicWorldData.commandSupervisor = data.controlRuntime }
        var observation = RailwayObservation.fromCore(WORLD, world.latestWorldFrame())
        val requester = Any()
        val stalls = arrayListOf<String>()
        var policyAllowed = true
        var rejectSourceId: String? = null
        var contactIntent: RouteResourceIntent? = null
        val runtime = RouteResourceAdmissionRuntime.create(WORLD, world, world.infrastructureCoordination,
            Any(), { observation }, { emptyList() }, { _, _ -> null }, { _, _ -> null },
            authorizeFormationRoute = { _, _ -> if (policyAllowed) FormationRoutePolicyResult.Accepted(emptyList())
                else FormationRoutePolicyResult.Rejected(RouteAdmissionFailure(RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
                    "test contact policy denied")) }, stallWorld = { detail, _ -> stalls += detail })
        private val service = object : RouteResourceAdmissionService {
            override fun request(intent: RouteResourceIntent): RouteAdmissionResult = if (intent.trainId == rejectSourceId)
                RouteAdmissionResult.Rejected(RouteAdmissionFailure(RouteAdmissionFailureCode.WORLD_UNAVAILABLE, "test source denied"))
                else runtime.request(requester, intent)
            override fun progress(handle: RouteAdmissionHandle) = runtime.progress(requester, handle)
            override fun prepareFormationChange(intent: FixedFormationChangeIntent) = runtime.prepareFormationChange(requester, intent)
            override fun sealFormationPublication(handle: FormationChangeHandle) = runtime.sealFormationPublication(requester, handle)
            override fun formationChangeState(handle: FormationChangeHandle) = runtime.formationChangeState(requester, handle)
            override fun abortFormationChange(handle: FormationChangeHandle) = runtime.abortFormationChange(requester, handle)
            override fun requestCouplingApproach(handle: FormationChangeHandle, intent: RouteResourceIntent): RouteAdmissionResult {
                contactIntent = intent
                return runtime.requestCouplingApproach(requester, handle, intent)
            }
            override fun completeCouplingContact(handle: FormationChangeHandle) = runtime.completeCouplingContact(requester, handle)
            override fun bindFormationOutputs(handle: FormationChangeHandle, outputBodyIntents: List<RouteResourceIntent>) =
                runtime.bindFormationOutputs(requester, handle, outputBodyIntents)
            override fun finishFormationForManual(handle: FormationChangeHandle, outputGrantId: RouteGrantId) =
                runtime.finishFormationForManual(requester, handle, outputGrantId)
            override fun suspendReleaseForReversal(grantId: RouteGrantId) = runtime.suspendReleaseForReversal(requester, grantId)
            override fun rebaseAfterReversal(intent: RouteResourceIntent) = runtime.rebaseAfterReversal(requester, intent)
            override fun endGrant(grantId: RouteGrantId) = runtime.endGrant(requester, grantId)
            override fun forceEndRoute(owner: work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner) = runtime.forceEndRoute(requester, owner)
        }
        val provider = TimetableRouteAdmissionContextProvider { TimetableRouteAdmissionContext(observation, service) }
        val releasedControls = arrayListOf<Train>()
        val mode = CouplingModeRuntime(world, CouplingModeSettings(5.0, CAP), provider) { source ->
            assertTrue(moving.movementHeld, "platform control retirement must occur under the actual output fence")
            releasedControls += source
        }
        private var redRevision = -1L
        fun red(blocked: Boolean) = owner {
            data.signalRestrictions.publish(TrainRestrictionSnapshot("test:external-red", ++redRevision, moving.id,
                observation.stamp, FrozenRouteBinding.from(checkNotNull(contactIntent).route),
                if (blocked) listOf(CurrentSpeedRestriction("test:red", 0.0)) else emptyList()))
        }
        fun <T> owner(action: () -> T): T = world.onOwnerForTest(action)
        fun tick() {
            physics.stepSimulation(50.0).await()
            owner {
                physics.drainBetweenFramesOperations()
                observation = RailwayObservation.fromCore(WORLD, world.latestWorldFrame())
                runtime.consumeObservation(observation)
                data.signalRestrictions.completeEpoch(observation.stamp, world.allTrains.mapTo(hashSetOf()) { it.id })
                mode.tick()
            }
        }
        override fun close() {
            try { mode.close(); runtime.close() } finally {
                TestSimpleATOWorldDataRegistry.unregister(world)
                try { physical.close() } finally { physics.close() }
            }
        }
    }
    companion object { private const val WORLD = "ordinary-coupling"; private const val CAP = 0.2 }
}
