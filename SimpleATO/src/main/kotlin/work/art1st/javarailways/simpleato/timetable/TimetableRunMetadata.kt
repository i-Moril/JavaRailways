package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.basic.api.route.wayPoints
import work.art1st.javarailways.simpleato.api.metadata.JsonMetadata
import kotlinx.serialization.json.*
import work.art1st.javarailways.simpleato.api.line.LineId
import work.art1st.javarailways.simpleato.api.line.LineDirection
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationIds
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.core.api.physics.World
import java.util.Collections

/** A compiled physical run scope. Address ranges include every nested child of the top-level step. */
class TimetableRunSegment(val runId: String, val lineId: LineId, val startStepIndex: Int,
    val endStepIndexExclusive: Int, val isInService: Boolean, val diagramLineId: String? = null,
    val lineDirection: LineDirection? = null, calls: List<TimetableRunCall> = emptyList(),
    metadata: JsonObject = JsonObject(emptyMap())) {
    val metadata: JsonObject = JsonMetadata.immutableCopy(metadata)
    val calls: List<TimetableRunCall> = java.util.List.copyOf(calls)
    init {
        require(runId.isNotBlank() && runId == runId.trim())
        require(startStepIndex >= 0 && endStepIndexExclusive > startStepIndex)
        require(diagramLineId == null || diagramLineId.isNotBlank())
        require(calls.map { it.id }.distinct().size == calls.size)
    }
    override fun equals(other: Any?) = other is TimetableRunSegment && runId == other.runId && lineId == other.lineId &&
        startStepIndex == other.startStepIndex && endStepIndexExclusive == other.endStepIndexExclusive && isInService == other.isInService &&
        diagramLineId == other.diagramLineId && lineDirection == other.lineDirection && calls == other.calls && metadata == other.metadata
    override fun hashCode() = java.util.Objects.hash(runId, lineId, startStepIndex, endStepIndexExclusive, isInService, diagramLineId, lineDirection, calls, metadata)
}

data class TimetableRunCall(val id: String, val stationId: String, val destinationId: String,
    val status: TimetableServiceStopStatus, val arrivalTick: Long? = null, val departureTick: Long? = null,
    val viaIds: List<String> = emptyList()) {
    init {
        require(id.isNotBlank() && stationId.isNotBlank() && destinationId.isNotBlank())
        require(viaIds.size <= 256 && viaIds.all { it.isNotBlank() && it == it.trim() }) { "viaIds must be ordered nonblank waypoint IDs (at most256)" }
        require(status != TimetableServiceStopStatus.ORIGIN || viaIds.isEmpty()) { "origin cannot have incoming via waypoints" }
        require(arrivalTick == null || arrivalTick >= 0)
        require(departureTick == null || departureTick >= 0)
        require(arrivalTick == null || departureTick == null || departureTick >= arrivalTick)
    }
}

class TimetableOperationalTripSegment(val serviceId: String, val runId: String, unitSlots: List<Int>,
    val unassignedUnits: Boolean = false) {
    val unitSlots = if (unassignedUnits) {
        require(unitSlots.isEmpty()) { "default trip selects unassigned units dynamically, without explicit slots" }
        emptyList()
    } else immutableUnitSlots(unitSlots)
    init { require(serviceId.isNotBlank() && runId.isNotBlank()) }
    override fun equals(other: Any?) = other is TimetableOperationalTripSegment && serviceId == other.serviceId && runId == other.runId && unitSlots == other.unitSlots && unassignedUnits == other.unassignedUnits
    override fun hashCode() = java.util.Objects.hash(serviceId, runId, unitSlots, unassignedUnits)
}
class TimetableOperationalTrip(val id: String, defaults: JsonObject = JsonObject(emptyMap()), segments: List<TimetableOperationalTripSegment>) {
    val defaults = immutableDisplayValues(defaults)
    val segments: List<TimetableOperationalTripSegment> = java.util.List.copyOf(segments)
    init {
        require(id.isNotBlank()); require(segments.isNotEmpty())
        require(segments.none { it.unassignedUnits } || (segments.size == 1 && defaults.isEmpty())) {
            "default trip must contain one RUN and empty defaults"
        }
    }
    override fun equals(other: Any?) = other is TimetableOperationalTrip && id == other.id && defaults == other.defaults && segments == other.segments
    override fun hashCode() = java.util.Objects.hash(id, defaults, segments)
}

/** Rebuild only generated definitions; explicit IDs, defaults and unit selections are never rewritten. */
internal fun defaultOperationalTrips(services: List<TimetableService>, trips: List<TimetableOperationalTrip>): List<TimetableOperationalTrip> {
    val authored = trips.filter { trip -> trip.segments.none { it.unassignedUnits } }
    val ids = authored.mapTo(hashSetOf()) { it.id }
    val result = authored.toMutableList()
    for (service in services) for (run in service.runSegments) {
        val base = "default-run/${service.id.length}:${service.id}/${run.runId.length}:${run.runId}"
        var id = base
        var collision = 0
        while (!ids.add(id)) id = "$base#${++collision}"
        result += TimetableOperationalTrip(id, segments = listOf(
            TimetableOperationalTripSegment(service.id, run.runId, emptyList(), unassignedUnits = true)))
    }
    return java.util.List.copyOf(result)
}

