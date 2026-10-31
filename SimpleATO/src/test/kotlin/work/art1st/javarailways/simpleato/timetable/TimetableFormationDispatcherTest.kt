package work.art1st.javarailways.simpleato.timetable

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.JsonObject
import work.art1st.javarailways.simpleato.api.timetable.*
import work.art1st.javarailways.simpleato.api.station.*
import work.art1st.javarailways.simpleato.api.destination.*
import work.art1st.javarailways.basic.api.operation.DoorSide
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.*
import work.art1st.javarailways.basic.control.HandleTrainActuator
import work.art1st.javarailways.basic.control.TrainDynamics
import work.art1st.javarailways.basic.infrastructure.admission.RouteResourceAdmissionRuntime
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.train.*
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.api.depot.*
import work.art1st.javarailways.simpleato.api.mission.AtoMissionOrigin
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotStore
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry

/** Actual scheduled action, Basic admission, Core split and handle-driven output movement. */
class TimetableFormationDispatcherTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(doubles = [0.5, 5.0])
    fun `scheduled split continues FIRST and keeps REST physically held`(safetyDistanceMeters: Double) {
        Fixture(false, safetyDistanceMeters = safetyDistanceMeters, measureTasks = true).use { f ->
            f.begin()
            val before = f.train.publishedFrame
            f.physics.start()
            repeat(1800) { f.tick() }
            assertEquals(2, f.world.allTrains.size)
            val run = f.dispatcher.statuses().single { it.id == TimetableServiceRunId("split", "source") }
            val continuing = f.world.getTrain(run.trainId) as CoreTrain
            val held = f.world.allTrains.single { it.id != continuing.id } as CoreTrain
            assertEquals(TimetableServiceRunState.COMPLETED, run.state, run.toString())
            assertEquals(120.0, continuing.publishedFrame.head.arcLengthInAbMeters, 0.5)
            assertTrue(f.outputRequests.last().route.segments.last().endArcLengthMeters >=
                120.0 + safetyDistanceMeters - 1e-6,
                "departure completes the full stopping margin after the body-only handoff")
            assertTrue(f.outputBindings.all {
                it.route.totalLengthMeters == it.desiredAuthority.distanceFromRouteStartMeters
            }, "bootstrap grants protect only the actual complete body route")
            assertTrue(continuing.publishedFrame!!.head.arcLengthInAbMeters > before.head.arcLengthInAbMeters + 10.0)
            assertTrue(held.movementHeld)
            assertEquals(0.0, held.publishedFrame.speedMetersPerSecond)
            assertEquals(before.tail.arcLengthInAbMeters, held.publishedFrame.tail.arcLengthInAbMeters, 1e-6)
            assertEquals(1, f.preparations, "scheduled split must prepare and publish once")
            val measured = f.dispatcher.statuses().single { it.id.serviceId == "source" }.taskTimings.single()
            assertEquals("divide-task", measured.taskId)
            assertTrue(measured.endTick!! >= measured.startTick, "source task timing survives physical output handoff")
            assertEquals(listOf(before.cars.first().carId), continuing.cars.map { it.id })
            assertEquals(setOf("unit-0", "unit-1"), f.world.simpleAtoWorldData.depots.allFixedUnitAssignments().map { it.unitId }.toSet())
            assertInstanceOf(AtoMissionOrigin.FormationOutput::class.java, (continuing.controller as SimpleATO).currentMission!!.origin)
        }
    }

    @Test
    fun `applied formation output protects the extra rail required only by its stopping margin`() {
        Fixture(false, safetyDistanceMeters = 5.0, marginCrossesRail = true).use { f ->
            f.begin()
            val marginRail = checkNotNull(f.stoppingMarginRail)
            val applied = f.dependencies.applied.values.single {
                it.owner.ownerId.startsWith("simpleato:timetable/")
            }
            assertTrue(marginRail.id in applied.dependencies.railIds,
                "the static output target is at248m; its5m margin crosses the250m ordinary connection")
            assertTrue(f.outputRequests.isEmpty(), "dependency protection precedes physical output departure")
        }
    }

    @Test
    fun `split output follows certified intermediate marker and final stopping suffix`() {
        Fixture(false, viaOutput = true).use { f ->
            f.begin()
            f.physics.start()
            repeat(1800) { f.tick() }
            val run = f.dispatcher.statuses().single { it.id == TimetableServiceRunId("split", "source") }
            val output = f.world.getTrain(run.trainId) as CoreTrain
            val mission = (output.controller as SimpleATO).currentMission!!
            assertEquals(listOf("mid", "end"), mission.legs.map { it.point.target.id })
            assertEquals(TimetableServiceRunState.COMPLETED, run.state, run.toString())
            assertEquals(120.0, output.publishedFrame.head.arcLengthInAbMeters, 0.5)
            assertEquals(1, f.preparations)
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `compile-selected output route observes published child and retains body admission`(chooseThen: Boolean) {
        Fixture(false, deferredOutput = true, chooseOutputBranch = chooseThen).use { f ->
            f.begin()
            assertEquals(2, f.train.cars.size)
            assertTrue(f.observedBranchCarCounts.isEmpty(), "output action must not observe the pre-split source frame")
            f.physics.start()
            repeat(2400) { f.tick() }
            val run = f.dispatcher.statuses().single { it.id == TimetableServiceRunId("split", "source") }
            assertEquals(listOf(1), f.observedBranchCarCounts)
            assertNull(run.selectedBerthRef, "compile-selected fixed station call does not install dynamic berth selection")
            assertFalse(run.selectedBerthCommitted)
            assertEquals(TimetableServiceRunState.COMPLETED, run.state, run.toString())
            val output = f.world.getTrain(run.trainId) as CoreTrain
            assertEquals(120.0, output.publishedFrame.head.arcLengthInAbMeters, 0.5)
            assertEquals(1, f.preparations)
            assertTrue(f.world.allTrains.filter { it.id != run.trainId }.all { it.movementHeld })
        }
    }

    @Test
    fun `compile-selected output may reverse the actual held child before its route`() {
        Fixture(false, deferredOutput = true, chooseOutputBranch = false, reverseOutputBranch = true).use { f ->
            f.begin()
            f.physics.start()
            repeat(4000) { f.tick() }
            val run = f.dispatcher.statuses().single { it.id == TimetableServiceRunId("split", "source") }
            assertEquals(listOf(1), f.observedBranchCarCounts)
            assertEquals(TimetableServiceRunState.COMPLETED, run.state, run.toString())
            val output = f.world.getTrain(run.trainId) as CoreTrain
            assertEquals(2.0, output.publishedFrame.head.arcLengthInAbMeters, 0.5)
            assertTrue(output.isReversed)
            assertTrue(f.world.allTrains.filter { it.id != run.trainId }.all { it.movementHeld })
        }
    }

    @Test
    fun `new duty takes exact published output and waits for its own scheduled departure`() {
        Fixture(true).use { f ->
            f.begin()
            val early = f.owner { f.dispatcher.startService(f.world, "split", "branch") }
            assertInstanceOf(TimetableServiceStartResult.Failure::class.java, early)
            f.physics.start()
            repeat(25) { f.tick() }
            val branch = checkNotNull(f.dispatcher.statuses().singleOrNull { it.id == TimetableServiceRunId("split", "branch") })
            assertEquals(TimetableServiceRunState.WAITING_TIMETABLE_TIME, branch.state)
            assertEquals(0, branch.nextStepIndex, "future acquisition remains unconsumed")
            assertNull(branch.currentWayPointId, "physical output does not inherit a named source marker")
            assertTrue(f.train.publishedFrame.head.arcLengthInAbMeters > 30.01,
                "FIRST departs independently before the other duty acquires: ${f.dispatcher.statuses()} / ${(f.train.controller as SimpleATO).runtimeDiagnostics}")
            val output = f.world.getTrain(branch.trainId) as CoreTrain
            val start = output.publishedFrame.head
            assertEquals(0.0, output.publishedFrame.speedMetersPerSecond)
            repeat(1200) { f.tick() }
            assertTrue(output.publishedFrame.head.arcLengthInAbMeters > start.arcLengthInAbMeters + 10.0)
            assertEquals(1, f.preparations)
            assertEquals(2, f.world.simpleAtoWorldData.depots.allRollingStock().size)
            assertEquals(2, f.world.simpleAtoWorldData.depots.allFixedUnitAssignments().map { it.assignmentId }.distinct().size)
        }
    }

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun `operating HEAD output retains exact whole unit and authored departure orientation`(sourceReversed: Boolean, outputReversed: Boolean) {
        Fixture(false, sourceReversed, outputReversed, orientationCase = true).use { f ->
            val initial = f.train.publishedFrame
            val expectedCar = if (sourceReversed) initial.cars.last().carId else initial.cars.first().carId
            f.begin()
            f.physics.start()
            repeat(1600) { f.tick() }
            val run = f.dispatcher.statuses().single { it.id == TimetableServiceRunId("split", "source") }
            val output = f.world.getTrain(run.trainId) as CoreTrain
            assertEquals(listOf(expectedCar), output.cars.map { it.id })
            assertEquals(sourceReversed xor outputReversed, output.isReversed)
            val origin = (output.controller as SimpleATO).currentMission!!.origin as AtoMissionOrigin.FormationOutput
            val displacement = output.publishedFrame.head.arcLengthInAbMeters - origin.position.arcLengthInAbMeters
            val detail = "$displacement / $run / held=${output.movementHeld} / ${(output.controller as SimpleATO).runtimeDiagnostics}"
            if (output.isReversed) assertTrue(displacement < -3.0, detail)
            else assertTrue(displacement > 3.0, detail)
            assertEquals(1, f.preparations)
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `post split real snapshot restores diagnostic definitions without historical composition or live replay`(pendingDuty: Boolean) {
        Fixture(pendingDuty).use { f ->
            f.begin()
            f.physics.start()
            repeat(25) { f.tick() }
            assertEquals(2, f.world.allTrains.size)
            val definition = checkNotNull(f.world.simpleAtoWorldData.timetables["split"])
            val originalCheckpoint = f.dispatcher.saveCheckpoint()
            val infrastructure = f.owner { f.world.saveInfrastructure(0L) }
            val epoch = f.world.latestWorldFrame().physicsEpoch
            val coreRuntime = f.owner { f.world.saveRuntime(0L, epoch) }
            val operational = f.owner {
                work.art1st.javarailways.simpleato.persistence.SimpleATORuntimeSnapshotV1.capture(f.world, 0L, epoch)
                    .copy(dispatcher = originalCheckpoint)
            }.let { saved -> work.art1st.javarailways.simpleato.persistence.SimpleATORuntimeSnapshotCodec.decode(
                work.art1st.javarailways.simpleato.persistence.SimpleATORuntimeSnapshotCodec.encode(saved)) }
            work.art1st.javarailways.core.util.TickManagerImpl(java.util.concurrent.Executors.newSingleThreadScheduledExecutor(), 0L).use { tick ->
                PhysicsCoordinator(1, automaticFallback = false).use { restoredPhysics ->
                    val restored = work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker.run {
                        work.art1st.javarailways.core.physics.CoreWorld.load(infrastructure, coreRuntime,
                            work.art1st.javarailways.core.physics.CoreWorld.SnapshotDependency(tick, restoredPhysics))
                    }
                    TestSimpleATOWorldDataRegistry.register(restored, BasicWorldData())
                    try {
                        val depot = DepotId("home")
                        restored.simpleAtoWorldData.depots.addStablingPoint(depot, "home",
                            DepotStablingPoint(StablingPointRef(depot, "p"), "origin"))
                        f.world.basicWorldData.wayPoints.all().forEach(restored.basicWorldData.wayPoints::add)
                        val savedHeads = restored.allTrains.associate { it.id to it.publishedFrame!!.head }
                        restored.onOwnerForTest { operational.restore(restored) }
                        val dispatcher = restored.simpleAtoWorldData.dispatcher
                        assertFalse(TimetableValidator(restored).validate(definition).isSuccess,
                            "the original full source composition no longer exists, so it cannot be a fresh route proof")
                        assertEquals(TimetableApplicationState.RECOVERY_REQUIRED, dispatcher.applicationState("split"))
                        assertEquals(listOf(definition), dispatcher.recoveryRequiredTimetableDefinitions())
                        assertEquals(originalCheckpoint.runs, dispatcher.recoveryRequiredRuns())
                        assertTrue(dispatcher.statuses().isEmpty(), "no live run/controller/mission is recreated")
                        assertTrue(restored.allTrains.all { it.controller == null })
                        val heldIds = operational.heldFormationOutputs.map { it.trainId }.toSet()
                        assertTrue(heldIds.isNotEmpty())
                        assertTrue(heldIds.all { (restored.getTrain(it) as CoreTrain).movementHeld })
                        restored.onOwnerForTest {
                            assertInstanceOf(TimetableServiceStartResult.Failure::class.java,
                                dispatcher.startService(restored, "split", "source"))
                            assertInstanceOf(TimetableEpochStartResult.Failure::class.java,
                                dispatcher.beginTimetable(restored, "split"))
                            assertFalse(dispatcher.scheduleInitialCycle("split"))
                            assertFalse(dispatcher.stopFutureCycles("split"), "already-disabled diagnostics cannot be silently erased")
                        }
                        val resaved = dispatcher.saveCheckpoint()
                        assertEquals(definition.services.map { it.id }.toSet(), resaved.clocks.single().disabledServiceIds)
                        assertTrue(resaved.clocks.single().pendingActivationCycles.isEmpty())
                        val second = TimetableDispatcher(routeAdmissionContextProvider = UnavailableRouteAdmissionTestContext,
                            signalRestrictionSnapshots = work.art1st.javarailways.simpleato.restriction.emptyTrainRestrictionSnapshots,
                            inspectionRuntime = work.art1st.javarailways.simpleato.restriction.emptyInspectionRuntime(),
                            infrastructureDependencyService = acceptingInfrastructureDependencyService())
                        restored.onOwnerForTest { assertEquals(TimetableCheckpointRestoreResult.Restored,
                            second.restoreCheckpoint(restored, resaved)) }
                        assertEquals(TimetableApplicationState.RECOVERY_REQUIRED, second.applicationState("split"))
                        restoredPhysics.start()
                        repeat(5) {
                            restoredPhysics.stepSimulation(50.0).await()
                            restored.onOwnerForTest { dispatcher.tick(restored) }
                        }
                        assertEquals(savedHeads, restored.allTrains.associate { it.id to it.publishedFrame!!.head })
                        assertEquals(f.world.simpleAtoWorldData.depots.allFixedUnitAssignments(),
                            restored.simpleAtoWorldData.depots.allFixedUnitAssignments())
                        restored.onOwnerForTest { dispatcher.forceDiscardTimetable(restored, "split") }
                        assertEquals(TimetableApplicationState.UNAPPLIED, dispatcher.applicationState("split"))
                        assertTrue(dispatcher.recoveryRequiredTimetableDefinitions().isEmpty())
                        assertTrue(dispatcher.recoveryRequiredRuns().isEmpty())
                        assertTrue(heldIds.all { (restored.getTrain(it) as CoreTrain).movementHeld },
                            "explicit diagnostic disposal does not release a Core/Depot physical recovery fence")
                        assertEquals(savedHeads, restored.allTrains.associate { it.id to it.publishedFrame!!.head })
                        assertEquals(definition, restored.simpleAtoWorldData.timetables["split"])
                    } finally { TestSimpleATOWorldDataRegistry.unregister(restored) }
                }
            }
        }
    }

    @Test
    fun `train information follows published split outputs and a receiving duty`() {
        Fixture(newDuty = true).use { f ->
            val observed = arrayListOf<Pair<String, JsonObject>>()
            val binding = TrainInfoField.entries.associateBy { it.name }
            f.world.onOwnerForTest {
                f.world.simpleAtoWorldData.timetableExtensions.registerAction(object : TimetableActionProvider {
                    override val typeId = "test:train-info"
                    override val version = 1
                    override val descriptor = TimetableExtensionDescriptor("Info", "Actual output train")
                    override fun validate(parameters: JsonObject) = if (parameters.size == 3) emptyList() else listOf("three properties required")
                    override fun start(parameters: JsonObject, context: TimetableRuntimeContext): TimetableExtensionActionStartResult {
                        observed += checkNotNull(context.train).trainId to parameters
                        return TimetableExtensionActionStartResult.Accepted(object : TimetableExtensionActionTicket {
                            override fun snapshot() = TimetableExtensionActionSnapshot(TimetableExtensionActionState.SUCCEEDED)
                            override fun cancel() = Unit
                        })
                    }
                })
                val table = checkNotNull(f.world.simpleAtoWorldData.timetables["split"])
                val read = TimetableStep.At(0, TimetableAction.Extension(TimetableExtensionCall("test:train-info"), binding))
                f.world.simpleAtoWorldData.timetables.add(table.copy(services = table.services.map { duty ->
                    duty.copy(steps = duty.steps.flatMap { step ->
                        if (step is TimetableStep.At &&
                            (step.action is TimetableAction.Split || step.action is TimetableAction.TakeFromFormationOutput)) listOf(step, read.copy(tick = step.tick))
                        else listOf(step)
                    })
                }))
            }
            f.begin(); f.physics.start()
            repeat(4500) { f.tick() }
            assertTrue(f.dispatcher.statuses().all { it.state == TimetableServiceRunState.COMPLETED }, f.dispatcher.statuses().toString())
            assertEquals(2, observed.size)
            for ((trainId, values) in observed) {
                val actual = f.world.allTrains.single { it.id == trainId }.publishedFrame!!
                assertEquals(kotlinx.serialization.json.JsonPrimitive(actual.trainName), values["NAME"])
                assertEquals(kotlinx.serialization.json.JsonPrimitive(1), values["CAR_COUNT"])
                assertEquals(kotlinx.serialization.json.JsonPrimitive(if (actual.reversed) "REVERSED" else "FORWARD"), values["DIRECTION"])
            }
            assertEquals(2, observed.map { it.first }.distinct().size)
        }
    }

    @Test
    fun `retained ATO receives the new duty line cap and preserves independent restrictions`() {
        Fixture(false, sourceReversed = true, outputReversed = true, orientationCase = true,
            separateDutyCaps = true).use { f ->
            f.begin()
            val previousAto = f.train.controller as SimpleATO
            previousAto.setSpeedLimit("test:independent-speed", 0.6)
            f.physics.start()
            var peak = 0.0
            repeat(100) {
                f.tick()
                peak = maxOf(peak, f.train.publishedFrame.speedMetersPerSecond)
                assertTrue(f.train.publishedFrame.speedMetersPerSecond <= 0.61,
                    "independent restriction must survive transfer: ${f.train.publishedFrame.speedMetersPerSecond}")
            }
            val branch = f.dispatcher.statuses().single { it.id == TimetableServiceRunId("split", "branch") }
            assertEquals(f.train.id, branch.trainId, "REST must retain the original physical container in this reversed source case")
            assertSame(previousAto, f.train.controller)
            assertTrue(peak > 0.3, "old source line cap0.2 must not constrain new duty line cap2.0: $peak")
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `scheduled coupling joins actual units then holds or continues`(continueOutput: Boolean) {
        Fixture(false, coupling = true, coupledContinuation = continueOutput, measureTasks = true).use { f ->
            f.begin()
            f.physics.start()
            repeat(1600) { f.tick() }
            assertEquals(1, f.world.allTrains.size, f.dispatcher.statuses().toString())
            assertEquals(3, f.train.cars.size)
            assertEquals(1, f.preparations)
            assertTrue(f.dispatcher.statuses().all { run ->
                run.taskTimings.single().let { it.taskId == "join-task" && it.endTick != null && it.endTick >= it.startTick }
            }, "both actual coupling participants complete their invocation at handoff")
            assertEquals(3, f.world.simpleAtoWorldData.depots.allFixedUnits().size)
            if (continueOutput) {
                assertTrue(f.train.publishedFrame.head.arcLengthInAbMeters > 80.0, f.dispatcher.statuses().toString())
                assertInstanceOf(AtoMissionOrigin.FormationOutput::class.java, (f.train.controller as SimpleATO).currentMission!!.origin)
            } else {
                assertTrue(f.train.movementHeld)
                assertEquals(0.0, f.train.publishedFrame.speedMetersPerSecond)
            }
        }
    }

    @Test
    fun `operator can cancel unsealed coupling preparation without losing source claims`() {
        Fixture(false, coupling = true).use { f ->
            f.begin()
            val claims = f.world.simpleAtoWorldData.depots.allFixedUnitAssignments()
            val heads = f.world.allTrains.associate { it.id to it.publishedFrame!!.head }
            f.owner {
                assertInstanceOf(TimetableControlTransferResult.Failure::class.java,
                    f.dispatcher.enterManualRoutedControl(f.world, f.train.id, "operator"))
                assertTrue(f.dispatcher.stopService(f.world, TimetableServiceRunId("split", "source")))
            }
            f.physics.start()
            repeat(5) { f.tick() }
            assertEquals(0, f.preparations)
            assertEquals(2, f.world.allTrains.size)
            assertEquals(claims, f.world.simpleAtoWorldData.depots.allFixedUnitAssignments())
            assertEquals(heads, f.world.allTrains.associate { it.id to it.publishedFrame!!.head })
            assertTrue(f.world.simpleAtoWorldData.depots.unresolvedFormationChanges().isEmpty())
        }
    }

    @Test
    fun `operator stop after coupling seal preserves recovery evidence`() {
        Fixture(false, coupling = true).use { f ->
            f.begin()
            f.physics.start()
            var ticks = 0
            while (f.preparations == 0 && ticks++ < 20) f.tick()
            assertEquals(1, f.preparations)
            val changes = f.world.simpleAtoWorldData.depots.unresolvedFormationChanges()
            assertEquals(1, changes.size)
            val claims = f.world.infrastructureCoordination.claimSnapshots()
            f.owner {
                assertFalse(f.dispatcher.stopService(f.world, TimetableServiceRunId("split", "source")))
            }
            assertEquals(TimetableServiceRunState.FAILED,
                f.dispatcher.status(TimetableServiceRunId("split", "source"))!!.state)
            assertEquals(changes, f.world.simpleAtoWorldData.depots.unresolvedFormationChanges())
            assertEquals(claims, f.world.infrastructureCoordination.claimSnapshots())
        }
    }


    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource("false,false", "true,false", "false,true", "true,true")
    fun `display update targets selected units or whole train before split`(dynamic: Boolean, wholeTrain: Boolean) {
        Fixture(false,separateDutyCaps=true).use { f ->
            f.world.onOwnerForTest {
                f.world.simpleAtoWorldData.timetableExtensions.registerCondition(object : TimetableConditionProvider {
                    override val typeId="test:display"
                    override val version=1
                    override val descriptor=TimetableExtensionDescriptor("Display","Actual unit display branch")
                    override fun validate(parameters: JsonObject)=emptyList<String>()
                    override fun evaluate(parameters: JsonObject,context: TimetableRuntimeContext)=TimetableConditionResult.True
                })
                val table=checkNotNull(f.world.simpleAtoWorldData.timetables["split"])
                val source=table.services.single()
                val branch=TimetableStep.At(0,TimetableAction.SetDisplay(if (wholeTrain) emptyList() else listOf(1), if(dynamic) JsonObject(emptyMap()) else kotlinx.serialization.json.buildJsonObject { put("destination",kotlinx.serialization.json.JsonPrimitive("D express")) },
                    trainInfo = if(dynamic) mapOf("destination" to TrainInfoField.NAME, "cars" to TrainInfoField.CAR_COUNT) else emptyMap()))
                val steps=listOf(source.steps[0],branch)+source.steps.drop(1)
                val line=checkNotNull(source.lineId)
                val updated=source.copy(steps=steps,runSegments=listOf(TimetableRunSegment("before",line,0,2),TimetableRunSegment("after",line,3,4)))
                val d=TimetableOperationalTrip("D",kotlinx.serialization.json.buildJsonObject { put("destination",kotlinx.serialization.json.JsonPrimitive("D")) },
                    listOf(TimetableOperationalTripSegment("source","before",listOf(1)),TimetableOperationalTripSegment("source","after",listOf(1))))
                val e=TimetableOperationalTrip("E",kotlinx.serialization.json.buildJsonObject { put("destination",kotlinx.serialization.json.JsonPrimitive("E")) },
                    listOf(TimetableOperationalTripSegment("source","before",listOf(2))))
                f.world.simpleAtoWorldData.timetables.add(table.copy(services=listOf(updated),operationalTrips=listOf(d,e),lineSpeedLimits=table.lineSpeedLimits.map { it.copy(maximumSpeedMetersPerSecond=2.0) }))
            }
            f.begin(); f.physics.start()
            repeat(4000) { f.tick() }
            val run=f.dispatcher.statuses().single()
            assertEquals(TimetableServiceRunState.COMPLETED,run.state,run.toString())
            val outputs=f.world.allTrains.filterIsInstance<CoreTrain>()
            assertEquals(2,outputs.size)
            f.world.onOwnerForTest {
                val displays=outputs.flatMap { f.dispatcher.unitDisplaysForTrain(f.world,it.id,"split",0) }.associateBy { it.unitId }
                assertEquals(if(dynamic) f.train.publishedFrame.trainName else "D express",displays.getValue("unit-0").values["destination"].toString().trim('"'))
                if(dynamic) assertEquals(kotlinx.serialization.json.JsonPrimitive(2), displays.getValue("unit-0").values["cars"])
                assertEquals(if (wholeTrain) (if (dynamic) f.train.publishedFrame.trainName else "D express") else "E", displays.getValue("unit-1").values["destination"].toString().trim('"'))
            }
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `station display and reset apply to both units regardless of train orientation`(reversed: Boolean) {
        Fixture(false, sourceReversed = reversed, orientationCase = true, separateDutyCaps = true).use { f ->
            val observed = arrayListOf<Map<String, String>>()
            f.world.onOwnerForTest {
                f.world.simpleAtoWorldData.timetableExtensions.registerAction(object : TimetableActionProvider {
                    override val typeId = "test:display-observe"
                    override val version = 1
                    override val descriptor = TimetableExtensionDescriptor("Observe", "Read all unit displays")
                    override fun start(parameters: JsonObject, context: TimetableRuntimeContext): TimetableExtensionActionStartResult {
                        observed += f.dispatcher.unitDisplaysForTrain(f.world, checkNotNull(context.train).trainId, "split", 0)
                            .associate { it.unitId to it.values.getValue("destination").toString().trim('"') }
                        return TimetableExtensionActionStartResult.Accepted(object : TimetableExtensionActionTicket {
                            override fun snapshot() = TimetableExtensionActionSnapshot(TimetableExtensionActionState.SUCCEEDED)
                            override fun cancel() = Unit
                        })
                    }
                })
                val table = checkNotNull(f.world.simpleAtoWorldData.timetables["split"])
                val source = table.services.single()
                val read = StationProgramAction.Extension(TimetableExtensionCall("test:display-observe"))
                val program = StationStopPrograms("origin-station", "origin-call", StationStopRole.ORIGIN,
                    listOf(StationProgramAction.End), listOf(
                        StationProgramAction.SetDisplay(kotlinx.serialization.json.buildJsonObject { put("destination", kotlinx.serialization.json.JsonPrimitive("All")) }),
                        read, StationProgramAction.ResetDisplay, read, StationProgramAction.End), 0, 0, "origin")
                val steps = listOf(source.steps[0], TimetableStep.At(0, TimetableAction.BeginStationStop(program))) + source.steps.drop(1)
                val line = checkNotNull(source.lineId)
                val updated = source.copy(steps = steps, runSegments = listOf(
                    TimetableRunSegment("before", line, 0, 2), TimetableRunSegment("after", line, 3, 4)))
                val trips = (1..2).map { slot -> TimetableOperationalTrip("trip-$slot",
                    kotlinx.serialization.json.buildJsonObject { put("destination", kotlinx.serialization.json.JsonPrimitive("default-$slot")) },
                    listOf(TimetableOperationalTripSegment("source", "before", listOf(slot)))) }
                f.world.simpleAtoWorldData.timetables.add(table.copy(services = listOf(updated), operationalTrips = trips))
            }
            f.begin(); f.physics.start()
            repeat(500) { f.tick() }
            assertEquals(2, observed.size, f.dispatcher.statuses().toString())
            assertEquals(mapOf("unit-0" to "All", "unit-1" to "All"), observed[0])
            assertEquals(setOf("default-1", "default-2"), observed[1].values.toSet())
        }
    }

    @Test
    fun `absent registered unit slot fails validation before installing controller`() {
        Fixture(false,separateDutyCaps=true).use { f ->
            f.world.onOwnerForTest {
                val table=checkNotNull(f.world.simpleAtoWorldData.timetables["split"])
                val source=table.services.single()
                val updated=source.copy(runSegments=listOf(TimetableRunSegment("before",checkNotNull(source.lineId),0,1)))
                val trip=TimetableOperationalTrip("bad",JsonObject(emptyMap()),listOf(TimetableOperationalTripSegment("source","before",listOf(3))))
                val invalid=table.copy(services=listOf(updated),operationalTrips=listOf(trip))
                val result=TimetableValidator(f.world).validate(invalid)
                assertFalse(result.isSuccess)
                assertTrue(result.errors.any { "absent complete-unit slot" in it },result.errors.toString())
                assertNull(f.train.controller)
            }
        }
    }

    @Test
    fun `trip cannot jump from the held rear unit to the continuing front unit`() {
        Fixture(false,separateDutyCaps=true).use { f ->
            f.world.onOwnerForTest {
                val table=checkNotNull(f.world.simpleAtoWorldData.timetables["split"])
                val source=table.services.single()
                val line=checkNotNull(source.lineId)
                val updated=source.copy(runSegments=listOf(TimetableRunSegment("before",line,0,1),TimetableRunSegment("after",line,2,3)))
                val trip=TimetableOperationalTrip("bad",JsonObject(emptyMap()),listOf(
                    TimetableOperationalTripSegment("source","before",listOf(2)),
                    TimetableOperationalTripSegment("source","after",listOf(1))))
                val result=TimetableValidator(f.world).validate(table.copy(services=listOf(updated),operationalTrips=listOf(trip)))
                assertFalse(result.isSuccess)
                assertTrue(result.errors.any { "unrelated physical unit" in it },result.errors.toString())
                assertNull(f.train.controller)
            }
        }
    }

    @Test
    fun `an unrelated run without trip metadata does not require registered units`() {
        Fixture(false,separateDutyCaps=true).use { f ->
            val otherRail=createStraightRailFromSegments(f.world,PrimeCurveGenerator.buildStraight(100.0,1))
            val other=createTrainWithConfig("unregistered",otherRail,f.world,listOf(10.0))
            f.world.onOwnerForTest {
                f.world.basicWorldData.wayPoints.add(WayPoint("other-origin",otherRail.id,other.publishedFrame.head.arcLengthInAbMeters))
                f.world.basicWorldData.wayPoints.add(WayPoint("other-end",otherRail.id,80.0))
                val table=checkNotNull(f.world.simpleAtoWorldData.timetables["split"])
                val source=table.services.single()
                val line=checkNotNull(source.lineId)
                val updated=source.copy(steps=listOf(TimetableStep.Check("origin"),TimetableStep.At(0,TimetableAction.GoTo("end"))),
                    runSegments=listOf(TimetableRunSegment("before",line,0,2)))
                val unrelated=TimetableService("unrelated",TrainSelector.TrainId(other.id),listOf(TimetableStep.Check("other-origin"),TimetableStep.At(0,TimetableAction.GoTo("other-end"))),
                    runSegments=listOf(TimetableRunSegment("unrelated-run",line,0,2)))
                val trip=TimetableOperationalTrip("trip",JsonObject(emptyMap()),listOf(TimetableOperationalTripSegment("source","before",listOf(1))))
                f.world.simpleAtoWorldData.timetables.add(table.copy(services=listOf(updated,unrelated),formationEvents=TimetableFormationCatalog(emptyList()),operationalTrips=listOf(trip)))
            }
            f.begin()
            f.world.onOwnerForTest {
                val started=f.dispatcher.startService(f.world,"split","unrelated")
                assertInstanceOf(TimetableServiceStartResult.Started::class.java,started,started.toString())
                val run=f.dispatcher.statuses().single { it.id.serviceId=="unrelated" }
                assertEquals("unrelated-run",run.currentRunId,run.toString())
                assertNotEquals(TimetableServiceRunState.MANUAL_INTERVENTION,run.state,run.toString())
                assertTrue(run.unitDisplays.isEmpty())
            }
        }
    }

    private class Fixture(private val newDuty: Boolean, sourceReversed: Boolean = false, outputReversed: Boolean = false, orientationCase: Boolean = false, separateDutyCaps: Boolean = false, private val coupling: Boolean = false, coupledContinuation: Boolean = false, viaOutput: Boolean = false, deferredOutput: Boolean = false, chooseOutputBranch: Boolean = true, reverseOutputBranch: Boolean = false, safetyDistanceMeters: Double = 0.5, marginCrossesRail: Boolean = false, measureTasks: Boolean = false) : AutoCloseable {
        val physics = PhysicsCoordinator(1, automaticFallback = false)
        private val fixture = TestWorldFixture.create(physics)
        val world = fixture.world
        private val coordinator = AtoWorldCoordinator(WORLD, reportSink = {})
        init { TestSimpleATOWorldDataRegistry.register(world, BasicWorldData(safetyInterlock = coordinator.safetyInterlock), coordinator) }
        val rail = createStraightRailFromSegments(world,
            PrimeCurveGenerator.buildStraight(if (marginCrossesRail) 500.0 else 250.0, if (marginCrossesRail) 2 else 1))
        val stoppingMarginRail = if (marginCrossesRail) getViewsInOrder(rail, false)[1].rail else null
        val train = createTrainWithConfig("source-train", rail, world, listOf(10.0, 10.0))
        init { world.onOwnerForTest {
            if (orientationCase) {
                train.setLocation(RailLocationWithFacing(rail.viewAB, 130.0))
                train.commitMaintenancePlacement()
            }
            if (sourceReversed) train.reverse()
        } }
        private val stationary = if (coupling) world.onOwnerForTest {
            val head = RailLocationWithFacing(rail.viewAB, 41.5)
            val car = CoreCar.create("peer-car", CoreBogie.create(head), CoreBogie.create(head), length = 10.0,
                floorHeight = 0.0, connectorLengthAtA = 0.0, connectorLengthAtB = 0.0,
                bogieAOffset = 5.0, bogieBOffset = -5.0, world = world)
            CoreTrain.create("stationary", listOf(car), world).also { it.setLocation(head); it.commitMaintenancePlacement() }
        } else null
        private var observation = RailwayObservation.fromCore(WORLD, world.latestWorldFrame())
        private val requester = Any()
        private val failures = arrayListOf<String>()
        private val runtime = RouteResourceAdmissionRuntime.create(WORLD, world, world.infrastructureCoordination,
            Any(), { observation }, { emptyList() }, { _, _ -> null }, { _, _ -> null },
            authorizeFormationRoute = { _, _ -> FormationRoutePolicyResult.Accepted(emptyList()) },
            stallWorld = { detail, _ -> failures += detail })
        var preparations = 0
        val outputBindings = arrayListOf<RouteResourceIntent>()
        val outputRequests = arrayListOf<RouteResourceIntent>()
        val observedBranchCarCounts = arrayListOf<Int?>()
        private val service = object : RouteResourceAdmissionService {
            override fun prepareFormationChange(intent: FixedFormationChangeIntent): FormationChangeResult {
                preparations++
                return runtime.prepareFormationChange(requester, intent)
            }
            override fun sealFormationPublication(handle: FormationChangeHandle) = runtime.sealFormationPublication(requester, handle)
            override fun formationChangeState(handle: FormationChangeHandle) = runtime.formationChangeState(requester, handle)
            override fun abortFormationChange(handle: FormationChangeHandle) = runtime.abortFormationChange(requester, handle)
            override fun requestCouplingApproach(handle: FormationChangeHandle, intent: RouteResourceIntent) = runtime.requestCouplingApproach(requester, handle, intent)
            override fun bindFormationOutputs(handle: FormationChangeHandle, intents: List<RouteResourceIntent>): FormationChangeResult {
                outputBindings += intents
                return runtime.bindFormationOutputs(requester, handle, intents)
            }
            override fun requestFormationOutput(handle: FormationChangeHandle, intent: RouteResourceIntent): RouteAdmissionResult {
                outputRequests += intent
                return runtime.requestFormationOutput(requester, handle, intent)
            }
            override fun resumeFormationOutput(handle: FormationChangeHandle, grantId: RouteGrantId) = runtime.resumeFormationOutput(requester, handle, grantId)
            override fun request(intent: RouteResourceIntent) = runtime.request(requester, intent)
            override fun progress(handle: RouteAdmissionHandle) = runtime.progress(requester, handle)
            override fun suspendReleaseForReversal(grantId: RouteGrantId) = runtime.suspendReleaseForReversal(requester, grantId)
            override fun rebaseAfterReversal(intent: RouteResourceIntent) = runtime.rebaseAfterReversal(requester, intent)
            override fun endGrant(grantId: RouteGrantId) = runtime.endGrant(requester, grantId)
        }
        private val signals = TrainRestrictionSnapshotStore(WORLD)
        private val inspection = InspectionRuntime(signals) { _, _, failure -> throw failure }
        private val profile = AtoSafetyProfile(accelerationMetersPerSecondSquared = 1.0,
            serviceDecelerationMetersPerSecondSquared = 2.0, reactionTimeSeconds = 0.0,
            safetyDistanceMeters = safetyDistanceMeters, stopToleranceMeters = 0.1)
        val dependencies = RecordingInfrastructureDependencyService()
        val dispatcher = TimetableDispatcher(authorityStoppingMarginMeters = profile.safetyDistanceMeters,
            routeAdmissionContextProvider = TimetableRouteAdmissionContextProvider { TimetableRouteAdmissionContext(observation, service) },
            signalRestrictionSnapshots = signals, inspectionRuntime = inspection,
            infrastructureDependencyService = dependencies,
            atoFactory = TimetableAtoFactory { selected, selectedWorld -> SimpleATO(selected, selectedWorld,
                HandleTrainActuator(selected, Dynamics, List(selected.cars.size) { CarDynamics }, 9.8), profile,
                inspection::maximumDeclaredLeadTimeSeconds) })
        init {
            val depot = DepotId("home")
            val point = StablingPointRef(depot, "p")
            val depots = world.simpleAtoWorldData.depots
            depots.addStablingPoint(depot, "home", DepotStablingPoint(point, "origin"))
            val cars = train.cars.map { it.id }
            cars.forEachIndexed { index, id -> depots.registerFixedUnit(FixedEmuUnit("unit-$index", depot, listOf(id), "emu"), cars) }
            depots.registerRollingStock(RollingStockRecord(train.id, depot, "emu", emptySet(), 2, 20.0, 2.0, emptySet()), point)
            val allocation = checkNotNull(depots.reservePhysicallySelected(train.id, point, DepotTrainSelector(depot), "source-assignment", cars))
            check(depots.confirmInService(allocation)); check(depots.releaseDeparture(allocation))
            world.basicWorldData.wayPoints.add(WayPoint("origin", rail.id, train.publishedFrame.head.arcLengthInAbMeters))
            world.basicWorldData.wayPoints.add(WayPoint("end", rail.id, if (orientationCase) { if (sourceReversed xor outputReversed) 50.0 else 200.0 } else if (marginCrossesRail) 248.0 else if (newDuty) 180.0 else if (reverseOutputBranch) 2.0 else 120.0))
            world.basicWorldData.wayPoints.add(WayPoint("branch-end", rail.id, if (orientationCase) { if (sourceReversed xor outputReversed) 2.0 else 248.0 } else if (marginCrossesRail) 248.0 else 120.0))
            val first = TimetableFormationOutput("first", if (reverseOutputBranch) FormationOutputDisposition.Hold else FormationOutputDisposition.Duty("source"))
            val rest = TimetableFormationOutput("rest", if (reverseOutputBranch) FormationOutputDisposition.Duty("source") else if (newDuty || outputReversed) FormationOutputDisposition.Duty("branch") else FormationOutputDisposition.Hold)
            val sourceSteps = arrayListOf<TimetableStep>(TimetableStep.Check("origin"))
            if (measureTasks) sourceSteps += TimetableStep.At(0, TimetableAction.TaskStart("divide-task"))
            sourceSteps += TimetableStep.At(0, TimetableAction.Split("divide"))
            if (measureTasks) sourceSteps += TimetableStep.At(0, TimetableAction.TaskEnd("divide-task"))
            if (outputReversed) sourceSteps += TimetableStep.At(0, TimetableAction.Reverse)
            if (viaOutput) world.basicWorldData.wayPoints.add(WayPoint("mid", rail.id, 70.0))
            if (deferredOutput) {
                val station = StationId("marker")
                listOf("short" to 90.0, "long" to 100.0).forEach { (id, arc) ->
                    OperationalDestinationService(world).register(OperationalDestination("marker@$id", rail.id, arc,
                        work.art1st.javarailways.core.api.physics.rail.ViewDirection.AB,
                        DestinationRole.StationBerthRole(station, berthId = id, doorSide = DoorSide.NONE,
                            usableLengthMeters = 30.0, minimumTrainLengthMeters = if (id == "long") 16.0 else null,
                            maximumTrainLengthMeters = if (id == "short") 15.0 else 25.0)))
                }
                world.simpleAtoWorldData.timetableExtensions.registerAction(object : TimetableActionProvider {
                    override val typeId = "test:published-output-observation"
                    override val version = 1
                    override val descriptor = TimetableExtensionDescriptor("Output", "Observe actual published child")
                    override fun start(parameters: JsonObject, context: TimetableRuntimeContext): TimetableExtensionActionStartResult {
                        observedBranchCarCounts += context.train?.carCount
                        return TimetableExtensionActionStartResult.Accepted(object : TimetableExtensionActionTicket {
                            override fun snapshot() = TimetableExtensionActionSnapshot(TimetableExtensionActionState.SUCCEEDED)
                            override fun cancel() = Unit
                        })
                    }
                })
                sourceSteps += TimetableStep.At(0, TimetableAction.Extension(TimetableExtensionCall("test:published-output-observation")))
                if (reverseOutputBranch) {
                    world.basicWorldData.wayPoints.add(WayPoint("reverse-turn", rail.id, 5.0))
                    sourceSteps += TimetableStep.At(0, TimetableAction.Reverse)
                    sourceSteps += TimetableStep.At(0, TimetableAction.GoTo("reverse-turn"))
                } else if (chooseOutputBranch) {
                    sourceSteps += TimetableStep.At(0, TimetableAction.CallStation("marker", "short"))
                    sourceSteps += TimetableStep.At(0, TimetableAction.GoTo("marker@short"))
                } else {
                    sourceSteps += TimetableStep.At(0, TimetableAction.GoVia(listOf("marker@short", "marker@long")))
                }

            }
            sourceSteps += TimetableStep.At(0, if (viaOutput) TimetableAction.GoVia(listOf("mid", "end")) else if(deferredOutput && !reverseOutputBranch && chooseOutputBranch) TimetableAction.GoVia(listOf("marker@long","end")) else if (newDuty) TimetableAction.GoVia(listOf("branch-end", "end")) else TimetableAction.GoTo("end"))
            val duties = arrayListOf(TimetableService("source", TrainSelector.TrainId(train.id), sourceSteps))
            if (newDuty || outputReversed) {
                val takeTick = if (newDuty) 100L else 0L
                val branchSteps = arrayListOf<TimetableStep>(TimetableStep.At(takeTick,
                    TimetableAction.TakeFromFormationOutput("divide", "rest")))
                if (outputReversed) branchSteps += TimetableStep.At(takeTick, TimetableAction.Reverse)
                branchSteps += TimetableStep.At(takeTick, if (outputReversed) TimetableAction.GoVia(listOf("end", "branch-end")) else TimetableAction.GoTo("branch-end"))
                duties += TimetableService("branch", steps = branchSteps)
            }
            val slow = work.art1st.javarailways.simpleato.api.line.LineId("source-line")
            val fast = work.art1st.javarailways.simpleato.api.line.LineId("branch-line")
            if (separateDutyCaps) {
                val stations = listOf(work.art1st.javarailways.simpleato.api.station.StationId("a"),
                    work.art1st.javarailways.simpleato.api.station.StationId("b"))
                for (id in listOf(slow, fast)) world.simpleAtoWorldData.lines.put(
                    work.art1st.javarailways.simpleato.api.line.Line(id, id.value, stations, stations.reversed()),
                    world.simpleAtoWorldData.lines.snapshot().revision)
            }
            val configuredDuties = if (!separateDutyCaps) duties else duties.map {
                it.copy(lineId = if (it.id == "source") slow else fast)
            }
            if (coupling) {
                val peer = checkNotNull(stationary)
                val peerHome = DepotId("peer-home")
                val peerPoint = StablingPointRef(peerHome, "p")
                depots.addStablingPoint(peerHome, "peer-home", DepotStablingPoint(peerPoint, "peer-origin"))
                val ids = peer.cars.map { it.id }
                depots.registerFixedUnit(FixedEmuUnit("unit-peer", peerHome, ids, "emu"), ids)
                depots.registerRollingStock(RollingStockRecord(peer.id, peerHome, "emu", emptySet(), 1, 10.0, 2.0, emptySet()), peerPoint)
                val peerAllocation = checkNotNull(depots.reservePhysicallySelected(peer.id, peerPoint, DepotTrainSelector(peerHome), "peer-assignment", ids))
                check(depots.confirmInService(peerAllocation)); check(depots.releaseDeparture(peerAllocation))
                world.basicWorldData.wayPoints.add(WayPoint("peer-origin", rail.id, peer.publishedFrame.head.arcLengthInAbMeters))
                val joinAction = if (measureTasks) listOf(TimetableStep.At(0, TimetableAction.TaskStart("join-task")),
                    TimetableStep.At(0, TimetableAction.Couple("join")), TimetableStep.At(0, TimetableAction.TaskEnd("join-task")))
                    else listOf(TimetableStep.At(0, TimetableAction.Couple("join")))
                val joinSteps = listOf(TimetableStep.Check("origin")) + joinAction +
                    if (coupledContinuation) listOf(TimetableStep.At(0, TimetableAction.GoTo("end"))) else emptyList()
                world.simpleAtoWorldData.timetables.add(Timetable("split", services = listOf(
                    TimetableService("source", TrainSelector.TrainId(train.id), joinSteps),
                    TimetableService("peer", TrainSelector.TrainId(peer.id), (listOf(TimetableStep.Check("peer-origin")) + joinAction))),
                    formationEvents = TimetableFormationCatalog(listOf(TimetableFormationEvent.Couple("join", "source", "source", "peer",
                        FormationStationaryEnd.TAIL, FormationRendezvous("origin", "peer-origin"), 0.2,
                        TimetableFormationOutput("combined", if (coupledContinuation) FormationOutputDisposition.Duty("source") else FormationOutputDisposition.Hold))))))
            } else world.simpleAtoWorldData.timetables.add(Timetable("split", services = configuredDuties,
                lineSpeedLimits = if (separateDutyCaps) listOf(TimetableLineSpeedLimit(slow, 0.2),
                    TimetableLineSpeedLimit(fast, 2.0)) else emptyList(),
                formationEvents = TimetableFormationCatalog(listOf(TimetableFormationEvent.Split("divide", "source", 1, first, rest)))))
        }
        fun begin() = owner {
            val result = dispatcher.beginTimetable(world, "split")
            assertInstanceOf(TimetableEpochStartResult.Started::class.java, result, result.toString())
            assertInstanceOf(TimetableServiceStartResult.Started::class.java, dispatcher.startService(world, "split", "source"))
            if (coupling) assertInstanceOf(TimetableServiceStartResult.Started::class.java, dispatcher.startService(world, "split", "peer"))
        }
        fun tick() {
            physics.stepSimulation(50.0).await()
            owner {
                physics.drainBetweenFramesOperations()
                observation = RailwayObservation.fromCore(WORLD, world.latestWorldFrame())
                runtime.consumeObservation(observation)
                dispatcher.tick(world)
            }
            assertTrue(failures.isEmpty(), failures.toString())
            assertFalse(coordinator.isStalled, coordinator.lastStall.toString())
        }
        fun <T> owner(action: () -> T): T = world.onOwnerForTest(action)
        override fun close() { try { runtime.close() } finally { TestSimpleATOWorldDataRegistry.unregister(world); fixture.close(); physics.close() } }
    }
    private object Dynamics : TrainDynamics.TrainProfile {
        override val maxSpeed = 2.0
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
    private object CarDynamics : TrainDynamics.CarProfile {
        override val mass = 10_000.0
        override val effectiveMass = mass
        override val brakeMaxForce = 20_000.0
        override val motorAdhesionMassRatio = 1.0
        override val brakeAdhesionMassRatio = 1.0
        override fun motorForceAt(speed: Double) = 10_000.0
        override fun resistanceForceAt(speed: Double, defaultResistance: (Double) -> Double) = 0.0
    }
    private companion object { const val WORLD = "formation-dispatcher" }
}
