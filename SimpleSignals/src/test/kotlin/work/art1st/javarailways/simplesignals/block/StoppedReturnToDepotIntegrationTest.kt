package work.art1st.javarailways.simplesignals.block

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyRequest
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.infrastructure.RailIntervalResource
import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.core.api.physics.infrastructure.StableTopologyNodeKey
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailure
import work.art1st.javarailways.basic.api.route.RouteAdmissionHandle
import work.art1st.javarailways.basic.api.route.RouteGrantId
import work.art1st.javarailways.basic.api.route.RouteGrantReversalResult
import work.art1st.javarailways.basic.api.route.RouteResourceAdmissionService
import work.art1st.javarailways.basic.api.route.RouteResourceIntent
import work.art1st.javarailways.basic.control.HandleTrainActuator
import work.art1st.javarailways.basic.control.TrainDynamics
import work.art1st.javarailways.core.physics.infrastructure.InfrastructureClaimRequest
import work.art1st.javarailways.core.physics.infrastructure.InfrastructureCoordinationRegistry
import work.art1st.javarailways.basic.infrastructure.admission.RouteResourceAdmissionRuntime
import work.art1st.javarailways.simpleato.routing.RouteAllocationTiming
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.util.plugin.ExtensionData
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.physics.train.onOwnerForTest
import work.art1st.javarailways.simpleato.SimpleATOPlugin
import work.art1st.javarailways.simpleato.SimpleATOWorldData
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.api.depot.DepotId
import work.art1st.javarailways.simpleato.api.depot.DepotTrainSelector
import work.art1st.javarailways.simpleato.api.depot.RollingStockRecord
import work.art1st.javarailways.simpleato.api.depot.RollingStockState
import work.art1st.javarailways.simpleato.api.depot.StablingPointRef
import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.simpleato.api.destination.OperationalDestination
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationService
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotStore
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simpleato.timetable.Timetable
import work.art1st.javarailways.simpleato.timetable.InfrastructureDependencyService
import work.art1st.javarailways.simpleato.timetable.TimetableAction
import work.art1st.javarailways.simpleato.timetable.TimetableAtoFactory
import work.art1st.javarailways.simpleato.timetable.TimetableDispatcher
import work.art1st.javarailways.simpleato.timetable.TimetableRouteAdmissionContext
import work.art1st.javarailways.simpleato.timetable.TimetableRouteAdmissionContextProvider
import work.art1st.javarailways.simpleato.timetable.TimetableService
import work.art1st.javarailways.simpleato.timetable.TimetableServiceRunId
import work.art1st.javarailways.simpleato.timetable.TimetableServiceRunState
import work.art1st.javarailways.simpleato.timetable.TimetableServiceStartResult
import work.art1st.javarailways.simpleato.timetable.TimetableStep
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.math.abs

/** Real 50 ms physics, configured-handle dynamics, Dispatcher and Basic token lifecycle. */
@OptIn(ExtensionData::class)
class StoppedReturnToDepotIntegrationTest {
    @Test
    fun `stop inside block reverse and store only releases token after actual tail clearance`() =
        runReturn(null)

    @Test
    fun `real forward and return stops satisfy the default ATO parking tolerance`() =
        runReturn(null, stopToleranceMeters = AtoSafetyProfile().stopToleranceMeters)

    @Test
    fun `next forward stop inside held suffix reuses exact physical grant before returning`() = runReturn(120.0)

    @Test
    fun `next forward mission moves on held prefix before extending into the following complete block`() =
        // Keep the 40m held remainder longer than this train's full stopping lookahead.
        runReturn(160.0, maximumSpeedMetersPerSecond = 5.0)

    @Test
    fun `moving authority freezes a real stopping margin through forward stop reverse and managed storage`() =
        runReturn(null, fixedBlocks = false)

    @Test
    fun `next moving stop inside retained margin extends geometry with a new route revision`() =
        runReturn(101.0, fixedBlocks = false)

    @Test
    fun `actual head beyond moving authority stalls before reverse or managed storage and retains claims`() =
        runReturn(null, fixedBlocks = false, injectAuthorityBreach = true)

    @Test
    fun `mixed direction block retains one token through two reversals and releases after legal forward tail clearance`() =
        runReturn(null, mixedDirections = true)

