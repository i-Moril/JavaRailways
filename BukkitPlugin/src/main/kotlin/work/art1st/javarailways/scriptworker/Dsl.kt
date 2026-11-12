package work.art1st.javarailways.scriptworker

import kotlinx.serialization.json.*
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Value

class TimetableObjectSelectorValue internal constructor(val saved: SavedObjectSelectorV1)

class ServiceTrainAcquisition internal constructor(
    val tick: Long,
    val action: SavedTimetableActionV1
)

class TimetableBuilder(private val id: String) {
    private var periodDays: Long? = null
    private val services = mutableListOf<SavedTimetableServiceV1>()
    private val diagramLines = mutableListOf<SavedDiagramLineV1>()
    private val initialPositions = mutableListOf<SavedInitialPositionV1>()
    private val compilationTrace = mutableListOf<SavedCompilationTraceV1>()
    private val lineSpeedLimits = mutableListOf<SavedTimetableLineSpeedLimitV1>()
    private val formationEvents = mutableListOf<SavedFormationEventV1>()
    private val operationalTrips = mutableListOf<SavedTimetableOperationalTripV1>()

    @HostAccess.Export fun setPeriodDays(days: Long): TimetableBuilder { require(days > 0); Math.multiplyExact(days, 24_000); periodDays = days; return this }
    @HostAccess.Export
    fun lineSpeedLimit(lineId: String, maximumSpeedMetersPerSecond: Double): TimetableBuilder {
        require(lineId.isNotBlank()) { "lineId must not be blank" }
        require(maximumSpeedMetersPerSecond.isFinite() && maximumSpeedMetersPerSecond > 0.0) {
            "line maximum speed must be finite and positive"
        }
        require(lineSpeedLimits.none { it.lineId == lineId }) { "duplicate line speed limit: $lineId" }
        lineSpeedLimits += SavedTimetableLineSpeedLimitV1(lineId, maximumSpeedMetersPerSecond)
        return this
    }

    @HostAccess.Export
    fun diagramLine(lineId: String, options: Value): TimetableBuilder {
        require(lineId.isNotBlank()) { "diagram line id must not be blank" }
        require(diagramLines.none { it.id == lineId }) { "duplicate diagram line id: $lineId" }
        options.requireOnly(setOf("upStations", "downStations"), "diagramLine options")
        val up = options.member("upStations")?.let(::strings).orEmpty()
        val down = options.member("downStations")?.let(::strings).orEmpty()
        require(up.isNotEmpty() && down.isNotEmpty()) { "diagramLine directions must not be empty" }
        require(up.distinct().size == up.size && down.distinct().size == down.size) {
            "diagramLine directions must not contain duplicate stations"
        }
        diagramLines += SavedDiagramLineV1(lineId, up, down)
        return this
    }

    @HostAccess.Export fun service(serviceId: String, callback: Value): TimetableBuilder = addService(serviceId, null, null, callback)
    @HostAccess.Export fun service(serviceId: String, selector: Value, callback: Value): TimetableBuilder = addService(serviceId, selector, null, callback)
    @HostAccess.Export fun service(serviceId: String, selector: Value, options: Value, callback: Value): TimetableBuilder = addService(serviceId, selector, options, callback)

    @HostAccess.Export
    fun formationEvent(eventId: Value, definition: Value): TimetableBuilder {
        val id = fixedFormationId(eventId)
        definition.requireOnly(setOf("kind", "coordinatorDutyId", "approachingDutyId", "stationaryDutyId", "stationaryEnd",
            "rendezvous", "maximumApproachSpeedMetersPerSecond", "output", "partition", "outputs"), "formation event")
        require(formationEvents.none { it.id == id }) { "duplicate formation event: $id" }
        val value = toJson(definition, 8) as? JsonObject ?: error("formation event must be an object")
        val declaration = Json.decodeFromJsonElement(SavedFormationEventV1.serializer(), JsonObject(value + ("id" to JsonPrimitive(id))))
        formationEvents += declaration
        return this
    }

    @HostAccess.Export fun operationalTrip(tripId: String, definition: Value): TimetableBuilder {
        require(tripId.isNotBlank() && tripId == tripId.trim()) { "invalid operational trip id" }
        definition.requireOnly(setOf("defaults", "segments"), "operational trip")
        val raw = toJson(definition, 24) as? JsonObject ?: error("operational trip must be an object")
        val trip = Json.decodeFromJsonElement(SavedTimetableOperationalTripV1.serializer(), JsonObject(raw + ("id" to JsonPrimitive(tripId))))
        require(operationalTrips.none { it.id == tripId }) { "duplicate operational trip id: $tripId" }
        require(trip.segments.isNotEmpty()) { "operational trip must contain segments" }
        trip.segments.forEach { requireUnitSlots(it.unitSlots) }
        validateDisplayValues(trip.defaults)
        operationalTrips += trip
        return this
    }

    @HostAccess.Export
    fun takeFromFormationOutput(eventId: Value, outputId: Value, tick: Long): ServiceTrainAcquisition {
        require(tick in 0L..9_007_199_254_740_991L) { "formation acquisition tick must be a nonnegative safe integer" }
        return ServiceTrainAcquisition(tick, SavedTimetableActionV1(SavedTimetableActionTypeV1.TAKE_FROM_FORMATION_OUTPUT,
            eventId = fixedFormationId(eventId), outputId = fixedFormationId(outputId)))
    }

    @HostAccess.Export
    fun takeFromDepot(depotId: String, tick: Long): ServiceTrainAcquisition =
        takeFromDepot(depotId, Value.asValue(null), tick)

    @HostAccess.Export
    fun takeFromDepot(depotId: String, options: Value, tick: Long): ServiceTrainAcquisition {
        require(depotId.isNotBlank()) { "depotId must not be blank" }
        require(tick >= 0L) { "takeFromDepot tick must not be negative" }
        options.requireOnly(
            setOf("tags", "minimumCars", "maximumCars", "minimumSpeedMetersPerSecond", "capabilities"),
            "takeFromDepot options"
        )
        return ServiceTrainAcquisition(
            tick,
            SavedTimetableActionV1(
                SavedTimetableActionTypeV1.TAKE_FROM_DEPOT,
                depotId = depotId,
                requiredTags = options.strings("tags"),
                minimumCars = options.int("minimumCars"),
                maximumCars = options.int("maximumCars"),
                minimumSpeedMetersPerSecond = options.double("minimumSpeedMetersPerSecond"),
                requiredCapabilities = options.strings("capabilities")
            )
        )
    }