/** Default RUN ownership is resolved against actual complete units, after explicit slots take priority. */
private fun TimetableOperationalTripSegment.selectedSlots(timetable: Timetable, unitCount: Int): List<Int> {
    if (!unassignedUnits) return unitSlots
    val claimed = timetable.operationalTrips.flatMap { it.segments }.filter {
        !it.unassignedUnits && it.serviceId == serviceId && it.runId == runId
    }.flatMap { it.unitSlots }.toSet()
    return (1..unitCount).filterNot(claimed::contains)
}
internal fun immutableUnitSlots(slots: List<Int>): List<Int> {
    require(slots.isNotEmpty() && slots.size <= 128 && slots.distinct().size == slots.size && slots.all { it > 0 }) { "unitSlots must be distinct positive complete-unit slots (1..128 entries)" }
    return java.util.List.copyOf(slots)
}
internal fun immutableDisplayValues(values: JsonObject): JsonObject {
    require(values.size <= 128 && values.keys.all { it.isNotBlank() }) { "display values must have at most 128 nonblank keys" }
    require(values.toString().length <= 65536) { "display values exceed 64 KiB" }
    var nodes = 0
    fun checkJson(value: JsonElement, depth: Int) {
        require(depth <= 16 && ++nodes <= 4096) { "display JSON exceeds bounded depth/node budget" }
        when (value) {
            is JsonObject -> value.forEach { (key,child) -> require(key.length <= 8192); checkJson(child,depth+1) }
            is JsonArray -> value.forEach { checkJson(it,depth+1) }
            is JsonPrimitive -> when {
                value.isString -> require(value.content.length <= 8192) { "display JSON string exceeds 8192 characters" }
                value == JsonNull || value.booleanOrNull != null -> Unit
                else -> {
                    val number = value.doubleOrNull
                    require(number != null && number.isFinite() && !(kotlin.math.floor(number) == number && kotlin.math.abs(number) > 9_007_199_254_740_991.0)) {
                        "display JSON numbers must be finite and integral values must be safe integers"
                    }
                }
            }
        }
    }
    checkJson(values,0)
    // JSON collections can wrap caller-owned mutable maps; encode/decode establishes independent ownership.
    return Json.parseToJsonElement(values.toString()).jsonObject
}

fun TimetableService.segmentAtOrBefore(stepIndex: Int): TimetableRunSegment? =
    runSegments.lastOrNull { it.startStepIndex <= stepIndex } ?: runSegments.firstOrNull()

/** LCD consumers receive facts and display values independently; call occurrence IDs preserve repeated visits. */
data class TimetableUnitStopSnapshot(val serviceId: String, val runId: String, val call: TimetableRunCall, val completed: Boolean)
data class TimetableUnitDisplaySnapshot(val unitId: String, val operationalTripId: String, val cycleIndex: Long,
    val values: JsonObject, val plannedStops: List<TimetableUnitStopSnapshot>,
    val completedStops: List<TimetableUnitStopSnapshot>, val remainingStops: List<TimetableUnitStopSnapshot>,
    val unresolvedRuns: List<String> = emptyList(), val active: Boolean = true)

