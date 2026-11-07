package work.art1st.javarailways.bukkit.http

import kotlinx.serialization.json.*
import work.art1st.javarailways.simpleato.persistence.*
import work.art1st.javarailways.simpleato.timetable.*
import work.art1st.javarailways.simpleato.timetable.simulation.*
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.simpleAtoWorldData

internal class LineDwellInsufficientException(message: String) : IllegalArgumentException(message)
internal data class LineMeasurementCall(val callId: String, val stationId: String,
    val destinationId: String, val kind: String, val viaIds: List<String>)

internal data class LineMeasurementRequest(val measurementSource: String, val firstDepartureTick: Long,
    val fixedDwellTicks: Long, val minimumDwellTicks: Long, val calls: List<LineMeasurementCall>,
    val maximumDurationTicks: Long = 72_000L) {
    val maximumSimulationTicks = Math.addExact(firstDepartureTick, maximumDurationTicks)
    init {
        require(firstDepartureTick >= 0 && fixedDwellTicks >= 0 && minimumDwellTicks >= 0)
        require(maximumDurationTicks in 1..1_728_000L && maximumSimulationTicks < 9_007_199_254_740_991L)
        require(calls.size in 2..128 && calls.map { it.callId }.distinct().size == calls.size)
        require(calls.first().kind == "ORIGIN" && calls.last().kind == "TERMINAL")
        require(calls.drop(1).dropLast(1).all { it.kind == "STOP" || it.kind == "PASS" })
        require(calls.first().viaIds.isEmpty())
        require(calls.none { it.callId.isBlank() || it.stationId.isBlank() || it.destinationId.isBlank() })
        if (calls.any { it.kind == "STOP" } && fixedDwellTicks < minimumDwellTicks) {
            throw LineDwellInsufficientException("模拟用停站间隔 $fixedDwellTicks Tick 小于最短停站 $minimumDwellTicks Tick（${calls.first { it.kind == "STOP" }.stationId}）")
        }
    }
    companion object {
        fun parse(root: JsonObject): LineMeasurementRequest {
            fun ticks(name: String) = requireNotNull((root[name] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull) { "$name requires integer ticks" }
            val origin = requireNotNull(root["calls"]?.jsonArray?.firstOrNull()).jsonObject
            val offset = origin["stoppingOffsetMetersBefore"]?.takeUnless { it == JsonNull }?.let {
                requireNotNull((it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.doubleOrNull) { "origin stopping offset must be numeric" }
            } ?: 0.0
            require(offset == 0.0) {
                "line simulation does not support an origin stopping offset: initial placement and CHECK use the origin marker"
            }
            return LineMeasurementRequest(requireNotNull(root["measurementSource"]?.jsonPrimitive?.content)
                .also { require(it.isNotBlank()) }, ticks("firstDepartureTick"), ticks("fixedDwellTicks"), ticks("minimumDwellTicks"),
                requireNotNull(root["calls"]?.jsonArray).map { raw -> val call = raw.jsonObject
                    LineMeasurementCall(call.getValue("callId").jsonPrimitive.content, call.getValue("stationId").jsonPrimitive.content,
                        call.getValue("destinationId").jsonPrimitive.content, call.getValue("kind").jsonPrimitive.content,
                        call["viaIds"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty())
                }, if (root.containsKey("maximumDurationTicks")) ticks("maximumDurationTicks") else 72_000L)
        }
    }
}

/** Retimes only detached measurement input, never the author's document or its global minimum. */
internal fun prepareLineMeasurement(saved: SavedTimetableV1, request: LineMeasurementRequest,
    reverseOrigin: Boolean = false): SavedTimetableV1 {
    require(saved.services.size == 1 && saved.formationEvents.isEmpty() && saved.operationalTrips.isEmpty()) {
        "line measurement requires exactly one independent run"
    }
    val service = saved.services.single()
    val segment = service.runSegments.single()
    require(segment.calls.map { LineMeasurementCall(it.id, it.stationId, it.destinationId, it.status.name, it.viaIds) } == request.calls) {
        "measurement source calls do not match the requested route"
    }
    val allowed = setOf(SavedTimetableActionTypeV1.BEGIN_STATION_STOP, SavedTimetableActionTypeV1.DESTINATION_OPTIONS,
        SavedTimetableActionTypeV1.STATION_DESTINATION_OPTIONS, SavedTimetableActionTypeV1.DESTINATION)
    require(service.steps.count { it.type == SavedTimetableStepTypeV1.CHECK } == 1)
    require(service.steps.mapNotNull { it.action }.all { it.type in allowed }) {
        "measurement source must contain only the selected run and its station programs"
    }
    val steps = service.steps.map { step ->
        val action = step.action ?: return@map step
        val program = action.stopPrograms
        val minimum = if (program?.role == "STOP") request.fixedDwellTicks else 0L
        step.copy(tick = if (action.type == SavedTimetableActionTypeV1.BEGIN_STATION_STOP) 0L else request.firstDepartureTick,
            action = action.copy(arrivalTick = null,
                minimumDwellTicks = if (program == null) action.minimumDwellTicks else minimum,
                stopPrograms = program?.copy(minimumDwellTicks = minimum,
                    plannedDepartureTick = if (program.role == "TERMINAL") null else request.firstDepartureTick)))
    }.toMutableList()
    val insertion = steps.indexOfFirst { it.type == SavedTimetableStepTypeV1.CHECK } + 1
    if (reverseOrigin) steps.add(insertion, SavedTimetableStepV1(SavedTimetableStepTypeV1.AT, 0L,
        action = SavedTimetableActionV1(SavedTimetableActionTypeV1.REVERSE)))
    val shiftedSegment = segment.copy(
        startStepIndex = segment.startStepIndex + if (reverseOrigin && segment.startStepIndex >= insertion) 1 else 0,
        endStepIndexExclusive = segment.endStepIndexExclusive + if (reverseOrigin && segment.endStepIndexExclusive >= insertion) 1 else 0,
        calls = segment.calls.map { it.copy(arrivalTick = null,
            departureTick = if (it.status == SavedServiceStopStatusV1.ORIGIN) request.firstDepartureTick else null) })
    return saved.copy(periodDays = maxOf(saved.periodDays, request.maximumSimulationTicks / 24_000L + 1L),
        services = listOf(service.copy(steps = steps, runSegments = listOf(shiftedSegment))))
}

internal data class SimulatedCall(val callId: String, val stationId: String, val kind: String,
    val arrivalTick: Long?, val departureTick: Long?)
internal data class SimulatedLeg(val fromStationId: String, val toStationId: String, val lengthMeters: Double,
    val travelSeconds: Double, val peakSpeedMetersPerSecond: Double, val stoppingErrorMeters: Double?)
internal data class IsolatedLineSimulationResult(val calls: List<SimulatedCall>, val legs: List<SimulatedLeg>) {
    val totalTravelSeconds: Double get() = legs.sumOf { it.travelSeconds }
}

/** Same one-world, one-train execution and domain features as full timetable simulation. */
internal object IsolatedLineSimulator {
    fun simulate(factory: IsolatedTimetableSimulationFactory, snapshot: IsolatedTimetableSimulationSnapshot,
        request: LineMeasurementRequest, workerThreads: Int): IsolatedLineSimulationResult {
        factory.create(snapshot, workerThreads).use { simulation ->
            val world = simulation.world
            val id = TimetableServiceRunId(snapshot.timetable.id, snapshot.timetable.services.single().id)
            val calls = arrayListOf(SimulatedCall(request.calls.first().callId, request.calls.first().stationId,
                "ORIGIN", null, request.firstDepartureTick))
            val legs = arrayListOf<SimulatedLeg>()
            val expected = request.calls.drop(1).flatMapIndexed { index, call ->
                call.viaIds.map { it to -1 } + (call.destinationId to index + 1)
            }
            var eventIndex = 0
            var peak = 0.0
            var previousDistance: Double? = null
            var failure: String? = null
            var dwellFailure: LineDwellInsufficientException? = null
            var pendingStopSample: Pair<Long, work.art1st.javarailways.simpleato.controller.SimpleAtoPublishedState>? = null
            var pendingStopLegIndex = -1
            val ready = hashSetOf<String>()
            fun state() = (world.allTrains.singleOrNull()?.controller as? SimpleATO)?.publishedState
            fun sample() { state()?.let { peak = maxOf(peak, it.trainFrame.speedMetersPerSecond) } }
            fun stoppingError(current: work.art1st.javarailways.simpleato.controller.SimpleAtoPublishedState, legIndex: Int): Double {
                if (current.currentStationCall != null) return requireNotNull(current.runtimeDiagnostics.stationPositionErrorMeters) {
                    "missing actual StationCall position error"
                }
                val mission = requireNotNull(current.currentMission) { "missing measured Mission" }
                val stop = mission.legs[legIndex].point as work.art1st.javarailways.simpleato.api.mission.AtoMissionPoint.Stop
                val target = mission.legs.take(legIndex + 1).sumOf { it.plannedRoute.expandedRoute.totalLength }
                return target - stop.offsetMetersBefore - requireNotNull(current.runtimeDiagnostics.projectedProgressMeters) { "missing actual route progress" }
            }
            val waypointSubscription = world.simpleAtoWorldData.dispatcher.observeWaypointProgress(id) { event ->
                if (failure == null) runCatching {
                    val wanted = expected.getOrNull(eventIndex) ?: error("unexpected extra waypoint event: ${event.waypointId}")
                    require(event.waypointId == wanted.first) { "waypoint event order differs from selected calls" }
                    eventIndex++
                    if (wanted.second >= 0) {
                        val call = request.calls[wanted.second]
                        require(event.pass == (call.kind == "PASS")) { "call ${call.callId} STOP/PASS event mismatch" }
                        val current = requireNotNull(state()) { "missing real publication at call ${call.callId}" }
                        sample()
                        val departure = calls.last().departureTick ?: calls.last().arrivalTick!!
                        require(event.timetableElapsedTicks > departure) { "nonpositive measured travel interval" }
                        val distance = current.lifetimeTravelDistanceMeters
                        val error = if (event.pass) null else stoppingError(current, event.legIndex)
                        if (!event.pass) {
                            // Waypoint completion only orders the call. A StationCall must still
                            // satisfy its physical BERTHED confirmation before it has arrived.
                            pendingStopLegIndex = event.legIndex
                            pendingStopSample = if (current.currentStationCall == null) event.timetableElapsedTicks to current else null
                        }
                        legs += SimulatedLeg(calls.last().stationId, call.stationId,
                            distance - requireNotNull(previousDistance) { "missing origin departure measurement" },
                            (event.timetableElapsedTicks - departure) / 20.0, peak, error)
                        previousDistance = distance
                        peak = current.trainFrame.speedMetersPerSecond
                        calls += SimulatedCall(call.callId, call.stationId, call.kind, event.timetableElapsedTicks,
                            if (call.kind == "STOP") Math.addExact(event.timetableElapsedTicks, request.fixedDwellTicks) else null)
                    }
                }.onFailure { failure = it.message ?: it.javaClass.simpleName }
            }
            val readySubscription = world.simpleAtoWorldData.dispatcher.observeStationProgramReady(id) { event ->
                if (failure == null) runCatching {
                    val call = requireNotNull(request.calls.singleOrNull { it.callId == event.callId }) { "unknown station readiness call ${event.callId}" }
                    ready += call.callId
                    if (call.kind != "ORIGIN") {
                        require(calls.last().callId == call.callId) { "station readiness order differs from calls" }
                        val arrival = requireNotNull(pendingStopSample) { "missing actual BERTHED frame for ${call.callId}" }
                        require(arrival.first == event.arrivedTick) { "station arrival event and physical sample ticks differ for ${call.callId}: ${arrival.first} != ${event.arrivedTick}" }
                        val from = calls[calls.lastIndex - 1]
                        val departure = from.departureTick ?: requireNotNull(from.arrivalTick)
                        val distance = arrival.second.lifetimeTravelDistanceMeters
                        legs[legs.lastIndex] = legs.last().copy(
                            lengthMeters = legs.last().lengthMeters + distance - requireNotNull(previousDistance),
                            travelSeconds = (event.arrivedTick - departure) / 20.0,
                            peakSpeedMetersPerSecond = maxOf(legs.last().peakSpeedMetersPerSecond, peak),
                            stoppingErrorMeters = stoppingError(arrival.second, pendingStopLegIndex))
                        previousDistance = distance
                        calls[calls.lastIndex] = calls.last().copy(arrivalTick = event.arrivedTick,
                            departureTick = if (call.kind == "STOP") Math.addExact(event.arrivedTick, request.fixedDwellTicks) else null)
                        pendingStopSample = null
                    }
                    val target = if (call.kind == "ORIGIN") request.firstDepartureTick
                        else calls.singleOrNull { it.callId == call.callId }?.departureTick
                    if (target != null && event.readyTick > target) {
                        dwellFailure = LineDwellInsufficientException("模拟用停站间隔不足：${call.stationId}/${call.callId}，目标发车 $target Tick，站内作业完成 ${event.readyTick} Tick")
                        failure = dwellFailure!!.message
                    }
                }.onFailure { failure = it.message ?: it.javaClass.simpleName }
            }
            try {
                val result = simulation.simulate(IsolatedTimetableSimulationRequest(snapshot.timetable,
                    request.maximumSimulationTicks + 1, request.maximumSimulationTicks), afterTick = { tick ->
                    world.physicsCoordinator.withStructuralMutation {
                        world.simpleAtoWorldData.dispatcher.recordStationProgramCompletions(id)
                    }
                    sample()
                    if (previousDistance == null && tick >= request.firstDepartureTick) previousDistance = state()?.lifetimeTravelDistanceMeters
                    if (pendingStopLegIndex >= 0 && pendingStopSample == null && calls.last().callId !in ready &&
                        world.simpleAtoWorldData.dispatcher.statuses().singleOrNull { it.id == id }?.state == TimetableServiceRunState.BERTHED) {
                        pendingStopSample = tick to requireNotNull(state())
                    }
                    failure?.let { error(it) }
                })
                dwellFailure?.let { throw it }
                check(result is IsolatedTimetableSimulationResult.Completed) {
                    when (result) {
                        is IsolatedTimetableSimulationResult.Failed -> result.reason
                        is IsolatedTimetableSimulationResult.PeriodEnded -> "line simulation reached its tick limit"
                        else -> "line simulation did not complete: $result"
                    }
                }
                require(calls.size == request.calls.size && eventIndex == expected.size) { "line simulation completed without all call events" }
                require(request.calls.filter { it.kind != "PASS" }.all { it.callId in ready }) { "line simulation lacks completed station program evidence" }
                return IsolatedLineSimulationResult(calls.toList(), legs.toList())
            } finally {
                readySubscription.close()
                waypointSubscription.close()
            }
        }
    }
}