    @HostAccess.Export
    fun takeFromWaypoint(target: Value, tick: Long): ServiceTrainAcquisition {
        require(tick >= 0L) { "takeFromWaypoint tick must not be negative" }
        return ServiceTrainAcquisition(
            tick,
            SavedTimetableActionV1(
                SavedTimetableActionTypeV1.TAKE_FROM_WAYPOINT,
                wayPointIds = strings(target).distinct()
            )
        )
    }

    @HostAccess.Export
    fun setInitialPosition(trainSelector: Value, wayPointSelector: Value): TimetableBuilder {
        initialPositions += SavedInitialPositionV1(
            objectSelector(trainSelector),
            objectSelector(wayPointSelector)
        )
        return this
    }

    @HostAccess.Export fun named(name: String): TimetableObjectSelectorValue {
        require(name.isNotBlank()) { "name selector must not be blank" }
        return TimetableObjectSelectorValue(
            SavedObjectSelectorV1(SavedObjectSelectorTypeV1.REGEX, pattern = "^${Regex.escape(name)}$")
        )
    }
    @HostAccess.Export fun tags(vararg tags: String): TimetableObjectSelectorValue {
        val required = tags.toSet()
        require(required.isNotEmpty() && required.all(String::isNotBlank)) {
            "tag selector requires at least one non-blank tag"
        }
        return TimetableObjectSelectorValue(
            SavedObjectSelectorV1(SavedObjectSelectorTypeV1.TAGS, tags = required)
        )
    }
    @HostAccess.Export fun and(vararg selectors: Value) = TimetableObjectSelectorValue(
        SavedObjectSelectorV1(
            SavedObjectSelectorTypeV1.AND,
            selectors = selectors.map(::objectSelector).also { require(it.isNotEmpty()) }
        )
    )
    @HostAccess.Export fun or(vararg selectors: Value) = TimetableObjectSelectorValue(
        SavedObjectSelectorV1(
            SavedObjectSelectorTypeV1.OR,
            selectors = selectors.map(::objectSelector).also { require(it.isNotEmpty()) }
        )
    )
    @HostAccess.Export fun not(value: Value): Any {
        if (value.isHostObject) {
            val host = value.asHostObject<Any>()
            if (host is SavedTimetableConditionV1) {
                return SavedTimetableConditionV1(
                    SavedTimetableConditionTypeV1.NOT,
                    conditions = listOf(host)
                )
            }
        }
        return TimetableObjectSelectorValue(
            SavedObjectSelectorV1(
                SavedObjectSelectorTypeV1.NOT,
                selectors = listOf(objectSelector(value))
            )
        )
    }

    @HostAccess.Export fun predicate(typeId: String, parameters: Value): SavedTimetableConditionV1 = predicate(typeId, 1, parameters)
    @HostAccess.Export fun predicate(typeId: String, version: Int, parameters: Value): SavedTimetableConditionV1 = SavedTimetableConditionV1(SavedTimetableConditionTypeV1.PREDICATE, call = extension(typeId, version, parameters))
    @HostAccess.Export fun all(vararg conditions: SavedTimetableConditionV1) = SavedTimetableConditionV1(SavedTimetableConditionTypeV1.ALL, conditions = conditions.toList())
    @HostAccess.Export fun any(vararg conditions: SavedTimetableConditionV1) = SavedTimetableConditionV1(SavedTimetableConditionTypeV1.ANY, conditions = conditions.toList())

    private fun addService(serviceId: String, selector: Value?, options: Value?, callback: Value): TimetableBuilder {
        require(serviceId.isNotBlank()); require(callback.canExecute()) { "service builder must be a function" }
        var acquisition: ServiceTrainAcquisition? = null
        val trainId = selector?.takeUnless { it.isNull }?.let { value ->
            when {
                value.isString -> value.asString()
                value.isHostObject -> {
                    acquisition = value.asHostObject<ServiceTrainAcquisition>()
                    null
                }
                else -> error(
                    "selector must be a train id, timetable.takeFromDepot(...), " +
                        "timetable.takeFromWaypoint(...), or null"
                )
            }
        }
        val builder = ServiceBuilder()
        acquisition?.let { builder.add(it.tick, it.action) }
        callback.executeVoid(builder)
        options?.requireOnly(setOf("activation", "diagramLineId", "lineDirection", "lineId"), "service options")
        val activation = options?.member("activation")?.takeUnless { it.isNull }?.let { activation(it) } ?: SavedTimetableActivationV1()
        val diagramLineId = options?.string("diagramLineId")?.also {
            require(it.isNotBlank()) { "diagramLineId must not be blank" }
        }
        val lineId = options?.string("lineId")?.also {
            require(it.isNotBlank()) { "lineId must not be blank" }
        }
        val lineDirection = options?.string("lineDirection")?.uppercase()?.let {
            runCatching { SavedLineDirectionV1.valueOf(it) }.getOrElse { error("lineDirection must be UP or DOWN") }
        }
        require(diagramLineId != null || lineDirection == null) { "lineDirection requires diagramLineId" }
        services += SavedTimetableServiceV1(serviceId, if (trainId == null) SavedTrainSelectorTypeV1.ANY else SavedTrainSelectorTypeV1.TRAIN_ID, trainId, builder.buildSteps(), activation, diagramLineId, lineDirection, lineId, builder.buildRunSegments())
        compilationTrace += builder.buildTrace().map { trace ->
            SavedCompilationTraceV1(serviceId, trace.stepIndex, trace.origin, trace.branchPath)
        }
        return this
    }