internal object TimetableMetadataValidator {
    fun validate(world: World, timetable: Timetable): List<String> = buildList {
        val claims = hashSetOf<Triple<String, String, Int>>()
        timetable.services.forEach { service ->
            var end = 0
            val ids = hashSetOf<String>()
            service.runSegments.forEach { segment ->
                val prefix = "[${timetable.id}/${service.id}/${segment.runId}]"
                if (!ids.add(segment.runId)) add("$prefix duplicate runId")
                if (segment.startStepIndex < end || segment.endStepIndexExclusive > service.steps.size) add("$prefix invalid overlapping/out-of-range step scope")
                end = segment.endStepIndexExclusive
                val line = world.simpleAtoWorldData.lines[segment.lineId]
                if (line != null && segment.lineDirection != null) {
                    val stations = line.stations(segment.lineDirection)
                    val indices = segment.calls.map { stations.indexOf(StationId(it.stationId)) }
                    if (indices.any { it < 0 } || indices.zipWithNext().any { (a,b) -> b <= a }) add("$prefix calls do not follow the declared line direction")
                }
                if (segment.endStepIndexExclusive <= service.steps.size && segment.startStepIndex >= 0) {
                    addAll(validateCallFacts(world,service,segment,prefix))
                }
                if (segment.calls.isNotEmpty() && (segment.calls.first().status != TimetableServiceStopStatus.ORIGIN || segment.calls.last().status != TimetableServiceStopStatus.TERMINAL)) add("$prefix first release requires stopped ORIGIN/TERMINAL boundaries")
                segment.calls.forEach { call ->
                    val destination = world.simpleAtoWorldData.destinations[call.destinationId]
                    if (destination == null) add("$prefix call ${call.id} destination is missing: ${call.destinationId}")
                    else if ((destination.role as? DestinationRole.StationBerthRole)?.stationId?.value != call.stationId)
                        add("$prefix call ${call.id} station does not own canonical berth ${call.destinationId}")
                }
            }
        }
        if (timetable.operationalTrips.map { it.id }.distinct().size != timetable.operationalTrips.size) add("duplicate operationalTrip id")
        timetable.services.forEach { service -> service.steps.forEachIndexed { index,step ->
            val begin=(step as? TimetableStep.At)?.action as? TimetableAction.BeginOperationalTrip ?: return@forEachIndexed
            val trip=timetable.operationalTrips.singleOrNull { it.id==begin.tripId }
            val leg=trip?.segments?.singleOrNull { it.serviceId==service.id && it.runId==begin.runId }
            val run=service.runSegments.singleOrNull { it.runId==begin.runId }
            val matchingSelection = leg?.let {
                if (it.unassignedUnits) begin.unitSlots.isEmpty()
                else begin.unitSlots.isNotEmpty() && begin.unitSlots.all { slot -> slot in it.unitSlots }
            } == true
            if(leg==null || run==null || !matchingSelection) add("[${service.id}] step#$index BEGIN requires matching declared trip/run/unit slots")
            else {
                val gapStart=service.runSegments.filter { it.endStepIndexExclusive<=run.startStepIndex }.maxOfOrNull { it.endStepIndexExclusive } ?: 0
                if(index !in gapStart until run.startStepIndex || service.steps.subList(minOf(index+1,run.startStepIndex),run.startStepIndex).any { changesMembershipOrOrientation(it) })
                    add("[${service.id}] step#$index BEGIN must be in the target RUN boundary after membership/orientation changes")
            }
        } }
        timetable.operationalTrips.forEach { trip ->
            val legs = hashSetOf<Pair<String,String>>()
            val lastRunIndex=hashMapOf<String,Int>()
            trip.segments.forEach { leg ->
                if (!legs.add(leg.serviceId to leg.runId)) add("trip ${trip.id} duplicates a physical run leg")
                val service = timetable.services.singleOrNull { it.id == leg.serviceId }
                val runIndex=service?.runSegments?.indexOfFirst { it.runId==leg.runId } ?: -1
                if(runIndex<0) add("trip ${trip.id} references missing run ${leg.serviceId}/${leg.runId}")
                else {
                    val previous=lastRunIndex.put(leg.serviceId,runIndex)
                    if(previous!=null && runIndex<=previous) add("trip ${trip.id} legs are not in physical duty order")
                }
                leg.unitSlots.forEach { slot -> if (!claims.add(Triple(leg.serviceId, leg.runId, slot))) add("duplicate trip assignment ${leg.serviceId}/${leg.runId}/unit#$slot") }
            }
            trip.segments.forEachIndexed { index, leg ->
                if (trip.segments.take(index).any { prior -> tripLegPrecedes(timetable,leg,prior) })
                    add("trip ${trip.id} legs are not in physical duty order")
            }
        }
    }

    private fun changesMembershipOrOrientation(step: TimetableStep): Boolean {
        val action=(step as? TimetableStep.At)?.action ?: return false
        return when(action) {
            is TimetableAction.TakeFromDepot,is TimetableAction.TakeFromWaypoint,is TimetableAction.TakeFromFormationOutput,
            is TimetableAction.Couple,is TimetableAction.Split,TimetableAction.Reverse -> true
            is TimetableAction.Conditional -> (action.thenSteps+action.elseSteps).any(::changesMembershipOrOrientation)
            else -> false
        }
    }
    private fun validateCallFacts(world: World, service: TimetableService, segment: TimetableRunSegment, prefix: String): List<String> = buildList {
        if (segment.calls.isEmpty()) return@buildList
        val expected = arrayListOf<Pair<String,Boolean>>()
        var pending: String? = null
        var ambiguous = false
        fun movement(action: TimetableAction) {
            when (action) {
                is TimetableAction.CallStation -> pending = OperationalDestinationIds.stationBerth(StationId(action.stationId),action.berthId)
                is TimetableAction.GoTo -> {
                    if (pending != null && pending != action.wayPointId) expected += checkNotNull(pending) to false
                    expected += action.wayPointId to false
                    pending = null
                }
                is TimetableAction.GoVia -> {
                    val station = pending
                    action.wayPointIds.forEachIndexed { index,id -> expected += id to (index != action.wayPointIds.lastIndex && id != station) }
                    if (station != null && station !in action.wayPointIds) ambiguous = true
                    pending = null
                }
                is TimetableAction.SelectStop -> ambiguous = true
                is TimetableAction.Conditional -> {
                    fun changesPosition(steps: List<TimetableStep.At>): Boolean = steps.any { child ->
                        when(val a=child.action) { is TimetableAction.GoTo,is TimetableAction.GoVia,is TimetableAction.SelectStop,TimetableAction.Reverse -> true
                            is TimetableAction.Conditional -> changesPosition(a.thenSteps)||changesPosition(a.elseSteps)
                            else -> false }
                    }
                    if (changesPosition(action.thenSteps)||changesPosition(action.elseSteps)) ambiguous=true
                }
                else -> Unit
            }
        }
        service.steps.subList(segment.startStepIndex,segment.endStepIndexExclusive).filterIsInstance<TimetableStep.At>().forEach { movement(it.action) }
        if (ambiguous) add("$prefix fixed planned call facts cannot describe an ambiguous movement scope")
        val authored = segment.calls.drop(1).flatMap { call ->
            call.viaIds.map { it to true } + (call.destinationId to (call.status == TimetableServiceStopStatus.PASS))
        }
        if (expected != authored) add("$prefix planned calls and ordered via waypoints do not match scoped compiled movement occurrences/PASS/STOP status")
        segment.calls.forEach { call -> call.viaIds.forEach { waypoint ->
            if (world.wayPoints[waypoint] == null) add("$prefix call ${call.id} via waypoint is missing: $waypoint")
        } }
        var origin: String? = null
        for (step in service.steps.take(segment.startStepIndex)) {
            when(step) {
                is TimetableStep.Check -> origin=step.wayPointId
                is TimetableStep.At -> when(val action=step.action) {
                    is TimetableAction.GoTo -> origin=action.wayPointId
                    is TimetableAction.GoVia -> origin=action.wayPointIds.lastOrNull()
                    is TimetableAction.TakeFromWaypoint -> origin=action.wayPointIds.singleOrNull()
                    is TimetableAction.TakeFromDepot,is TimetableAction.TakeFromFormationOutput,is TimetableAction.Split,is TimetableAction.Couple,
                    is TimetableAction.SelectStop,is TimetableAction.Conditional,TimetableAction.Reverse -> origin=null
                    else -> Unit
                }
            }
        }
        // CHECK inside the scope can prove its origin; a first movement must not overwrite the prior origin.
        val firstStep=service.steps.getOrNull(segment.startStepIndex)
        if (firstStep is TimetableStep.Check) origin=firstStep.wayPointId
        if (origin != null && segment.calls.first().destinationId != origin) add("$prefix origin call disagrees with known compiled origin")
    }

