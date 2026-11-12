package work.art1st.javarailways.bukkit.http

import org.joml.Vector3d
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import work.art1st.javarailways.bukkit.config.model.PackageLoader
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.control.HandleTrainActuator
import work.art1st.javarailways.basic.control.TrainDynamics
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.train.*
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.api.mission.*
import work.art1st.javarailways.simpleato.api.station.*
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.simpleato.SimpleATOPlugin
import work.art1st.javarailways.simpleato.SimpleATOWorldData
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotStore
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simpleato.timetable.*
import work.art1st.javarailways.core.api.physics.infrastructure.*
import java.io.File
import kotlin.math.*

/** Opt-in measurement: real configured handles, dynamics, ATO and Core; no admission/dispatcher fixture. */
class AtoStoppingAccuracyExperiment {
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "JR_ATO_ACCURACY_OUTPUT", matches = ".+")
    @Test fun measure() {
        val destination = System.getenv("JR_ATO_ACCURACY_OUTPUT") ?: return
        val full = System.getenv("JR_ATO_ACCURACY_FULL") == "1"
        val longRun = System.getenv("JR_ATO_ACCURACY_LONG") == "1"
        val advance = System.getenv("JR_ATO_ACCURACY_ADVANCE")?.toDouble() ?: 0.0
        val configuredStep = System.getenv("JR_ATO_ACCURACY_STEP_MS")?.toDouble() ?: 50.0
        val jitter = System.getenv("JR_ATO_ACCURACY_JITTER") == "1"
        val onlyStop = System.getenv("JR_ATO_ACCURACY_ONLY_STOP") == "1"
        BasicPlugin.instanceOrNull() ?: BasicPlugin(0).also { BasicPlugin.INSTANCE = it }
        SimpleATOPlugin.instanceOrNull() ?: SimpleATOPlugin(101).also { SimpleATOPlugin.INSTANCE = it }
        val source = File(System.getenv("JR_ATO_ACCURACY_MODEL_DIR") ?:
            "/tmp/jr-ato-deploy-20261102-2b9ec9d1/live-s100-model").canonicalFile
        val loaded = requireNotNull(PackageLoader(source).loadTrainPackage())
        val composition = loaded.train.composition.map { requireNotNull(loaded.cars[it.substringAfterLast('/')]) }
        require(composition.size == 5) { "expected frozen five-car S100 composition: ${loaded.train.composition}" }
        val output = File(destination)
        val failures = mutableListOf<String>()
        output.writeText("mode,cars,cap_mps,grade_per_mille,direction,distance_m,step_ms,advance_m,status,ticks,simulated_seconds,wall_seconds,peak_mps,operating_error_m,physical_target_error_m,max_error_m,drift_m,arrival,last_meter_switches,last_meter_traction,precision_state,reason\n")
        val grades = if (full || longRun) listOf(-35.0, 0.0, 35.0) else listOf(0.0)
        val caps = System.getenv("JR_ATO_ACCURACY_CAP")?.toDouble()?.let { listOf(it) } ?: if (longRun) listOf(33.3) else listOf(10.0)
        val lengths = System.getenv("JR_ATO_ACCURACY_DISTANCE")?.toDouble()?.let { listOf(it) } ?: if (longRun) listOf(2000.0) else listOf(100.0)
        val repeats = if (full || longRun) listOf(1, 2) else listOf(1)
        for (mode in if (onlyStop) listOf("STOP") else listOf("STOP", "STATION_RUN", "AUTHORITY")) for (count in repeats) for (cap in caps)
            for (grade in grades) for (direction in ViewDirection.entries) for (baseDistance in lengths)
            for (variableStep in if (full) listOf(false, true) else listOf(jitter))
            for (step in if (longRun) listOf(25.0, 50.0, 100.0, 200.0) else listOf(configuredStep)) {
            val distance = if (mode == "STATION_RUN" && System.getenv("JR_ATO_ACCURACY_DISTANCE") == null) max(500.0, baseDistance) else baseDistance
            val models = List(count) { composition }.flatten()
            val carProfiles = models.map { it.control }
            val config = loaded.control.copy(maxSpeed = cap)
            val row = mutableListOf<Any>(mode, models.size, cap, grade, direction, distance, if (variableStep) "jitter25-200" else step, advance)
            val started = System.nanoTime()
            try { PhysicsCoordinator(1, automaticFallback = false).use { physics ->
                TestWorldFixture.create(physics).use { fixture ->
                    val world = fixture.world
                    val coordinator = AtoWorldCoordinator("accuracy", reportSink = {})
                    val basic = BasicWorldData(safetyInterlock = coordinator.safetyInterlock)
                    world.registerExtensionData(BasicPlugin.INSTANCE, basic)
                    val restrictions = TrainRestrictionSnapshotStore(coordinator.worldId)
                    val inspection = InspectionRuntime(restrictions) { _, _, failure -> throw failure }
                    val signals = SignalEpochRuntime(coordinator.worldId, restrictions, restrictions::completeEpoch, inspection::markDirty)
                    val unusedDispatcher = TimetableDispatcher(TimetableRouteAdmissionContextProvider { error("experiment does not invoke admission") },
                        restrictions, inspection, object : InfrastructureDependencyService {
                            override fun set(request: InfrastructureDependencyRequest): InfrastructureDependencyMutationResult = error("unused")
                            override fun remove(owner: InfrastructureOwner): InfrastructureDependencyMutationResult = error("unused")
                        })
                    world.registerExtensionData(SimpleATOPlugin.INSTANCE, SimpleATOWorldData(coordinator, unusedDispatcher,
                        signals, restrictions, inspection, basic.railObjects, basic.safetyInterlock))
                    // Reverse geometry grade as well as travel, so the requested signed grade is the travel grade.
                    val sign = if (direction == ViewDirection.AB) 1.0 else -1.0
                    val tangent = Vector3d(1.0, sign * grade / 1000.0, 0.0).normalize()
                    val normal = Vector3d(-tangent.y, tangent.x, 0.0)
                    val railLength = maxOf(8000.0, distance * 4.0)
                    val curve = PrimeCurve.fromPoints(listOf(
                        Curve.RawPoint(Vector3d(), Vector3d(tangent), Vector3d(normal), 0.0, Curve.SegmentType.LINE_START),
                        Curve.RawPoint(Vector3d(tangent).mul(railLength), Vector3d(tangent), Vector3d(normal), railLength, Curve.SegmentType.LINE_END)))
                    val rail = createStraightRailFromSegments(world, listOf(curve))
                    val train = createTrainWithConfig("accuracy", rail, world, models.map { it.length }, connectorLength = 0.3)
                    val startArc = railLength / 2.0
                    world.onOwnerForTest {
                        train.setLocation(RailLocationWithFacing(if (direction == ViewDirection.AB) rail.viewAB else rail.viewBA,
                            if (direction == ViewDirection.AB) startArc else railLength - startArc))
                        train.commit()
                    }
                    world.simpleAtoWorldData.controlRuntime.simulation = true
                    val safety = AtoSafetyProfile(serviceDecelerationMetersPerSecondSquared =
                        TrainDynamics.flatServiceDeceleration(config, carProfiles, 9.8),
                        reactionTimeSeconds = HandleTrainActuator.serviceBrakeReactionTimeSeconds(config, carProfiles, 9.8))
                    val actuator = HandleTrainActuator(train, config, carProfiles, 9.8)
                    val ato = SimpleATO(train, world, actuator, safety, { 0.0 })
                    train.setControllerForTest(ato)
                    val actualStart = train.publishedFrame.head.arcLengthInAbMeters
                    val targetArc = actualStart + sign * distance
                    val missionDistance = if (mode != "AUTHORITY") distance else distance + 200.0
                    world.onOwnerForTest {
                        val data = world.basicWorldData
                        data.wayPoints.add(WayPoint("origin", rail.id, actualStart))
                        data.wayPoints.add(WayPoint("target", rail.id, actualStart + sign * missionDistance))
                        val result = ato.submitMission(AtoMissionRequest("accuracy", 1, WayPointRef("origin"),
                            listOf(AtoMissionPoint.Stop(WayPointRef("target"), offsetMetersBefore = if (mode == "STOP") advance else 0.0)), requiredStartDirection = direction))
                        assertInstanceOf(AtoMissionSubmissionResult.Accepted::class.java, result)
                        if (mode == "STATION_RUN") {
                            val stationId = StationId("station")
                            val berthRef = StationBerthRef(stationId, "berth")
                            val berth = StationBerth(berthRef, RailLocationWithFacing(
                                if (direction == ViewDirection.AB) rail.viewAB else rail.viewBA,
                                if (direction == ViewDirection.AB) targetArc else railLength - targetArc),
                                approachDirection = direction)
                            world.simpleAtoWorldData.stations.register(Station(stationId, listOf(berthRef)), listOf(berth))
                            val request = StationCallRequest("accuracy", WayPointRef("origin"),
                                StationCall("accuracy", 1, stationId, berthRef, dwellPlan = DwellPlan(0)))
                            val selected = (result as AtoMissionSubmissionResult.Accepted).mission.legs.single().plannedRoute.expandedRoute
                            val prefix = work.art1st.javarailways.basic.api.route.ExpandedRoute(selected.fromWayPointId,
                                "station-call:${request.call.callId}:${request.call.berthRef}", selected.segments)
                            val resolved = work.art1st.javarailways.simpleato.api.station.StationCallResolver(world).resolve(request,
                                train.cars.sumOf { it.length }, retainedRoute = prefix, retainedTopology = world.topologyState)
                                as work.art1st.javarailways.simpleato.api.station.StationCallResolutionResult.Success
                            val call = ato.submitStationCall(resolved.resolved)
                            assertInstanceOf(StationCallSubmissionResult.Accepted::class.java, call)
                        }
                        ato.setMovementAuthority(1, if (mode != "AUTHORITY") distance + safety.safetyDistanceMeters else distance)
                    }
                    physics.start()
                    var peak = 0.0; var farthest = 0.0; var ticks = 0; var status = "TIMEOUT"
                    val runtime = world.simpleAtoWorldData.controlRuntime
                    val operatingDistance = if (mode == "AUTHORITY") distance - safety.safetyDistanceMeters else distance - if (mode == "STOP") advance else 0.0
                    var previousCommand = actuator.controlState(train.speed).lastCommand
                    var lastMeterSwitches = 0
                    var lastMeterTraction = 0
                    var simulatedMillis = 0.0
                    var quietMillis = 0.0
                    val maxTicks = ((distance / max(0.1, cap) + 180.0) * 1000.0 / if (variableStep) 25.0 else step).toInt()
                    while (ticks < maxTicks) {
                        val delta = if (variableStep) listOf(25.0, 200.0, 50.0, 100.0, 25.0, 50.0)[ticks % 6] else step
                        physics.stepSimulation(delta).await(); ticks++; simulatedMillis += delta
                        val frame = train.publishedFrame
                        assertFalse(frame.derailed, "physical derailment")
                        assertNull(runtime.latestIntervention, "ATP abort: ${runtime.latestIntervention}")
                        assertFalse(coordinator.isStalled, coordinator.lastStall.toString())
                        val command = actuator.controlState(frame.speedMetersPerSecond).lastCommand
                        val travelled = sign * (frame.head.arcLengthInAbMeters - actualStart)
                        if (operatingDistance - travelled <= 1.0) {
                            if (command != previousCommand) lastMeterSwitches++
                            if (command.tractionRatio > 0.0) lastMeterTraction++
                        }
                        previousCommand = command
                        peak = max(peak, frame.speedMetersPerSecond)
                        farthest = max(farthest, sign * (frame.head.arcLengthInAbMeters - actualStart))
                        quietMillis = if (frame.speedMetersPerSecond == 0.0 && peak > 0.0) quietMillis + delta else 0.0
                        if (quietMillis >= 2000.0) { status = "STOPPED"; break }
                    }
                    val physicalError = sign * (train.publishedFrame.head.arcLengthInAbMeters - targetArc)
                    val final = sign * (train.publishedFrame.head.arcLengthInAbMeters - actualStart) - operatingDistance
                    val arrival = if (mode == "STATION_RUN") ato.drainStationCallNotifications().any {
                        it.type == StationCallNotificationType.BERTHED
                    } else abs(final) <= safety.stopToleranceMeters
                    val before = train.publishedFrame.head.arcLengthInAbMeters
                    repeat(40) {
                        physics.stepSimulation(step).await()
                        assertNull(runtime.latestIntervention, "ATP after rest: ${runtime.latestIntervention}")
                        assertFalse(coordinator.isStalled, coordinator.lastStall.toString())
                    }
                    val drift = sign * (train.publishedFrame.head.arcLengthInAbMeters - before)
                    val precision = runtime.latestDecision(train)?.precisionStatus?.name ?: "NONE"
                    row.addAll(listOf(status, ticks, simulatedMillis / 1000.0, (System.nanoTime()-started)/1e9,
                        peak, final, physicalError, farthest - operatingDistance, drift, arrival,
                        lastMeterSwitches, lastMeterTraction, precision, ""))
                    if (status != "STOPPED" || abs(final) > .05 || abs(drift) > 1e-6 || !arrival || lastMeterTraction != 0)
                        failures += "mode=$mode cars=${models.size} grade=$grade direction=$direction jitter=$variableStep status=$status error=$final drift=$drift arrival=$arrival terminalTraction=$lastMeterTraction"

                }
            }
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
                val reason = "${failure.javaClass.simpleName}: ${failure.message}"
                failures += "mode=$mode cars=${models.size} grade=$grade direction=$direction jitter=$variableStep $reason"
                row.addAll(listOf("ABORTED", "", "", (System.nanoTime()-started)/1e9,
                    "", "", "", "", "", false, "", "", "", reason))
            }
            output.appendText(row.joinToString(",") { value -> "\"" + value.toString().replace("\"", "\"\"") + "\"" } + "\n")
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