    fun build(): SavedTimetableV1 {
        require(id.isNotBlank()) { "timetable id must not be blank" }
        require(services.map { it.id }.distinct().size == services.size) { "duplicate service id" }
        val preparedTrips = normalizeOperationalTrips(services, operationalTrips)
        val preparedTrace = compilationTrace.toMutableList()
        val preparedServices = services.map { service -> prepareDisplayBoundaries(service, preparedTrips, preparedTrace) }
        val assignments = mutableSetOf<Triple<String, String, Int>>()
        preparedTrips.forEach { trip -> trip.segments.forEach { segment ->
            val service = services.singleOrNull { it.id == segment.serviceId } ?: error("operational trip references missing service")
            require(service.runSegments.any { it.runId == segment.runId }) { "operational trip references missing run segment" }
            segment.unitSlots.forEach { slot -> require(assignments.add(Triple(segment.serviceId, segment.runId, slot))) {
                "duplicate operational trip unit assignment"
            } }
        } }
        return SavedTimetableV1(
            id,
            requireNotNull(periodDays) { "setPeriodDays() is required" },
            preparedServices,
            diagramLines.toList(),
            initialPositions.toList(),
            preparedTrace.toList(),
            lineSpeedLimits.toList(),
            formationEvents.toList(),
            preparedTrips
        )
    }
}

/** Keep authored definitions, then rebuild one deterministic default for each physical RUN. */
internal fun normalizeOperationalTrips(
    services: List<SavedTimetableServiceV1>,
    trips: List<SavedTimetableOperationalTripV1>,
): List<SavedTimetableOperationalTripV1> {
    // Mirror the domain shape check before dropping old generated entries during normalization.
    trips.filter { trip -> trip.segments.any { it.unassignedUnits } }.forEach { trip ->
        require(trip.segments.size == 1 && trip.defaults.isEmpty() &&
            trip.segments.single().unitSlots.isEmpty()) { "invalid default operational trip" }
    }
    val authored = trips.filter { trip -> trip.segments.none { it.unassignedUnits } }
    val usedIds = authored.mapTo(hashSetOf()) { it.id }
    require(usedIds.size == authored.size) { "duplicate operational trip id" }
    return buildList {
        addAll(authored)
        services.forEach { service -> service.runSegments.forEach { run ->
            val base = "default-run/${service.id.length}:${service.id}/${run.runId.length}:${run.runId}"
            var id = base
            var suffix = 1
            while (!usedIds.add(id)) id = "$base#${suffix++}"
            add(SavedTimetableOperationalTripV1(id, segments = listOf(
                SavedTimetableOperationalTripSegmentV1(service.id, run.runId, emptyList(), unassignedUnits = true))))
        } }
    }
}

/** Activate the upcoming trip before its station display program, once actual unit order is established. */
private fun prepareDisplayBoundaries(
    service: SavedTimetableServiceV1,
    trips: List<SavedTimetableOperationalTripV1>,
    trace: MutableList<SavedCompilationTraceV1>
): SavedTimetableServiceV1 {
    val steps = service.steps.toMutableList()
    var segments = service.runSegments
    val structural = setOf(SavedTimetableActionTypeV1.TAKE_FROM_DEPOT, SavedTimetableActionTypeV1.TAKE_FROM_WAYPOINT,
        SavedTimetableActionTypeV1.TAKE_FROM_FORMATION_OUTPUT, SavedTimetableActionTypeV1.COUPLE,
        SavedTimetableActionTypeV1.SPLIT, SavedTimetableActionTypeV1.REVERSE)
    fun stationaryDisplay(actions: List<SavedStationProgramAction>): Boolean = actions.any { it.type in setOf(SavedStationProgramActionType.SET_DISPLAY, SavedStationProgramActionType.RESET_DISPLAY) || stationaryDisplay(it.steps) }
    fun changesDisplay(step: SavedTimetableStepV1): Boolean = step.action?.let { action ->
        action.type in setOf(SavedTimetableActionTypeV1.SET_DISPLAY, SavedTimetableActionTypeV1.RESET_DISPLAY) ||
            action.thenSteps.any(::changesDisplay) || action.elseSteps.any(::changesDisplay) ||
            action.stopPrograms?.let { stationaryDisplay(it.arrival) || stationaryDisplay(it.departurePreparation) } == true
    } == true
    for (segment in service.runSegments.sortedByDescending { it.startStepIndex }) {
        val gapStart = service.runSegments.filter { it.endStepIndexExclusive <= segment.startStepIndex }
            .maxOfOrNull { it.endStepIndexExclusive } ?: 0
        val afterStructure = (gapStart until segment.startStepIndex)
            .lastOrNull { steps[it].action?.type in structural }?.plus(1) ?: gapStart
        val insertion = (afterStructure until segment.startStepIndex).firstOrNull { changesDisplay(steps[it]) } ?: continue
        val declarations = trips.flatMap { trip -> trip.segments.filter { it.serviceId == service.id && it.runId == segment.runId }
            .map { trip.id to it.unitSlots } }.filter { (tripId, slots) ->
            (afterStructure until insertion).none { index -> steps[index].action?.let {
                it.type == SavedTimetableActionTypeV1.BEGIN_OPERATIONAL_TRIP && it.operationalTripId == tripId &&
                    it.runId == segment.runId && it.unitSlots.toSet() == slots.toSet()
            } == true }
        }
        if (declarations.isEmpty()) continue
        val tick = requireNotNull(steps[insertion].tick)
        val origin = trace.firstOrNull { it.serviceId == service.id && it.stepIndex == insertion }?.origin
        steps.addAll(insertion, declarations.map { (tripId, slots) -> SavedTimetableStepV1(SavedTimetableStepTypeV1.AT,
            tick = tick, action = SavedTimetableActionV1(SavedTimetableActionTypeV1.BEGIN_OPERATIONAL_TRIP,
                operationalTripId = tripId, runId = segment.runId, unitSlots = slots)) })
        val count = declarations.size
        segments = segments.map { scope -> when {
            scope.startStepIndex >= insertion -> scope.copy(startStepIndex = scope.startStepIndex + count,
                endStepIndexExclusive = scope.endStepIndexExclusive + count)
            scope.endStepIndexExclusive > insertion -> scope.copy(endStepIndexExclusive = scope.endStepIndexExclusive + count)
            else -> scope
        } }
        for (index in trace.indices) {
            val source = trace[index]
            if (source.serviceId == service.id && source.stepIndex >= insertion) trace[index] = source.copy(stepIndex = source.stepIndex + count)
        }
        if (origin != null) declarations.forEachIndexed { offset, (tripId, _) ->
            trace += SavedCompilationTraceV1(service.id, insertion + offset, "$origin/operational-trip:$tripId")
        }
    }
    return service.copy(steps = steps.toList(), runSegments = segments)
}