    fun validatePhysical(world: World, timetable: Timetable, structural: StructuralTimetableValidation): List<String> = buildList {
        // Dynamic defaults bind actual units on entry. They do not require a frozen depot candidate
        // during definition validation; explicit cross-RUN/unit assignments retain their proof gate.
        if (timetable.operationalTrips.none { trip -> trip.segments.none { it.unassignedUnits } }) return@buildList
        val store=world.simpleAtoWorldData.depots
        val proven=linkedMapOf<Pair<String,String>,List<List<String>>>()
        fun units(cars: List<String>,reversed: Boolean): List<String>? = runCatching {
            store.fixedUnitsForFormation(if(reversed) cars.reversed() else cars).map { it.id }
        }.getOrNull()
        timetable.services.forEach { service -> service.runSegments.forEach { segment ->
            val key=service.id to segment.runId
            val assigned = timetable.operationalTrips.any { trip -> trip.segments.any { !it.unassignedUnits && it.serviceId==service.id && it.runId==segment.runId } }
            val outputs=structural.routes.filterKeys { it.serviceId==service.id && it.stepIndex in segment.startStepIndex until segment.endStepIndexExclusive }.values
            var candidates=outputs.mapNotNull { units(it.outputCarIds,it.expectedReversed) }.distinct()
            if (assigned && outputs.any { units(it.outputCarIds,it.expectedReversed)==null }) add("[${service.id}/${segment.runId}] output has no complete registered-unit evidence")
            val prefix=service.steps.take(segment.startStepIndex)
            if (candidates.isEmpty() && prefix.none { val a=(it as? TimetableStep.At)?.action; a is TimetableAction.Couple || a is TimetableAction.Split || a is TimetableAction.TakeFromFormationOutput }) {
                val take=(service.steps.firstOrNull() as? TimetableStep.At)?.action
                val sourceTrains=world.allTrains.filterIsInstance<CoreTrain>().filter { train ->
                    when(val selector=service.trainSelector) {
                        is TrainSelector.TrainId -> selector.trainId==train.id
                        TrainSelector.AnyTrain -> when(take) {
                            is TimetableAction.TakeFromDepot -> take.selector.matches(world.simpleAtoWorldData.candidateFacts(train),train.cars.size)
                            is TimetableAction.TakeFromWaypoint -> take.wayPointIds.any { id ->
                                val point=world.simpleAtoWorldData.destinations[id]
                                point != null && point.railId==train.publishedFrame.head.railId && kotlin.math.abs(point.arcLengthInAbMeters-train.publishedFrame.head.arcLengthInAbMeters)<=0.5
                            }
                            else -> false
                        }
                    }
                }
                val reversed=prefix.filterIsInstance<TimetableStep.At>().count { it.action==TimetableAction.Reverse }%2==1
                candidates=sourceTrains.mapNotNull { units(it.cars.map { car->car.id },it.isReversed xor reversed) }.distinct()
                if(assigned && sourceTrains.isNotEmpty() && sourceTrains.any { units(it.cars.map { car->car.id },it.isReversed)==null }) add("[${service.id}/${segment.runId}] source contains unregistered or partial units")
            }
            if(candidates.isEmpty() && assigned) add("[${service.id}/${segment.runId}] operational trip has no registered physical unit evidence")
            else proven[key]=candidates
        } }
        timetable.operationalTrips.forEach { trip -> trip.segments.forEach { leg ->
            val candidates=proven[leg.serviceId to leg.runId].orEmpty()
            if(candidates.any { formation -> leg.unitSlots.any { it>formation.size } }) add("trip ${trip.id} references absent complete-unit slot at ${leg.serviceId}/${leg.runId}")
        } }
        fun selected(leg: TimetableOperationalTripSegment): Set<String> = proven[leg.serviceId to leg.runId].orEmpty()
            .flatMap { formation -> leg.selectedSlots(timetable, formation.size).mapNotNull { formation.getOrNull(it-1) } }.toSet()
        timetable.operationalTrips.forEach { trip ->
            trip.segments.forEach { leg ->
                val predecessors = trip.segments.filter { it !== leg && tripLegPrecedes(timetable,it,leg) }
                if (predecessors.isNotEmpty() && !predecessors.flatMap(::selected).toSet().containsAll(selected(leg)))
                    add("trip ${trip.id} transfers to an unrelated physical unit at ${leg.serviceId}/${leg.runId}")
                for (before in predecessors) {
                    val continuing = selected(before).intersect(selected(leg))
                    if (continuing.isEmpty()) continue
                    timetable.services.forEach { service -> service.runSegments.forEach { run ->
                        val middle = TimetableOperationalTripSegment(service.id,run.runId,listOf(1))
                        if (tripLegPrecedes(timetable,before,middle) && tripLegPrecedes(timetable,middle,leg)) {
                            val present = proven[service.id to run.runId].orEmpty().flatten().toSet().intersect(continuing)
                            val assigned = trip.segments.filter { it.serviceId==service.id && it.runId==run.runId }.flatMap(::selected).toSet()
                            if (!assigned.containsAll(present)) add("trip ${trip.id} omits an intervening physical run ${service.id}/${run.runId}")
                        }
                    } }
                }
            }
        }
    }
}