    private fun runReturn(secondStop: Double?, fixedBlocks: Boolean = true, injectAuthorityBreach: Boolean = false,
        stopToleranceMeters: Double = 0.25, mixedDirections: Boolean = false,
        maximumSpeedMetersPerSecond: Double = TrainProfile.maxSpeed
    ) {
        val worldId = UUID.randomUUID()
        val world = createTestWorld(PhysicsCoordinator(1, automaticFallback = false))
        var registryToClose: InfrastructureCoordinationRegistry? = null
        var admissionToClose: RouteResourceAdmissionRuntime? = null
        try {
            val profile = object : TrainDynamics.TrainProfile by TrainProfile {
                override val maxSpeed = maximumSpeedMetersPerSecond
            }
            val firstRail = createStraightRailFromSegments(world,
                PrimeCurveGenerator.buildStraight(240.0, if (mixedDirections) 2 else 1))
            val views = work.art1st.javarailways.core.physics.train.getViewsInOrder(firstRail, false)
            val rail = if (mixedDirections) views[1].rail else firstRail
            val allRailIds = views.map { it.rail.id }.toSet()
            val firstStop = if (mixedDirections) 80.0 else 100.0
            val blockExit = if (mixedDirections) 100.0 else 140.0
            val depotPosition = if (mixedDirections) 115.0 else 20.0
            val train = createTrainWithConfig("return-train", rail, world, listOf(10.0))
            val coordinator = AtoWorldCoordinator(worldId.toString(), reportSink = {})
            val basic = BasicWorldData(safetyInterlock = coordinator.safetyInterlock)
            val basicPlugin = BasicPlugin.instanceOrNull() ?: BasicPlugin(0).also { BasicPlugin.INSTANCE = it }
            world.registerExtensionData(basicPlugin, basic)
            if (mixedDirections) basic.operatingDirections.put(firstRail.id, ViewDirection.AB)
            val restrictions = TrainRestrictionSnapshotStore(worldId.toString())
            val inspection = InspectionRuntime(restrictions) { _, _, _ -> }
            val epoch = SignalEpochRuntime(worldId.toString(), restrictions, restrictions::completeEpoch) { source, ids ->
                inspection.markDirty(source, ids)
            }
            val block = FixedBlockDefinition("test:return-block", 1L,
                if (mixedDirections) listOf(
                    FixedBlockTraversal(firstRail.id, ViewDirection.AB, 40.0, 120.0),
                    FixedBlockTraversal(rail.id, ViewDirection.AB, 0.0, blockExit))
                else listOf(FixedBlockTraversal(rail.id, ViewDirection.AB, 40.0, blockExit)))
            // This is a fixed-block test railway, including its depot. The actual stop remains20m;
            // a separate real0..40m block supplies its physical continuation, not a synthetic buffer.
            val depotBlock = FixedBlockDefinition("test:depot-block", 1L,
                listOf(FixedBlockTraversal(rail.id, ViewDirection.AB,
                    if (mixedDirections) blockExit else 0.0, if (mixedDirections) 120.0 else 40.0)))
            val blocks = if (!fixedBlocks) emptyList() else listOf(depotBlock, block) + if (secondStop != null && secondStop > 140.0) listOf(
                FixedBlockDefinition("test:next-block", 1L,
                    listOf(FixedBlockTraversal(rail.id, ViewDirection.AB, 140.0, 200.0)))
            ) else emptyList()
            val topology = world.topologyState
            val catalog = FixedBlockCatalog()
            assertEquals(FixedBlockCatalogStatus.PUBLISHED, catalog.publish(topology, 1L, blocks).status)
            val plugin = Plugin::class.java.cast(Proxy.newProxyInstance(Plugin::class.java.classLoader,
                arrayOf(Plugin::class.java)) { proxy, method, args ->
                when (method.name) {
                    "isEnabled" -> true
                    "getName", "toString" -> "SimpleSignals"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.singleOrNull()
                    else -> error(method.name)
                }
            })
            val policy = FixedBlockRouteResourcePolicy(plugin) { catalog.snapshot(topology) }
            if (fixedBlocks) inspection.replaceSources(listOf(FixedBlockInspectionSource({ catalog.snapshot(topology) }).source))
            val token = StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, block.blockId)
            val tokens = blocks.map { StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, it.blockId) }.toSet()
            val registry = InfrastructureCoordinationRegistry({ topology.revision }, { resource ->
                resource in tokens || resource is RailIntervalResource && resource.railId in allRailIds
            }, { it in allRailIds }, { key -> topology.nodes.values.any { StableTopologyNodeKey(it.ports) == key } })
            registryToClose = registry
            var observation: RailwayObservation? = null
            var reverseGrantReachedDepotExit = false
            val requester = Any()
            val admission = RouteResourceAdmissionRuntime.create(worldId.toString(), world, registry, Any(),
                { observation }, { intent -> policy.contribute(worldId, intent).map { plugin to it } },
                { _, contribution -> policy.validateRouteContribution(plugin, worldId,
                    contribution.resource.resourceId, contribution.preparation)?.let {
                    RouteAdmissionFailure(it, "invalid fixed-block contribution")
                } }, { _, _ -> null }, epoch::publishAdmissionUpdate) { _, failure -> throw failure }
            admissionToClose = admission
            val submittedIntents = arrayListOf<RouteResourceIntent>()
            val grantedIds = arrayListOf<RouteGrantId>()
            val rebasedGrantIds = arrayListOf<RouteGrantId>()
            val service = object : RouteResourceAdmissionService {
                override fun request(intent: RouteResourceIntent) = admission.request(requester, intent).also { result ->
                    submittedIntents += intent
                    if (result is work.art1st.javarailways.basic.api.route.RouteAdmissionResult.Granted)
                        grantedIds += result.grant.grantId
                    if (result is work.art1st.javarailways.basic.api.route.RouteAdmissionResult.Granted &&
                        intent.route.segments.last().railId == firstRail.id &&
                        intent.route.segments.last().facing == ViewDirection.BA &&
                        abs(firstRail.viewAB.curve.totalArcLength - intent.route.segments.last().endArcLengthMeters -
                            (if (mixedDirections) 40.0 else if (fixedBlocks) 0.0 else 18.0)) <= 1.0e-6 &&
                        result.grant.committedAuthority.distanceFromRouteStartMeters == intent.route.totalLengthMeters
                    ) reverseGrantReachedDepotExit = true
                }
                override fun progress(handle: RouteAdmissionHandle) = admission.progress(requester, handle)
                override fun suspendReleaseForReversal(grantId: RouteGrantId) =
                    admission.suspendReleaseForReversal(requester, grantId)
                override fun rebaseAfterReversal(intent: RouteResourceIntent) =
                    admission.rebaseAfterReversal(requester, intent).also { result ->
                        if (result is RouteGrantReversalResult.Accepted) rebasedGrantIds += result.grant.grantId
                    }
                override fun endGrant(grantId: RouteGrantId) = admission.endGrant(requester, grantId)
            }
            val dependencies = object : InfrastructureDependencyService {
                override fun set(request: InfrastructureDependencyRequest) = registry.setInfrastructureDependencies(requester, request)
                override fun remove(owner: InfrastructureOwner) = registry.removeInfrastructureDependencies(requester, owner)
            }
            val carProfile = CarProfile
            val safety = AtoSafetyProfile(1.0,
                TrainDynamics.flatServiceDeceleration(profile, listOf(carProfile), 9.81),
                HandleTrainActuator.serviceBrakeReactionTimeSeconds(profile, listOf(carProfile), 9.81),
                safetyDistanceMeters = 2.0, stopToleranceMeters = stopToleranceMeters)
            val dispatcher = TimetableDispatcher(
                TimetableRouteAdmissionContextProvider { observation?.let { TimetableRouteAdmissionContext(it, service) } },
                restrictions, inspection, dependencies,
                authorityStoppingMarginMeters = safety.safetyDistanceMeters,
                allocationTiming = RouteAllocationTiming(0.0, 0.0, 2.0),
                atoFactory = TimetableAtoFactory { selected, selectedWorld ->
                    SimpleATO(selected, selectedWorld,
                        HandleTrainActuator(selected, profile, listOf(carProfile), 9.81), safety,
                        inspection::maximumDeclaredLeadTimeSeconds)
                })
            val data = SimpleATOWorldData(coordinator, dispatcher, epoch, restrictions, inspection,
                basic.railObjects, basic.safetyInterlock)
            val atoPlugin = SimpleATOPlugin.instanceOrNull() ?: SimpleATOPlugin(101).also { SimpleATOPlugin.INSTANCE = it }
            world.registerExtensionData(atoPlugin, data)
            val depot = DepotId("return-depot")
            val arrivalDepot = if (mixedDirections) DepotId("forward-depot") else depot
            val out = StablingPointRef(depot, "out")
            // The mixed case exits into a separate depot, so static departure validation
            // does not treat that forward-only destination as another possible origin.
            val home = StablingPointRef(arrivalDepot, "return-home")
            world.onOwnerForTest {
                val destinations = OperationalDestinationService(world)
                destinations.register(OperationalDestination("return-depot@out", rail.id, 20.0,
                    ViewDirection.AB, DestinationRole.DepotStablingPointRole(depot.value, out.pointId, usableLengthMeters = 20.0)))
                // This bidirectional stabling point is also a valid future departure origin.
                // The timetable's explicit Reverse actions determine the final arrival facing.
                destinations.register(OperationalDestination("${arrivalDepot.value}@return-home", rail.id, depotPosition,
                    null, DestinationRole.DepotStablingPointRole(arrivalDepot.value, home.pointId, usableLengthMeters = 20.0)))
                // The return passes this outbound marker in BA after an explicit Reverse.
                destinations.register(OperationalDestination("inside", rail.id, firstStop,
                    ViewDirection.AB, DestinationRole.Regular))
                if (mixedDirections) destinations.register(OperationalDestination("return-platform", rail.id, 40.0,
                    ViewDirection.BA, DestinationRole.Regular))
                secondStop?.let { basic.wayPoints.add(WayPoint("second", rail.id, it)) }
                data.depots.registerRollingStock(RollingStockRecord(train.id, depot, "test/train", emptySet(),
                    1, 10.0, profile.maxSpeed, emptySet()), out)
                val steps = buildList {
                    add(TimetableStep.At(0, TimetableAction.TakeFromDepot(DepotTrainSelector(depot))))
                    add(TimetableStep.At(0, TimetableAction.GoTo("inside")))
                    add(TimetableStep.At(0, TimetableAction.Stop(5)))
                    if (secondStop != null) {
                        add(TimetableStep.At(0, TimetableAction.GoTo("second")))
                        add(TimetableStep.At(0, TimetableAction.Stop(5)))
                    }
                    add(TimetableStep.At(0, TimetableAction.Reverse))
                    if (mixedDirections) {
                        add(TimetableStep.At(0, TimetableAction.GoTo("return-platform")))
                        add(TimetableStep.At(0, TimetableAction.Stop(5)))
                        add(TimetableStep.At(0, TimetableAction.Reverse))
                        // The current planner requires every same-facing marker in order.
                        add(TimetableStep.At(0, TimetableAction.GoTo("inside")))
                    }
                    add(TimetableStep.At(0, TimetableAction.StoreAtDepot(arrivalDepot, setOf(home.pointId))))
                }
                data.timetables.add(Timetable("return", services = listOf(TimetableService("service", steps = steps))))
            }
            world.physicsCoordinator.registerRailwayEpochProcessor(epoch)
            world.physicsCoordinator.start()
            fun tokenClaims() = registry.claimSnapshots().filter { token in it.footprint.resources }
            fun assertOpposingClaimBlocked() {
                val before = registry.claimSnapshots()
                val conflict = registry.acquireClaim(Any(), InfrastructureClaimRequest(
                    InfrastructureOwner("test:opposing", 1), InfrastructureFootprint(listOf(token))))
                assertEquals(InfrastructureMutationStatus.RESOURCE_CONFLICT, conflict.status)
                assertEquals(before, registry.claimSnapshots(), "conflict rejection must preserve the real retained claim")
            }
            fun advance() {
                world.physicsCoordinator.stepSimulation(50.0).await()
                world.onOwnerForTest {
                    observation = RailwayObservation.fromCore(worldId.toString(), world.latestWorldFrame())
                    admission.consumeObservation(observation!!)
                    dispatcher.tick(world)
                }
            }
            advance()
            val start = world.onOwnerForTest { dispatcher.startService(world, "return", "service") }
            assertInstanceOf(TimetableServiceStartResult.Started::class.java, start,
                "$start stall=${coordinator.lastStall?.message}")
            val runId = TimetableServiceRunId("return", "service")
            var stoppedInside = false
            var reversedInside = false
            var tailCleared = false
            var returnedInside = false
            var secondReversedInside = false
            var heldOwner: InfrastructureOwner? = null
            var forwardRequestsAtFirstStop: Int? = null
            var secondStopped = secondStop == null
            var movedBeforeExtension = false
            var maximumHead = 20.0
            var steps = 0
            while (steps++ < 6_000 && dispatcher.status(runId)?.state != TimetableServiceRunState.COMPLETED) {
                advance()
                val frame = train.publishedFrame
                assertFalse(coordinator.isStalled,
                    "${coordinator.lastStall?.message}; frame=$frame; run=${dispatcher.status(runId)}")
                assertEquals(observation!!.stamp.physicsEpoch, frame.physicsEpoch)
                maximumHead = maxOf(maximumHead, frame.head.arcLengthInAbMeters)
                if (mixedDirections) assertTrue(frame.occupiedIntervals.all { it.railId == rail.id },
                    "the complete train must remain in the two-way rail throughout both reversals")
                if (secondStop != null && secondStop > 140.0 && !frame.reversed &&
                    frame.head.arcLengthInAbMeters > 101.0 && submittedIntents.size == forwardRequestsAtFirstStop
                ) movedBeforeExtension = true
                val bodyInBlock = frame.occupiedIntervals.any {
                    interval -> block.traversals.any { traversal ->
                        interval.railId == traversal.railId && interval.maxArcLengthInAbMeters > traversal.lowerAbMeters &&
                            interval.minArcLengthInAbMeters < traversal.upperAbMeters
                    }
                }
                if (!secondStopped && secondStop != null && (train.controller as SimpleATO).currentMission?.legs?.last()?.point
                        ?.target?.id == "second" && frame.speedMetersPerSecond == 0.0 && !frame.reversed &&
                    abs(frame.head.arcLengthInAbMeters - secondStop) <= safety.stopToleranceMeters
                ) {
                    secondStopped = true
                    assertEquals(secondStop, frame.head.arcLengthInAbMeters, safety.stopToleranceMeters,
                        "the actual second stop must satisfy the existing parking tolerance")
                    if (fixedBlocks && secondStop < 140.0) assertEquals(forwardRequestsAtFirstStop, submittedIntents.size,
                        "an operating target inside an already granted suffix needs no new Basic request")
                    else assertTrue(submittedIntents.size > checkNotNull(forwardRequestsAtFirstStop))
                }
                if (!stoppedInside && maximumHead > firstStop - 10.0 && frame.speedMetersPerSecond == 0.0 && !frame.reversed && bodyInBlock &&
                    abs(frame.head.arcLengthInAbMeters - firstStop) <= safety.stopToleranceMeters
                ) {
                    stoppedInside = true
                    assertEquals(firstStop, frame.head.arcLengthInAbMeters, safety.stopToleranceMeters,
                        "the actual first stop must satisfy the existing parking tolerance")
                    assertTrue(bodyInBlock)
                    val claim = if (fixedBlocks) tokenClaims().single() else registry.claimSnapshots().single()
                    assertTrue(claim.footprint.resources.filterIsInstance<RailIntervalResource>().any {
                        it.railId == rail.id && it.endAbMeters >= (if (fixedBlocks) blockExit else 102.0)
                    }, "the real physical claim, not just its block token, must cover the complete section")
                    heldOwner = claim.owner
                    if (frame.head.arcLengthInAbMeters < 110.0 && forwardRequestsAtFirstStop == null) {
                        forwardRequestsAtFirstStop = submittedIntents.size
                    }
                    if (fixedBlocks) {
                        assertOpposingClaimBlocked()
                    } else assertEquals(102.0, claim.footprint.resources.filterIsInstance<RailIntervalResource>()
                        .maxOf { it.endAbMeters }, 1.0e-9)
                    if (injectAuthorityBreach) {
                        // Simulate a physical overrun, not an invented observation/grant. The next
                        // real frame must fail closed before the pending Reverse/storage actions.
                        train.moveTrainForward(3.0)
                        train.commit()
                        world.onOwnerForTest { dispatcher.tick(world) }
                        assertTrue(train.publishedFrame.head.arcLengthInAbMeters > 102.0)
                        assertTrue(coordinator.isStalled)
                        assertEquals("RouteAuthorityViolation", coordinator.lastStall?.errorType)
                        assertEquals(TimetableServiceRunState.FAILED, dispatcher.status(runId)?.state)
                        assertFalse(train.publishedFrame.reversed)
                        assertTrue(registry.claimSnapshots().isNotEmpty(), "a breach cannot release physical claims")
                        assertFalse(data.depots.rollingStock(train.id)!!.state == RollingStockState.AVAILABLE)
                        assertNull(data.depots.stablingPoint(home)?.occupantTrainId)
                        return
                    }
                }
                if (frame.reversed && bodyInBlock) {
                    reversedInside = true
                    assertTrue(stoppedInside, "the direction change must follow the actual in-block stop")
                    assertEquals(heldOwner, (if (fixedBlocks) tokenClaims() else registry.claimSnapshots()).single().owner,
                        "reversal must retain the same claim")
                }
                if (mixedDirections && !returnedInside && frame.reversed && frame.speedMetersPerSecond == 0.0 &&
                    abs(frame.head.arcLengthInAbMeters - 40.0) <= safety.stopToleranceMeters
                ) {
                    returnedInside = true
                    assertTrue(frame.occupiedIntervals.all { it.railId == rail.id }, "the full reversed train stays in the two-way rail")
                    assertTrue(tokenClaims().single().footprint.resources.filterIsInstance<RailIntervalResource>().any {
                        it.railId == firstRail.id && it.startAbMeters <= 40.0 + 1.0e-6 &&
                            it.endAbMeters >= firstRail.viewAB.curve.totalArcLength - 1.0e-6
                    }, "reverse protection includes the single-direction rail without authorizing travel into it; " +
                        "A=${firstRail.id}; claims=${registry.claimSnapshots()}; " +
                        "requests=${submittedIntents.map { it.route.segments to it.desiredAuthority }}")
                    assertOpposingClaimBlocked()
                }
                if (mixedDirections && returnedInside && !frame.reversed && bodyInBlock) {
                    secondReversedInside = true
                    assertEquals(heldOwner, tokenClaims().single().owner)
                }
                val actualTailCleared = if (mixedDirections) secondReversedInside && !frame.reversed &&
                    frame.tail.railId == rail.id && frame.tail.arcLengthInAbMeters > blockExit
                    else reversedInside && frame.tail.arcLengthInAbMeters < 40.0
                if (actualTailCleared) {
                    tailCleared = true
                    assertTrue(tokenClaims().isEmpty(), "actual tail clearance must release the block")
                } else if (fixedBlocks && stoppedInside && bodyInBlock) {
                    assertEquals(1, tokenClaims().size, "head exit or mission transition cannot release the block")
                    assertEquals(heldOwner, tokenClaims().single().owner,
                        "every occupied physics frame must retain the original claim owner")
                }
            }
            assertTrue(stoppedInside)
            assertTrue(secondStopped, "the contained next mission must receive updated controller authorization")
            if (secondStop != null && secondStop > 140.0) assertTrue(movedBeforeExtension,
                "rolling allocation must allow movement on the old grant before requesting the next block")
            assertTrue(reversedInside)
            assertTrue(tailCleared)
            if (mixedDirections) {
                assertTrue(returnedInside)
                assertTrue(secondReversedInside)
                assertEquals(1, grantedIds.distinct().size, "both reversals preserve the original grant identity")
                assertEquals(listOf(grantedIds.first(), grantedIds.first()), rebasedGrantIds,
                    "both actual reversal rebases must retain the grant returned for the original departure")
                assertTrue(submittedIntents.any { it.route.segments.last().railId == firstRail.id &&
                    it.route.segments.last().facing == ViewDirection.BA }, "reverse protection reaches the other rail")
            }
            assertTrue(reverseGrantReachedDepotExit, "real reverse physical grant must reach its physical terminal boundary")
            assertEquals(TimetableServiceRunState.COMPLETED, dispatcher.status(runId)?.state,
                "steps=$steps status=${dispatcher.status(runId)}")
            val parkedHead = train.publishedFrame.head
            val parkedClaims = registry.claimSnapshots().map { it.footprint }
            repeat(5) {
                advance()
                assertEquals(0.0, train.publishedFrame.speedMetersPerSecond)
                assertEquals(parkedHead, train.publishedFrame.head,
                    "accepted terminal parking must remain stationary in later real physics frames")
                assertEquals(parkedClaims, registry.claimSnapshots().map { it.footprint })
            }
            // Authorization and parking are separate assertions: retaining a complete physical
            // grant must not move the operating target or excuse an inaccurate final stop.
            val finalMission = (train.controller as SimpleATO).currentMission!!
            val operatingEnd = finalMission.legs.last().plannedRoute.expandedRoute.segments.last()
            assertEquals(rail.id, operatingEnd.railViewRef.railId)
            assertEquals(if (mixedDirections) ViewDirection.AB else ViewDirection.BA, operatingEnd.railViewRef.direction)
            assertEquals(depotPosition, if (mixedDirections) operatingEnd.endArcLength else
                rail.viewAB.curve.totalArcLength - operatingEnd.endArcLength, 1.0e-9)
            assertEquals(0.0, train.speed)
            val finalFrame = train.publishedFrame
            assertEquals(depotPosition, finalFrame.head.arcLengthInAbMeters, safety.stopToleranceMeters,
                "the actual return stop must satisfy the existing parking tolerance")
            assertTrue(finalFrame.occupiedIntervals.all {
                it.railId == rail.id && it.minArcLengthInAbMeters >= (if (mixedDirections) blockExit else 0.0) &&
                    it.maxArcLengthInAbMeters <= (if (mixedDirections) 120.0 else 40.0)
            }, "the actual complete train must remain inside the authored depot block")
            val depotToken = StableInfrastructureResource(FIXED_BLOCK_RESOURCE_TYPE, depotBlock.blockId)
            val depotClaim = if (fixedBlocks) registry.claimSnapshots().single { depotToken in it.footprint.resources }
                else registry.claimSnapshots().single()
            val claimedRails = depotClaim.footprint.resources.filterIsInstance<RailIntervalResource>()
            if (!fixedBlocks) assertEquals(18.0, claimedRails.minOf { it.startAbMeters }, 1.0e-9)
            for (end in listOf(finalFrame.head, finalFrame.tail)) {
                assertTrue(claimedRails.any {
                    it.railId == end.railId && end.arcLengthInAbMeters >= it.startAbMeters &&
                        end.arcLengthInAbMeters <= it.endAbMeters
                }, "actual train end $end must be covered by the real retained rail claim")
            }
            val stored = data.depots.rollingStock(train.id)!!
            assertEquals(RollingStockState.AVAILABLE, stored.state)
            assertEquals(home, stored.stablingPointRef)
            assertNull(data.depots.stablingPoint(out)?.occupantTrainId)
            assertEquals(train.id, data.depots.stablingPoint(home)?.occupantTrainId)
            assertFalse(coordinator.isStalled, coordinator.lastStall?.message)
        } finally {
            admissionToClose?.close()
            registryToClose?.close()
            world.physicsCoordinator.close()
            (world.tickManager as? AutoCloseable)?.close()
        }
    }

    private object TrainProfile : TrainDynamics.TrainProfile {
        override val maxSpeed = 10.0
        override val powerHandles = listOf("P1", "P2")
        override val brakeHandles = listOf("B1", "B2", "EB")
        override val brakeApplyTime = 0.2
        override val brakeReleaseTime = 0.2
        override val adhesionCoefficient = 0.25
        override val tractionJerk = 1.5
        override val brakeJerk = 1.5
        override fun normalizedPowerScale(handle: String) = when (handle) {
            "P1" -> 0.5
            "P2" -> 1.0
            else -> 0.0
        }
        override fun normalizedBrakeScale(handle: String) = when (handle) {
            "B1" -> 0.5
            "B2" -> 1.0
            "EB" -> 1.25
            else -> 0.0
        }
        override fun resistanceForceAt(speed: Double) = 200.0 + 2.0 * speed * speed
    }

    private object CarProfile : TrainDynamics.CarProfile {
        override val mass = 20_000.0
        override val effectiveMass = 21_000.0
        override val motorAdhesionMassRatio = 1.0
        override val brakeMaxForce = 30_000.0
        override val brakeAdhesionMassRatio = 1.0
        override fun motorForceAt(speed: Double) = 25_000.0
        override fun resistanceForceAt(speed: Double, defaultResistance: (Double) -> Double) = defaultResistance(speed)
    }
}