internal data class BuilderStepOrigin(val stepIndex: Int, val origin: String, val branchPath: List<SavedTimetableBranchPathEntryV1> = emptyList())

class ServiceBuilder(private val branchDepth: Int = 0, initialTick: Long = 0L, initialOrigin: String? = null) {
    private val steps = mutableListOf<SavedTimetableStepV1>()
    private val trace = mutableListOf<BuilderStepOrigin>()
    private val runSegments = mutableListOf<SavedTimetableRunSegmentV1>()
    private var insideRunSegment = false
    private var insideTask = false
    private val taskIds = mutableSetOf<String>()
    private val pendingPasses = mutableListOf<String>()
    private var currentTick = initialTick
    private var lastEmittedTick = initialTick
    private var currentOrigin: String? = initialOrigin

    @HostAccess.Export fun task(taskId: String, tick: Long, callback: Value): ServiceBuilder {
        require(branchDepth == 0 && !insideRunSegment && !insideTask) { "tasks cannot be nested or conditional" }
        require(taskId.isNotBlank() && taskId.length <= 128 && taskIds.add(taskId)) { "invalid or duplicate task id" }
        require(callback.canExecute()) { "task builder must be a function" }
        add(tick, SavedTimetableActionV1(SavedTimetableActionTypeV1.TASK_START, taskId = taskId))
        insideTask = true
        try { callback.executeVoid(this); requireNoPendingRoute() } finally { insideTask = false }
        add(currentTick, SavedTimetableActionV1(SavedTimetableActionTypeV1.TASK_END, taskId = taskId))
        return this
    }

    @HostAccess.Export fun runSegment(options: Value, callback: Value): ServiceBuilder {
        require(branchDepth == 0 && !insideRunSegment) { "run segments cannot be nested or conditional" }
        requireNoPendingRoute()
        options.requireOnly(setOf("runId", "lineId", "isInService", "diagramLineId", "lineDirection", "calls", "metadata"), "run segment")
        require(options.member("isInService")?.isBoolean == true) { "run segment isInService must be a boolean" }
        val raw = toJson(options, 24) as? JsonObject ?: error("run segment must be an object")
        val start = steps.size
        require(callback.canExecute()) { "run segment builder must be a function" }
        insideRunSegment = true
        try { callback.executeVoid(this); requireNoPendingRoute() } finally { insideRunSegment = false }
        require(steps.size > start) { "run segment must contain steps" }
        val segment = Json.decodeFromJsonElement(SavedTimetableRunSegmentV1.serializer(), JsonObject(raw + mapOf(
            "startStepIndex" to JsonPrimitive(start), "endStepIndexExclusive" to JsonPrimitive(steps.size))))
        require(segment.runId.isNotBlank() && segment.lineId.isNotBlank()) { "run segment IDs must not be blank" }
        require(runSegments.none { it.runId == segment.runId }) { "duplicate run segment id" }
        runSegments += segment
        return this
    }