private fun tripLegPrecedes(timetable: Timetable, from: TimetableOperationalTripSegment, target: TimetableOperationalTripSegment): Boolean {
    val fromService = timetable.services.singleOrNull { it.id == from.serviceId } ?: return false
    val fromRun = fromService.runSegments.singleOrNull { it.runId == from.runId } ?: return false
    val targetService = timetable.services.singleOrNull { it.id == target.serviceId } ?: return false
    val targetRun = targetService.runSegments.singleOrNull { it.runId == target.runId } ?: return false
    val queue = java.util.ArrayDeque<Pair<String,Int>>()
    val seen = hashSetOf<Pair<String,Int>>()
    queue.add(from.serviceId to fromRun.endStepIndexExclusive)
    while (queue.isNotEmpty()) {
        val (serviceId,index) = queue.removeFirst()
        if (!seen.add(serviceId to index)) continue
        if (serviceId == target.serviceId && index <= targetRun.startStepIndex) return true
        val service = timetable.services.singleOrNull { it.id == serviceId } ?: continue
        for (i in index until service.steps.size) {
            val action = (service.steps[i] as? TimetableStep.At)?.action
            val eventId = when(action) { is TimetableAction.Split -> action.eventId; is TimetableAction.Couple -> action.eventId; else -> null } ?: continue
            val event = timetable.formationEvents.singleOrNull { it.id == eventId } ?: continue
            event.outputs.forEach { output ->
                val duty = (output.disposition as? FormationOutputDisposition.Duty)?.dutyId ?: return@forEach
                queue.add(duty to if (duty == serviceId) i+1 else 1)
            }
            if (event.outputs.none { it.disposition == FormationOutputDisposition.Duty(serviceId) }) break
        }
    }
    return false
}

/** Dispatcher-local ownership; identity follows registered units across train replacement and structural handoff. */
internal class TimetableDisplayRuntime {
    private data class Key(val timetableId: String, val cycle: Long, val unitId: String)
    private class Context(val trip: TimetableOperationalTrip, var values: JsonObject) {
        var active = true
        val visitedLegs = linkedSetOf<Pair<String,String>>()
        val completed = linkedSetOf<Triple<String,String,String>>()
    }
    private val contexts = linkedMapOf<Key, Context>()
    private val legUnits = linkedMapOf<Triple<String,Long,Pair<String,String>>, List<String>>()
    private val actualLegs = hashSetOf<Triple<String,Long,Pair<String,String>>>()
    private data class Seed(val index: Int, val units: List<String>)
    private val seeds = linkedMapOf<Triple<String,Long,String>, Seed>()

    var revision: Long = 0L
        private set

    private data class ContextState(val trip: TimetableOperationalTrip, val values: JsonObject, val active: Boolean,
        val visited: Set<Pair<String,String>>, val completed: Set<Triple<String,String,String>>)
    private data class State(val contexts: Map<Key,ContextState>,
        val bindings: Map<Triple<String,Long,Pair<String,String>>,List<String>>,
        val actual: Set<Triple<String,Long,Pair<String,String>>>, val seeds: Map<Triple<String,Long,String>,Seed>)
    private fun state() = State(contexts.mapValues { (_, context) -> ContextState(context.trip,context.values,
        context.active,context.visitedLegs.toSet(),context.completed.toSet()) }, legUnits.toMap(), actualLegs.toSet(),
        seeds.mapValues { (_, seed) -> seed.copy(units = seed.units.toList()) })
    private inline fun mutate(action: () -> Unit) {
        val before = state()
        try { action() } finally { if (state() != before) revision = Math.incrementExact(revision) }
    }

    /** Context keys belong to this runtime. Never ask callers to guess a timetable or cycle. */
    fun currentSnapshot(unitId: String, timetable: (String) -> Timetable?): Pair<String,TimetableUnitDisplaySnapshot>? {
        val key = contexts.keys.filter { it.unitId == unitId }.singleOrNull() ?: return null
        val source = timetable(key.timetableId) ?: return null
        if (source.id != key.timetableId) return null
        val snapshot = snapshots(source,key.cycle,listOf(unitId)).singleOrNull() ?: return null
        return key.timetableId to snapshot
    }

    /** Predict only fixed complete-unit operations from actual seed membership, never dynamic branch results. */
    fun proveFuture(timetable: Timetable, cycle: Long, service: TimetableService, segment: TimetableRunSegment, units: List<String>,
        couplingOrder: (String, Set<String>) -> List<String>?) = mutate {
        seeds[Triple(timetable.id,cycle,service.id)] = Seed(segment.startStepIndex, units)
        data class Cursor(val service: TimetableService, var index: Int, var units: List<String>?, var done: Boolean = false)
        val cursors = timetable.services.map { duty -> val seed = seeds[Triple(timetable.id,cycle,duty.id)]; Cursor(duty, seed?.index ?: 0, seed?.units) }
        val outputs = hashMapOf<Pair<String,String>,List<String>>()
        val completed = hashSetOf<String>()
        fun changesUnitOrder(steps: List<TimetableStep.At>): Boolean = steps.any { step -> when(val action=step.action) {
            TimetableAction.Reverse, is TimetableAction.Couple, is TimetableAction.Split,
            is TimetableAction.TakeFromDepot, is TimetableAction.TakeFromWaypoint, is TimetableAction.TakeFromFormationOutput -> true
            is TimetableAction.Conditional -> changesUnitOrder(action.thenSteps) || changesUnitOrder(action.elseSteps)
            else -> false
        } }
        fun assign(event: TimetableFormationEvent, output: TimetableFormationOutput, ids: List<String>, source: Cursor?) {
            val duty = (output.disposition as? FormationOutputDisposition.Duty)?.dutyId ?: return
            if (source?.service?.id == duty) source.units = ids else outputs[event.id to output.id] = ids
        }
        var progress: Boolean
        do {
            progress = false
            for (cursor in cursors) {
                if (cursor.done) continue
                if (cursor.units == null) {
                    val take = (cursor.service.steps.firstOrNull() as? TimetableStep.At)?.action as? TimetableAction.TakeFromFormationOutput ?: continue
                    cursor.units = outputs[take.eventId to take.outputId] ?: continue
                    cursor.index = 1
                }
                while (cursor.index < cursor.service.steps.size) {
                    val scope = cursor.service.runSegments.singleOrNull { it.startStepIndex == cursor.index }
                    if (scope != null) {
                        val key = Triple(timetable.id,cycle,cursor.service.id to scope.runId)
                        if (key !in actualLegs) legUnits[key] = java.util.List.copyOf(checkNotNull(cursor.units))
                    }
                    val action = (cursor.service.steps[cursor.index] as? TimetableStep.At)?.action
                    if (action is TimetableAction.Couple) break
                    if (action is TimetableAction.Conditional && (changesUnitOrder(action.thenSteps) || changesUnitOrder(action.elseSteps))) {
                        cursor.done = true; break // No guessed unit order after a runtime branch.
                    }
                    if (action is TimetableAction.Reverse) cursor.units = checkNotNull(cursor.units).reversed()
                    if (action is TimetableAction.Split) {
                        val event = timetable.formationEvents.single { it.id == action.eventId } as TimetableFormationEvent.Split
                        val ids = checkNotNull(cursor.units)
                        require(event.firstUnitCount < ids.size) { "fixed split does not leave complete registered units" }
                        assign(event,event.first,ids.take(event.firstUnitCount),cursor)
                        assign(event,event.rest,ids.drop(event.firstUnitCount),cursor)
                        if (event.outputs.none { it.disposition == FormationOutputDisposition.Duty(cursor.service.id) }) cursor.done = true
                    }
                    cursor.index++
                    progress = true
                    if (cursor.done) break
                }
                if (cursor.index >= cursor.service.steps.size) cursor.done = true
            }
            timetable.formationEvents.filterIsInstance<TimetableFormationEvent.Couple>().filter { it.id !in completed }.forEach { event ->
                val participants = cursors.filter { it.service.id in event.participantDutyIds && !it.done }
                if (participants.size == 2 && participants.all { (it.service.steps.getOrNull(it.index) as? TimetableStep.At)?.action == TimetableAction.Couple(event.id) && it.units != null }) {
                    val all = participants.flatMap { checkNotNull(it.units) }
                    if (all.distinct().size != all.size) error("coupling would duplicate registered units")
                    val ordered = couplingOrder(event.id,all.toSet())
                    if (ordered != null) {
                        require(ordered.toSet() == all.toSet()) { "coupling unit proof changed membership" }
                        val target = participants.singleOrNull { event.output.disposition == FormationOutputDisposition.Duty(it.service.id) }
                        assign(event,event.output,ordered,target)
                        participants.forEach { it.index++; if (it !== target) it.done = true }
                        completed += event.id
                        progress = true
                    }
                }
            }
        } while (progress)
    }