    @HostAccess.Export fun setDisplay(unitSlots: Value, values: Value): ServiceBuilder = setDisplay(unitSlots, values, Value.asValue(null))
    @HostAccess.Export fun setDisplay(unitSlots: Value, values: Value, removeKeys: Value): ServiceBuilder =
        displayAt(currentTick, unitSlots, values, removeKeys)
    @HostAccess.Export fun resetDisplay(unitSlots: Value): ServiceBuilder = resetDisplayAt(currentTick, unitSlots)
    @HostAccess.Export fun beginOperationalTrip(tripId: String, runId: String, unitSlots: Value): ServiceBuilder =
        beginOperationalTripAt(currentTick, tripId, runId, unitSlots)
    internal fun beginOperationalTripAt(tick: Long, tripId: String, runId: String, unitSlots: Value): ServiceBuilder {
        require(branchDepth == 0 && !insideRunSegment) { "operational trip boundaries must be outside run scopes and conditions" }
        require(tripId.isNotBlank() && tripId == tripId.trim() && runId.isNotBlank() && runId == runId.trim()) { "invalid trip boundary IDs" }
        return add(tick, SavedTimetableActionV1(SavedTimetableActionTypeV1.BEGIN_OPERATIONAL_TRIP,
            operationalTripId = tripId, runId = runId, unitSlots = parseUnitSlots(unitSlots)))
    }
    internal fun displayAt(tick: Long, slots: Value, values: Value, removeKeys: Value): ServiceBuilder {
        val ids = if (slots.hasArrayElements() && slots.arraySize == 0L) emptyList() else parseUnitSlots(slots)
        val json = toJson(values, 24) as? JsonObject ?: error("display values must be a JSON object")
        validateDisplayValues(json)
        val keys = if (removeKeys.isNull) emptyList() else {
            val raw = toJson(removeKeys, 2) as? JsonArray ?: error("display removal keys must be an array")
            raw.map { key -> (key as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("display removal keys must be strings") }
        }
        require(keys.distinct().size == keys.size && keys.size <= 256 && keys.all { it.isNotBlank() && it.length <= 256 }) { "invalid display removal keys" }
        return add(tick, SavedTimetableActionV1(SavedTimetableActionTypeV1.SET_DISPLAY, unitSlots = ids, displayValues = json, removeDisplayKeys = keys))
    }
    internal fun resetDisplayAt(tick: Long, slots: Value): ServiceBuilder = add(tick,
        SavedTimetableActionV1(SavedTimetableActionTypeV1.RESET_DISPLAY, unitSlots = if (slots.hasArrayElements() && slots.arraySize == 0L) emptyList() else parseUnitSlots(slots)))
    internal fun buildRunSegments(): List<SavedTimetableRunSegmentV1> = runSegments.toList()

    @HostAccess.Export fun origin(origin: String, callback: Value): ServiceBuilder {
        requireNoPendingRoute()
        require(origin.isNotBlank()) { "origin must not be blank" }
        require(callback.canExecute()) { "origin builder must be a function" }
        val previous = currentOrigin
        currentOrigin = origin
        try {
            callback.executeVoid(this)
        } finally {
            currentOrigin = previous
        }
        return this
    }

    @HostAccess.Export fun check(wayPointId: String): ServiceBuilder {
        require(branchDepth == 0) { "CHECK is not allowed inside a runtime branch" }
        requireNoPendingRoute()
        require(wayPointId.isNotBlank())
        append(SavedTimetableStepV1(SavedTimetableStepTypeV1.CHECK, wayPointId = wayPointId))
        return this
    }

    @HostAccess.Export fun waitUntil(tick: Long): ServiceBuilder {
        require(tick >= lastEmittedTick && tick <= 9_007_199_254_740_991L) { "waitUntil tick must be a nondecreasing nonnegative safe integer" }
        return add(tick, SavedTimetableActionV1(SavedTimetableActionTypeV1.WAIT_UNTIL))
    }

    @HostAccess.Export fun program(programId: String, definition: Value, arguments: Value): ServiceBuilder = programAt(currentTick, programId, definition, arguments)
    @HostAccess.Export fun program(programId: String, definition: Value, arguments: Value, plannedContext: Value): ServiceBuilder = programAt(currentTick, programId, definition, arguments, plannedContext)

    internal fun programAt(tick: Long, programId: String, definition: Value, arguments: Value, plannedContext: Value = Value.asValue(null)): ServiceBuilder = try {
        appendProgram(tick, programId, definition, arguments, plannedContext)
    } catch (failure: RuntimeException) {
        throw IllegalArgumentException(listOfNotNull(currentOrigin, "program:$programId").joinToString("/") + (if (failure.message?.startsWith("step:") == true) "/" else ": ") + failure.message, failure)
    }

    private fun appendProgram(tick: Long, programId: String, definition: Value, arguments: Value, plannedContext: Value): ServiceBuilder {
        requireNoPendingRoute()
        require(programId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))) { "invalid program id" }
        require(branchDepth > 0 || steps.any { it.type == SavedTimetableStepTypeV1.CHECK || it.action?.type in setOf(SavedTimetableActionTypeV1.TAKE_FROM_DEPOT, SavedTimetableActionTypeV1.TAKE_FROM_WAYPOINT, SavedTimetableActionTypeV1.TAKE_FROM_FORMATION_OUTPUT) }) {
            "station programs require a prior check or service-boundary acquisition"
        }
        val expanded = expandStationProgram(toJson(definition, 64), toJson(arguments, 24), tick, (toJson(plannedContext, 8) as? JsonObject).orEmpty())
        val previous = currentOrigin
        val offset = steps.size
        try {
            currentOrigin = null
            expanded.steps.forEach { step -> add(requireNotNull(step.tick), requireNotNull(step.action)) }
            expanded.origins.forEach { origin ->
                trace += BuilderStepOrigin(offset + origin.stepIndex,
                    listOfNotNull(previous, "program:$programId", "step:${origin.programStepId}").joinToString("/"),
                    origin.branchPath)
            }
        } finally { currentOrigin = previous }
        return this
    }

    @HostAccess.Export fun at(tick: Long): TimedActionBuilder {
        requireNoPendingRoute()
        require(tick >= lastEmittedTick && tick <= 9_007_199_254_740_991L) { "at tick must be a nondecreasing nonnegative safe integer" }
        currentTick = tick
        return TimedActionBuilder(this, tick)
    }

    @HostAccess.Export fun pass(target: Value): ServiceBuilder = passAt(currentTick, target)
    @HostAccess.Export fun destination(target: String): ServiceBuilder = destinationAt(currentTick, target, Value.asValue(null))
    @HostAccess.Export fun destination(target: String, options: Value): ServiceBuilder = destinationAt(currentTick, target, options)
    @HostAccess.Export fun beginStationStop(target: String, options: Value): ServiceBuilder = beginStationStopAt(currentTick, target, options)
    internal fun beginStationStopAt(tick: Long, target: String, options: Value): ServiceBuilder {
        options.requireOnly(setOf("stationId", "callId", "callRole", "departureTick", "stationProgram", "plannedContext", "doorSide", "minimumDwellTicks"), "origin station options")
        val programs = requireNotNull(compileStationStopPrograms(target, toJson(options, 64).jsonObject)) { "beginStationStop requires stationProgram" }
        require(programs.role == "ORIGIN") { "beginStationStop requires ORIGIN role" }
        return add(tick, SavedTimetableActionV1(SavedTimetableActionTypeV1.BEGIN_STATION_STOP, stopPrograms = programs))
    }

    @HostAccess.Export fun wait(ticks: Value): ServiceBuilder = addWait(currentTick, ticks)
    @HostAccess.Export fun reverse(): ServiceBuilder = add(currentTick, SavedTimetableActionV1(SavedTimetableActionTypeV1.REVERSE))
    @HostAccess.Export fun halt(): ServiceBuilder = add(currentTick, SavedTimetableActionV1(SavedTimetableActionTypeV1.HALT))
    @HostAccess.Export fun couple(eventId: Value): ServiceBuilder = add(currentTick,
        SavedTimetableActionV1(SavedTimetableActionTypeV1.COUPLE, eventId = fixedFormationId(eventId)))
    @HostAccess.Export fun split(eventId: Value): ServiceBuilder = add(currentTick,
        SavedTimetableActionV1(SavedTimetableActionTypeV1.SPLIT, eventId = fixedFormationId(eventId)))

    @HostAccess.Export fun selectStop(stationId: String, berthIds: Value, policy: String): ServiceBuilder =
        selectStopAt(currentTick, stationId, berthIds, policy, Value.asValue(null))
    @HostAccess.Export fun selectStop(stationId: String, berthIds: Value, policy: String, options: Value): ServiceBuilder =
        selectStopAt(currentTick, stationId, berthIds, policy, options)

    internal fun selectStopAt(tick: Long, stationId: String, berthIds: Value, policy: String, options: Value): ServiceBuilder {
        require(stationId.isNotBlank() && stationId == stationId.trim()) { "stationId must not be blank" }
        require(berthIds.hasArrayElements()) { "selectStop requires an ordered berth ID array" }
        val ids = strings(berthIds)
        require(ids.size in 1..64 && ids.distinct().size == ids.size) { "selectStop requires 1..64 distinct berth IDs" }
        val selectedPolicy = SavedStationStopSelectionPolicyV1.valueOf(policy.uppercase())
        options.requireOnly(setOf("minimumDwellTicks", "stoppingOffsetMetersBefore", "doorSide"), "selectStop options")
        val dwell = options.long("minimumDwellTicks")
        val offset = options.double("stoppingOffsetMetersBefore")
        require(dwell == null || dwell >= 0)
        require(offset == null || offset.isFinite() && offset >= 0.0)
        val door = options.string("doorSide")?.uppercase()?.also { require(it in setOf("NONE", "LEFT", "RIGHT", "BOTH")) }
        return add(tick, SavedTimetableActionV1(SavedTimetableActionTypeV1.SELECT_STOP, stationId = stationId,
            berthCandidates = ids.mapIndexed { index, id -> SavedStationStopCandidateV1(id, index) }, selectionPolicy = selectedPolicy,
            minimumDwellTicks = dwell, stoppingOffsetMetersBefore = offset, doorSideOverride = door))
    }

    internal fun passAt(tick: Long, target: Value): ServiceBuilder {
        require(tick >= lastEmittedTick && (pendingPasses.isEmpty() || tick == currentTick))
        currentTick = tick
        pendingPasses += strings(target)
        return this
    }

    internal fun destinationAt(tick: Long, target: String, options: Value): ServiceBuilder {
        require(tick >= lastEmittedTick && (pendingPasses.isEmpty() || tick == currentTick))
        currentTick = tick
        require(target.isNotBlank()) { "destination target must not be blank" }
        options.requireOnly(setOf("arrivalTick", "minimumDwellTicks", "stoppingOffsetMetersBefore", "doorSide", "stationId", "callId", "callRole", "departureTick", "stationProgram", "plannedContext"), "destination options")
        val arrivalTick = options.long("arrivalTick")
        val dwell = options.long("minimumDwellTicks")
        val offset = options.double("stoppingOffsetMetersBefore")
        require(arrivalTick == null || arrivalTick >= tick) {
            "arrivalTick must not be earlier than departure tick"
        }
        require(dwell == null || dwell >= 0L) { "minimumDwellTicks must not be negative" }
        require(offset == null || offset >= 0.0) { "stoppingOffsetMetersBefore must not be negative" }
        val doorSide = options.string("doorSide")?.uppercase()?.also {
            require(it in setOf("NONE", "LEFT", "RIGHT", "BOTH")) { "doorSide must be NONE, LEFT, RIGHT, or BOTH" }
        }
        val stopPrograms = compileStationStopPrograms(target, toJson(options, 64) as? JsonObject ?: JsonObject(emptyMap()))
        append(SavedTimetableStepV1(
            SavedTimetableStepTypeV1.AT,
            tick,
            action = SavedTimetableActionV1(
                SavedTimetableActionTypeV1.DESTINATION_OPTIONS,
                wayPointIds = listOf(target),
                stopPrograms = stopPrograms,
                arrivalTick = arrivalTick,
                minimumDwellTicks = dwell,
                stoppingOffsetMetersBefore = offset,
                doorSideOverride = doorSide
            )
        ))
        append(SavedTimetableStepV1(
            SavedTimetableStepTypeV1.AT,
            tick,
            action = SavedTimetableActionV1(
                SavedTimetableActionTypeV1.DESTINATION,
                wayPointIds = pendingPasses + target
            )
        ))
        pendingPasses.clear()
        return this
    }

    internal fun addWait(tick: Long, ticks: Value): ServiceBuilder {
        require(ticks.isNumber && ticks.fitsInLong()) { "wait ticks must be an integer" }
        val duration = ticks.asLong()
        require(duration > 0) { "wait ticks must be greater than zero" }
        return add(tick, SavedTimetableActionV1(SavedTimetableActionTypeV1.STOP, stopTicks = duration))
    }

    internal fun add(tick: Long, action: SavedTimetableActionV1): ServiceBuilder {
        requireNoPendingRoute()
        require(tick >= lastEmittedTick && tick <= 9_007_199_254_740_991L) { "action tick must be a nondecreasing nonnegative safe integer" }
        val end = validateBranchSchedule(action, tick, branchDepth)
        append(SavedTimetableStepV1(SavedTimetableStepTypeV1.AT, tick, action = action))
        currentTick = end
        lastEmittedTick = end
        return this
    }

    internal fun addConditional(tick: Long, condition: SavedTimetableConditionV1, thenBuilder: Value, elseBuilder: Value): ServiceBuilder {
        require(branchDepth < 16) { "runtime branches exceed depth limit 16" }
        require(thenBuilder.canExecute() && (elseBuilder.isNull || elseBuilder.canExecute())) { "branch builders must be functions" }
        val yes = ServiceBuilder(branchDepth + 1, tick, currentOrigin).also { thenBuilder.executeVoid(it) }
        val no = ServiceBuilder(branchDepth + 1, tick, currentOrigin).also { if (!elseBuilder.isNull) elseBuilder.executeVoid(it) }
        val index = steps.size
        add(tick, SavedTimetableActionV1(SavedTimetableActionTypeV1.CONDITIONAL, condition = condition,
            thenSteps = yes.buildSteps(), elseSteps = no.buildSteps()))
        listOf(SavedTimetableBranchArmV1.THEN to yes, SavedTimetableBranchArmV1.ELSE to no).forEach { (arm, child) ->
            child.buildTrace().forEach { origin ->
                trace += BuilderStepOrigin(index, origin.origin,
                    listOf(SavedTimetableBranchPathEntryV1(arm, origin.stepIndex)) + origin.branchPath)
            }
        }
        return this
    }

    @HostAccess.Export fun action(typeId: String, parameters: Value): ServiceBuilder =
        add(currentTick, SavedTimetableActionV1(SavedTimetableActionTypeV1.EXTENSION, extensionCall = extension(typeId, 1, parameters)))
    @HostAccess.Export fun whenCondition(condition: SavedTimetableConditionV1, yes: Value): ServiceBuilder =
        addConditional(currentTick, condition, yes, Value.asValue(null))
    @HostAccess.Export fun whenCondition(condition: SavedTimetableConditionV1, yes: Value, no: Value): ServiceBuilder =
        addConditional(currentTick, condition, yes, no)

    internal fun buildSteps(): List<SavedTimetableStepV1> {
        requireNoPendingRoute()
        return steps.toList()
    }

    internal fun buildTrace(): List<BuilderStepOrigin> = trace.toList()

    private fun append(step: SavedTimetableStepV1) {
        step.tick?.let {
            require(it >= lastEmittedTick) { "emitted action ticks must not decrease" }
            lastEmittedTick = it
        }
        val index = steps.size
        steps += step
        currentOrigin?.let { trace += BuilderStepOrigin(index, it) }
    }

    private fun requireNoPendingRoute() {
        require(pendingPasses.isEmpty()) { "pass(...) must be completed by destination(...) before another action" }
    }
}

class TimedActionBuilder(private val service: ServiceBuilder, private val tick: Long) {
    @HostAccess.Export fun beginStationStop(target: String, options: Value): ServiceBuilder = service.beginStationStopAt(tick, target, options)
    @HostAccess.Export fun beginOperationalTrip(tripId: String, runId: String, unitSlots: Value): ServiceBuilder =
        service.beginOperationalTripAt(tick, tripId, runId, unitSlots)
    @HostAccess.Export fun setDisplay(unitSlots: Value, values: Value): ServiceBuilder = service.displayAt(tick, unitSlots, values, Value.asValue(null))
    @HostAccess.Export fun setDisplay(unitSlots: Value, values: Value, removeKeys: Value): ServiceBuilder = service.displayAt(tick, unitSlots, values, removeKeys)
    @HostAccess.Export fun resetDisplay(unitSlots: Value): ServiceBuilder = service.resetDisplayAt(tick, unitSlots)
    @HostAccess.Export fun selectStop(stationId: String, berthIds: Value, policy: String): ServiceBuilder =
        service.selectStopAt(tick, stationId, berthIds, policy, Value.asValue(null))
    @HostAccess.Export fun selectStop(stationId: String, berthIds: Value, policy: String, options: Value): ServiceBuilder =
        service.selectStopAt(tick, stationId, berthIds, policy, options)