    fun begin(timetable: Timetable, cycle: Long, service: TimetableService, action: TimetableAction.BeginOperationalTrip, units: List<String>) = mutate {
        val trip=requireNotNull(timetable.operationalTrips.singleOrNull { it.id==action.tripId }) { "operational trip is missing" }
        val leg=requireNotNull(trip.segments.singleOrNull { it.serviceId==service.id && it.runId==action.runId }) { "trip does not own upcoming RUN" }
        require(if (leg.unassignedUnits) action.unitSlots.isEmpty()
            else action.unitSlots.isNotEmpty() && action.unitSlots.all { it<=units.size && it in leg.unitSlots }) {
            "BEGIN references absent or unassigned complete units"
        }
        val identity=Triple(timetable.id,cycle,service.id to action.runId)
        require(legUnits[identity].let { it==null || it==units }) { "BEGIN physical unit proof changed" }
        val slots = if (leg.unassignedUnits) leg.selectedSlots(timetable, units.size) else action.unitSlots
        val selected=slots.map { units[it-1] }
        selected.forEach { id ->
            val key=Key(timetable.id,cycle,id)
            val hasPredecessor=trip.segments.any { it!==leg && tripLegPrecedes(timetable,it,leg) }
            require(!hasPredecessor || (contexts[key]?.trip?.id==trip.id && contexts[key]?.active==true)) { "BEGIN cannot transfer trip to unrelated unit $id" }
        }
        legUnits[identity]=java.util.List.copyOf(units)
        selected.forEach { id ->
            contexts.keys.removeIf { it.unitId==id && (it.timetableId!=timetable.id || it.cycle!=cycle) }
            val key=Key(timetable.id,cycle,id)
            val current=contexts[key]
            contexts[key]=(if(current?.trip?.id==trip.id) current else Context(trip,trip.defaults)).also { it.active=true }
        }
        pruneUnownedCycles()
    }

    fun retire(timetableId: String, cycle: Long, unitIds: List<String>) = mutate {
        unitIds.forEach { contexts[Key(timetableId,cycle,it)]?.active=false }
    }

    fun finish(timetable: Timetable, cycle: Long, serviceId: String, units: List<String>) = mutate {
        units.forEach { id ->
            val context=contexts[Key(timetable.id,cycle,id)] ?: return@forEach
            val ownedLegs=context.trip.segments.filter { leg -> legUnits[Triple(timetable.id,cycle,leg.serviceId to leg.runId)]?.let { binding -> leg.selectedSlots(timetable,binding.size).any { binding.getOrNull(it-1)==id } }==true }
            val unresolved=context.trip.segments.any { legUnits[Triple(timetable.id,cycle,it.serviceId to it.runId)]==null }
            if(!unresolved && ownedLegs.lastOrNull()?.serviceId==serviceId && ownedLegs.all { (it.serviceId to it.runId) in context.visitedLegs }) context.active=false
        }
    }

    fun enter(timetable: Timetable, cycle: Long, service: TimetableService, segment: TimetableRunSegment, units: List<String>) = mutate {
        val selections = timetable.operationalTrips.flatMap { trip -> trip.segments.filter { it.serviceId == service.id && it.runId == segment.runId }
            .map { Triple(trip,it,it.selectedSlots(timetable,units.size)) } }
        selections.forEach { (_,_,slots) -> require(slots.all { it <= units.size }) { "run ${service.id}/${segment.runId} references absent complete-unit slot" } }
        val identity = Triple(timetable.id,cycle,service.id to segment.runId)
        val previous = legUnits[identity]
        require(previous == null || previous == units) { "physical unit membership changed within a run segment" }
        selections.forEach { (trip,leg,slots) -> slots.forEach { slot ->
            val key = Key(timetable.id,cycle,units[slot-1])
            val predecessor = trip.segments.any { before -> before !== leg && tripLegPrecedes(timetable,before,leg) }
            require(!predecessor || (contexts[key]?.trip?.id == trip.id && contexts[key]?.active == true)) {
                "operational trip ${trip.id} cannot transfer to unrelated unit ${units[slot-1]} at ${leg.serviceId}/${leg.runId}"
            }
        } }
        // Binding is immutable per leg/cycle. Future stop projection uses only these proven physical bindings.
        legUnits[identity] = java.util.List.copyOf(units)
        actualLegs += identity
        val selected=selections.flatMap { (_,_,slots)->slots.map { units[it-1] } }.toSet()
        units.forEach { id -> contexts[Key(timetable.id,cycle,id)]?.takeIf { id !in selected }?.active=false }
        selected.forEach { id -> contexts.keys.removeIf { it.unitId==id && (it.timetableId!=timetable.id || it.cycle!=cycle) } }
        selections.forEach { (trip,_,slots) -> slots.forEach { slot ->
            val key = Key(timetable.id,cycle,units[slot-1])
            val current = contexts[key]
            val next = if (current?.trip?.id == trip.id) current else Context(trip, trip.defaults)
            next.active=true
            next.visitedLegs += service.id to segment.runId
            contexts[key] = next
        } }
        pruneUnownedCycles()
    }
    private fun pruneUnownedCycles() {
        val owned=contexts.keys.map { it.timetableId to it.cycle }.toSet()
        legUnits.keys.removeIf { (it.first to it.second) !in owned }
        actualLegs.removeIf { (it.first to it.second) !in owned }
        seeds.keys.removeIf { (it.first to it.second) !in owned }
    }
    fun set(timetableId: String, cycle: Long, unitIds: List<String>, values: JsonObject?, remove: List<String> = emptyList()) = mutate {
        val selected = unitIds.map { id -> requireNotNull(contexts[Key(timetableId,cycle,id)]?.takeIf { it.active }) { "unit $id has no active operational trip" } }
        val updates = selected.map { context -> if (values == null) context.trip.defaults else immutableDisplayValues(JsonObject(context.values.toMutableMap().also { map -> remove.forEach(map::remove); map.putAll(values) })) }
        selected.zip(updates).forEach { (context,value) -> context.values = value }
    }
    fun reached(timetableId: String, cycle: Long, serviceId: String, runId: String, callId: String, units: List<String>) = mutate {
        units.forEach { id -> contexts[Key(timetableId,cycle,id)]?.completed?.add(Triple(serviceId,runId,callId)) }
    }
    private data class ProjectedStops(
        val stops: List<TimetableUnitStopSnapshot>, val indices: Map<Triple<String,String,String>,Int>,
        val unresolved: List<String>, val uncertainFromIndex: Int?,
    )
    private fun projectStops(timetable: Timetable, cycle: Long, id: String, context: Context): ProjectedStops {
        val stops = arrayListOf<TimetableUnitStopSnapshot>()
        val indices = linkedMapOf<Triple<String,String,String>,Int>()
        val unresolved = arrayListOf<String>()
        var uncertainFromIndex: Int? = null
        var contiguous = true
        context.trip.segments.forEach { leg ->
            val binding = legUnits[Triple(timetable.id,cycle,leg.serviceId to leg.runId)]
            if (binding == null) {
                unresolved += "${leg.serviceId}/${leg.runId}"
                contiguous = false
                if (uncertainFromIndex == null) uncertainFromIndex = stops.size
                return@forEach
            }
            if (leg.selectedSlots(timetable,binding.size).none { binding.getOrNull(it-1) == id }) { contiguous = false; return@forEach }
            val segment = timetable.services.single { it.id == leg.serviceId }.runSegments.single { it.runId == leg.runId }
            if (segment.calls.isEmpty()) contiguous = false
            segment.calls.forEach { call ->
                val identity = Triple(leg.serviceId,leg.runId,call.id)
                val prior = stops.lastOrNull()
                val joined = contiguous && call.status == TimetableServiceStopStatus.ORIGIN && prior?.call?.status == TimetableServiceStopStatus.TERMINAL && prior.call.destinationId == call.destinationId
                if (joined) {
                    val priorStop = checkNotNull(prior)
                    stops[stops.lastIndex] = priorStop.copy(call = priorStop.call.copy(status = TimetableServiceStopStatus.STOP, departureTick = call.departureTick),
                        completed = priorStop.completed || identity in context.completed)
                } else stops += TimetableUnitStopSnapshot(leg.serviceId,leg.runId,call,identity in context.completed)
                // Both exact identities of a joined TERMINAL/ORIGIN address the same occurrence.
                indices[identity] = stops.lastIndex
                contiguous = true
            }
        }
        return ProjectedStops(stops,indices,unresolved,uncertainFromIndex)
    }