    @HostAccess.Export fun program(programId: String, definition: Value, arguments: Value): ServiceBuilder = service.programAt(tick, programId, definition, arguments)
    @HostAccess.Export fun program(programId: String, definition: Value, arguments: Value, plannedContext: Value): ServiceBuilder = service.programAt(tick, programId, definition, arguments, plannedContext)
    @HostAccess.Export fun pass(target: Value): ServiceBuilder = service.passAt(tick, target)
    @HostAccess.Export fun destination(target: String): ServiceBuilder = service.destinationAt(tick, target, Value.asValue(null))
    @HostAccess.Export fun destination(target: String, options: Value): ServiceBuilder = service.destinationAt(tick, target, options)
    @HostAccess.Export fun wait(ticks: Value): ServiceBuilder = service.addWait(tick, ticks)
    @HostAccess.Export fun reverse(): ServiceBuilder = service.add(tick, SavedTimetableActionV1(SavedTimetableActionTypeV1.REVERSE))
    @HostAccess.Export fun halt(): ServiceBuilder = service.add(tick, SavedTimetableActionV1(SavedTimetableActionTypeV1.HALT))
    @HostAccess.Export fun couple(eventId: Value): ServiceBuilder = service.add(tick,
        SavedTimetableActionV1(SavedTimetableActionTypeV1.COUPLE, eventId = fixedFormationId(eventId)))
    @HostAccess.Export fun split(eventId: Value): ServiceBuilder = service.add(tick,
        SavedTimetableActionV1(SavedTimetableActionTypeV1.SPLIT, eventId = fixedFormationId(eventId)))
    @HostAccess.Export fun storeAtDepot(depotId: String): ServiceBuilder = storeAtDepot(depotId, Value.asValue(null))
    @HostAccess.Export fun storeAtDepot(depotId: String, options: Value): ServiceBuilder {
        require(depotId.isNotBlank()) { "depotId must not be blank" }
        options.requireOnly(setOf("stablingPoints"), "storeAtDepot options")
        return service.add(tick, SavedTimetableActionV1(SavedTimetableActionTypeV1.STORE_AT_DEPOT, depotId = depotId, stablingPointIds = options.strings("stablingPoints")))
    }
    @HostAccess.Export fun action(typeId: String, parameters: Value): ServiceBuilder = service.add(tick, SavedTimetableActionV1(SavedTimetableActionTypeV1.EXTENSION, extensionCall = extension(typeId, 1, parameters)))
    @HostAccess.Export fun action(typeId: String, version: Int, parameters: Value): ServiceBuilder = service.add(tick, SavedTimetableActionV1(SavedTimetableActionTypeV1.EXTENSION, extensionCall = extension(typeId, version, parameters)))
    @HostAccess.Export fun whenCondition(condition: SavedTimetableConditionV1, thenBuilder: Value): ServiceBuilder = whenCondition(condition, thenBuilder, Value.asValue(null))
    @HostAccess.Export fun whenCondition(condition: SavedTimetableConditionV1, thenBuilder: Value, elseBuilder: Value): ServiceBuilder =
        service.addConditional(tick, condition, thenBuilder, elseBuilder)
}

/** Validate post-expansion fixed obligations and authored earliest ticks before emitting a branch. */
private fun validateBranchSchedule(action: SavedTimetableActionV1, tick: Long, depth: Int): Long {
    require(depth <= 16) { "runtime branches exceed depth limit 16" }
    if (depth > 0) require(action.type !in setOf(SavedTimetableActionTypeV1.TAKE_FROM_DEPOT,
        SavedTimetableActionTypeV1.TAKE_FROM_WAYPOINT, SavedTimetableActionTypeV1.TAKE_FROM_FORMATION_OUTPUT,
        SavedTimetableActionTypeV1.STORE_AT_DEPOT, SavedTimetableActionTypeV1.COUPLE,
        SavedTimetableActionTypeV1.SPLIT, SavedTimetableActionTypeV1.HALT)) {
        "${action.type} cannot be skipped by a runtime condition"
    }
    if (action.type != SavedTimetableActionTypeV1.CONDITIONAL) return tick
    fun end(steps: List<SavedTimetableStepV1>): Long {
        var current = tick
        for (step in steps) {
            require(step.type == SavedTimetableStepTypeV1.AT) { "Conditional children must be AT steps" }
            val next = requireNotNull(step.tick)
            require(next >= current && next <= 9_007_199_254_740_991L) { "branch ticks must be nondecreasing safe integers" }
            current = validateBranchSchedule(requireNotNull(step.action), next, depth + 1)
        }
        return current
    }
    return maxOf(end(action.thenSteps), end(action.elseSteps))
}

private fun objectSelector(value: Value): SavedObjectSelectorV1 = when {
    value.isString -> {
        val pattern = value.asString()
        require(pattern.isNotBlank()) { "selector regex must not be blank" }
        kotlin.text.Regex(pattern)
        SavedObjectSelectorV1(SavedObjectSelectorTypeV1.REGEX, pattern = pattern)
    }
    value.isHostObject -> value.asHostObject<TimetableObjectSelectorValue>().saved
    else -> error("selector must be a regex string or and/or/not selector")
}

private fun extension(typeId: String, version: Int, parameters: Value) = SavedTimetableExtensionCallV1(typeId, version, (toJson(parameters) as? JsonObject) ?: JsonObject(emptyMap()))
private fun activation(value: Value): SavedTimetableActivationV1 {
    value.requireOnly(setOf("type", "condition", "falseBehavior"), "activation")
    return when (value.string("type")?.uppercase()) {
        "MANUAL" -> SavedTimetableActivationV1(SavedTimetableActivationTypeV1.MANUAL)
        "CONDITIONAL" -> {
            val behavior = value.string("falseBehavior")?.uppercase() ?: "SKIP_CYCLE"
            require(behavior == "SKIP_CYCLE" || behavior == "WAIT_UNTIL_TRUE") {
                "conditional activation falseBehavior must be SKIP_CYCLE or WAIT_UNTIL_TRUE"
            }
            val condition = value.member("condition")
                ?: error("conditional activation requires condition")
            SavedTimetableActivationV1(
                SavedTimetableActivationTypeV1.CONDITIONAL,
                condition.`as`(SavedTimetableConditionV1::class.java),
                behavior
            )
        }
        "SCHEDULED", null -> SavedTimetableActivationV1()
        else -> error("activation type must be scheduled, manual, or conditional")
    }
}
private fun Value.requireOnly(allowed: Set<String>, subject: String) {
    if (isNull) return
    require(hasMembers()) { "$subject must be an object" }
    val unknown = memberKeys - allowed
    require(unknown.isEmpty()) { "$subject contains unknown fields: ${unknown.sorted().joinToString()}" }
}
private fun strings(value: Value): List<String> = if (value.isString) {
    listOf(value.asString()).also { require(it.single().isNotBlank()) { "value must not be blank" } }
} else {
    require(value.hasArrayElements()) { "value must be a string or string array" }
    (0 until value.arraySize).map { value.getArrayElement(it).asString() }
        .also { values ->
            require(values.isNotEmpty() && values.all(String::isNotBlank)) {
                "string array must be non-empty and contain no blank values"
            }
        }
}
private fun Value.member(name: String): Value? = if (!isNull && hasMembers() && hasMember(name)) getMember(name) else null
private fun Value.string(name: String) = member(name)?.takeUnless { it.isNull }?.asString()
private fun Value.long(name: String) = member(name)?.takeUnless { it.isNull }?.asLong()
private fun Value.int(name: String) = member(name)?.takeUnless { it.isNull }?.asInt()
private fun Value.double(name: String) = member(name)?.takeUnless { it.isNull }?.asDouble()
private fun Value.strings(name: String) = member(name)?.let(::strings)?.toSet() ?: emptySet()
private fun toJson(v: Value, remainingDepth: Int = Int.MAX_VALUE): JsonElement {
    require(remainingDepth >= 0) { "program JSON nesting exceeds the supported depth" }
    return when {
        v.isNull -> JsonNull
        v.isBoolean -> JsonPrimitive(v.asBoolean())
        v.isString -> JsonPrimitive(v.asString())
        v.fitsInLong() -> JsonPrimitive(v.asLong())
        v.isNumber -> JsonPrimitive(v.asDouble())
        v.hasArrayElements() -> JsonArray((0 until v.arraySize).map { toJson(v.getArrayElement(it), remainingDepth - 1) })
        v.hasMembers() -> JsonObject(v.memberKeys.associateWith { toJson(v.getMember(it), remainingDepth - 1) })
        else -> error("value is not JSON-compatible")
    }
}

private fun fixedFormationId(value: Value): String {
    require(value.isString) { "formation references must be fixed string IDs" }
    return value.asString().also { require(it.isNotBlank() && it == it.trim()) { "formation ID must be nonblank and trimmed" } }
}

internal fun requireUnitSlots(slots: List<Int>) {
    require(slots.isNotEmpty() && slots.size <= 128 && slots.distinct().size == slots.size && slots.all { it > 0 }) {
        "unitSlots must contain distinct positive integers (at most128)"
    }
}
private fun parseUnitSlots(value: Value): List<Int> {
    val raw = toJson(value, 2) as? JsonArray ?: error("unitSlots must be an array")
    return raw.map { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.intOrNull ?: error("unitSlots must be integers") }.also(::requireUnitSlots)
}