    /** Arrival proof and actual movement evidence come only from the serialized Dispatcher owner. */
    fun captureProgress(timetable: Timetable, cycle: Long, unitId: String,
        arrived: Triple<String,String,String>?, inTransit: Boolean): TimetableDisplayProgress {
        val unknown = TimetableDisplayProgress(TimetableDisplayPhase.UNKNOWN)
        val context = contexts[Key(timetable.id,cycle,unitId)]?.takeIf { it.active && it.visitedLegs.isNotEmpty() } ?: return unknown
        val projected = projectStops(timetable,cycle,unitId,context)
        fun certain(index: Int) = projected.uncertainFromIndex?.let { index < it } ?: true
        if (arrived != null) {
            val index = projected.indices[arrived] ?: return unknown
            if (!certain(index) || !projected.stops[index].completed ||
                projected.stops[index].call.status == TimetableServiceStopStatus.PASS) return unknown
            return TimetableDisplayProgress(TimetableDisplayPhase.AT_STATION,index)
        }
        if (!inTransit) return unknown
        val next = projected.stops.indexOfFirst { !it.completed }
        if (next == -1) return if (projected.unresolved.isEmpty() && projected.stops.isNotEmpty())
            TimetableDisplayProgress(TimetableDisplayPhase.FINISHED) else unknown
        // A hole in reached evidence cannot be repaired by an arbitrary later visit.
        if (!certain(next) || projected.stops.drop(next).any { it.completed }) return unknown
        return TimetableDisplayProgress(TimetableDisplayPhase.IN_TRANSIT,next)
    }

    fun progressChanged() { revision = Math.incrementExact(revision) }

    fun snapshots(timetable: Timetable, cycle: Long, units: List<String>): List<TimetableUnitDisplaySnapshot> = java.util.List.copyOf(units.mapNotNull { id ->
        val context = contexts[Key(timetable.id,cycle,id)] ?: return@mapNotNull null
        val projection = projectStops(timetable,cycle,id,context)
        val stops = projection.stops
        TimetableUnitDisplaySnapshot(id,context.trip.id,cycle,context.values,java.util.List.copyOf(stops),java.util.List.copyOf(stops.filter { it.completed }),java.util.List.copyOf(stops.filterNot { it.completed }),java.util.List.copyOf(projection.unresolved),context.active)
    })
    fun retainCycles(active: Set<Pair<String,Long>>) = mutate {
        contexts.keys.removeIf { (it.timetableId to it.cycle) !in active }
        legUnits.keys.removeIf { (it.first to it.second) !in active }
        actualLegs.removeIf { (it.first to it.second) !in active }
        seeds.keys.removeIf { (it.first to it.second) !in active }
    }
    fun clear() = mutate { contexts.clear(); legUnits.clear(); actualLegs.clear(); seeds.clear() }
}
