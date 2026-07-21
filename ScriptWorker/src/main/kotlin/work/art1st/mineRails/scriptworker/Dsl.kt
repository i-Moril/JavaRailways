package work.art1st.mineRails.scriptworker

import kotlinx.serialization.json.*
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Value

class TimetableBuilder(private val id: String) {
    private var periodDays: Long? = null
    private val services = mutableListOf<SavedTimetableServiceV1>()

    @HostAccess.Export fun setPeriodDays(days: Long): TimetableBuilder { require(days > 0); Math.multiplyExact(days, 24_000); periodDays = days; return this }
    @HostAccess.Export fun setPeriod(ticks: Long): TimetableBuilder { require(ticks > 0 && ticks % 24_000 == 0L) { "period ticks must be a positive multiple of 24000" }; return setPeriodDays(ticks / 24_000) }
    @HostAccess.Export fun service(serviceId: String, callback: Value): TimetableBuilder = addService(serviceId, null, null, callback)
    @HostAccess.Export fun service(serviceId: String, selector: Value, callback: Value): TimetableBuilder = addService(serviceId, selector, null, callback)
    @HostAccess.Export fun service(serviceId: String, selector: Value, options: Value, callback: Value): TimetableBuilder = addService(serviceId, selector, options, callback)

    @HostAccess.Export fun predicate(typeId: String, parameters: Value): SavedTimetableConditionV1 = predicate(typeId, 1, parameters)
    @HostAccess.Export fun predicate(typeId: String, version: Int, parameters: Value): SavedTimetableConditionV1 = SavedTimetableConditionV1(SavedTimetableConditionTypeV1.PREDICATE, call = extension(typeId, version, parameters))
    @HostAccess.Export fun all(vararg conditions: SavedTimetableConditionV1) = SavedTimetableConditionV1(SavedTimetableConditionTypeV1.ALL, conditions = conditions.toList())
    @HostAccess.Export fun any(vararg conditions: SavedTimetableConditionV1) = SavedTimetableConditionV1(SavedTimetableConditionTypeV1.ANY, conditions = conditions.toList())
    @HostAccess.Export fun not(condition: SavedTimetableConditionV1) = SavedTimetableConditionV1(SavedTimetableConditionTypeV1.NOT, conditions = listOf(condition))

    private fun addService(serviceId: String, selector: Value?, options: Value?, callback: Value): TimetableBuilder {
        require(serviceId.isNotBlank()); require(callback.canExecute()) { "service builder must be a function" }
        val trainId = selector?.takeUnless { it.isNull }?.let { require(it.isString) { "selector must be a train id or null" }; it.asString() }
        val builder = ServiceBuilder()
        callback.executeVoid(builder)
        options?.requireOnly(setOf("activation", "lineId"), "service options")
        val activation = options?.member("activation")?.takeUnless { it.isNull }?.let { activation(it) } ?: SavedTimetableActivationV1()
        val lineId = options?.string("lineId")?.also { require(it.isNotBlank()) { "lineId must not be blank" } }
        services += SavedTimetableServiceV1(serviceId, if (trainId == null) SavedTrainSelectorTypeV1.ANY else SavedTrainSelectorTypeV1.TRAIN_ID, trainId, builder.steps, activation, lineId)
        return this
    }

    fun build(): SavedTimetableV1 {
        require(id.isNotBlank()) { "timetable id must not be blank" }
        require(services.map { it.id }.distinct().size == services.size) { "duplicate service id" }
        return SavedTimetableV1(id, requireNotNull(periodDays) { "setPeriodDays() is required" }, services.toList())
    }
}

class ServiceBuilder {
    internal val steps = mutableListOf<SavedTimetableStepV1>()
    @HostAccess.Export fun check(wayPointId: String): ServiceBuilder { require(wayPointId.isNotBlank()); steps += SavedTimetableStepV1(SavedTimetableStepTypeV1.CHECK, wayPointId = wayPointId); return this }
    @HostAccess.Export fun at(tick: Long): TimedActionBuilder { require(tick >= 0); return TimedActionBuilder(this, tick) }
    internal fun add(tick: Long, action: SavedTimetableActionV1): ServiceBuilder { steps += SavedTimetableStepV1(SavedTimetableStepTypeV1.AT, tick, action = action); return this }
}

class TimedActionBuilder(private val service: ServiceBuilder, private val tick: Long) {
    @HostAccess.Export fun goto(target: Value): ServiceBuilder = service.add(tick, SavedTimetableActionV1(SavedTimetableActionTypeV1.DESTINATION, wayPointIds = strings(target)))
    @HostAccess.Export fun stop(ticks: Long): ServiceBuilder { require(ticks > 0); return service.add(tick, SavedTimetableActionV1(SavedTimetableActionTypeV1.STOP, stopTicks = ticks)) }
    @HostAccess.Export fun reverse(): ServiceBuilder = service.add(tick, SavedTimetableActionV1(SavedTimetableActionTypeV1.REVERSE))
    @HostAccess.Export fun halt(): ServiceBuilder = service.add(tick, SavedTimetableActionV1(SavedTimetableActionTypeV1.HALT))
    @HostAccess.Export fun depot(depotId: String): ServiceBuilder = takeFromDepot(depotId)
    @HostAccess.Export fun depot(depotId: String, options: Value): ServiceBuilder = takeFromDepot(depotId, options)
    @HostAccess.Export fun takeFromDepot(depotId: String): ServiceBuilder = takeFromDepot(depotId, Value.asValue(null))
    @HostAccess.Export fun takeFromDepot(depotId: String, options: Value): ServiceBuilder {
        require(depotId.isNotBlank()) { "depotId must not be blank" }
        options.requireOnly(setOf("tags", "minimumCars", "maximumCars", "minimumSpeedMetersPerSecond", "capabilities"), "takeFromDepot options")
        return service.add(tick, SavedTimetableActionV1(SavedTimetableActionTypeV1.TAKE_FROM_DEPOT, depotId = depotId, requiredTags = options.strings("tags"), minimumCars = options.int("minimumCars"), maximumCars = options.int("maximumCars"), minimumSpeedMetersPerSecond = options.double("minimumSpeedMetersPerSecond"), requiredCapabilities = options.strings("capabilities")))
    }
    @HostAccess.Export fun storeAtDepot(depotId: String): ServiceBuilder = storeAtDepot(depotId, Value.asValue(null))
    @HostAccess.Export fun storeAtDepot(depotId: String, options: Value): ServiceBuilder {
        require(depotId.isNotBlank()) { "depotId must not be blank" }
        options.requireOnly(setOf("stablingPoints"), "storeAtDepot options")
        return service.add(tick, SavedTimetableActionV1(SavedTimetableActionTypeV1.STORE_AT_DEPOT, depotId = depotId, stablingPointIds = options.strings("stablingPoints")))
    }
    @HostAccess.Export fun call(stationId: String, berthId: String): ServiceBuilder = call(stationId, berthId, Value.asValue(null))
    @HostAccess.Export fun call(stationId: String, berthId: String, options: Value): ServiceBuilder {
        require(stationId.isNotBlank() && berthId.isNotBlank()) { "stationId and berthId must not be blank" }
        options.requireOnly(setOf("minimumDwellTicks", "stoppingOffsetMetersBefore", "doorSide"), "call options")
        val doorSide = options.string("doorSide")?.uppercase()?.also {
            require(it in setOf("NONE", "LEFT", "RIGHT", "BOTH")) { "doorSide must be NONE, LEFT, RIGHT, or BOTH" }
        }
        return service.add(tick, SavedTimetableActionV1(SavedTimetableActionTypeV1.STATION_DESTINATION_OPTIONS, stationId = stationId, berthId = berthId, minimumDwellTicks = options.long("minimumDwellTicks"), stoppingOffsetMetersBefore = options.double("stoppingOffsetMetersBefore"), doorSideOverride = doorSide))
    }
    @HostAccess.Export fun action(typeId: String, parameters: Value): ServiceBuilder = service.add(tick, SavedTimetableActionV1(SavedTimetableActionTypeV1.EXTENSION, extensionCall = extension(typeId, 1, parameters)))
    @HostAccess.Export fun action(typeId: String, version: Int, parameters: Value): ServiceBuilder = service.add(tick, SavedTimetableActionV1(SavedTimetableActionTypeV1.EXTENSION, extensionCall = extension(typeId, version, parameters)))
    @HostAccess.Export fun whenCondition(condition: SavedTimetableConditionV1, thenBuilder: Value): ServiceBuilder = whenCondition(condition, thenBuilder, Value.asValue(null))
    @HostAccess.Export fun whenCondition(condition: SavedTimetableConditionV1, thenBuilder: Value, elseBuilder: Value): ServiceBuilder {
        val yes = RuntimeActions().also { thenBuilder.executeVoid(it) }.actions
        val no = if (elseBuilder.isNull) emptyList() else RuntimeActions().also { elseBuilder.executeVoid(it) }.actions
        return service.add(tick, SavedTimetableActionV1(SavedTimetableActionTypeV1.CONDITIONAL, condition = condition, thenActions = yes, elseActions = no))
    }
}

class RuntimeActions { internal val actions = mutableListOf<SavedTimetableActionV1>(); @HostAccess.Export fun action(typeId: String, parameters: Value): RuntimeActions { actions += SavedTimetableActionV1(SavedTimetableActionTypeV1.EXTENSION, extensionCall = extension(typeId, 1, parameters)); return this } }

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
private fun toJson(v: Value): JsonElement = when { v.isNull -> JsonNull; v.isBoolean -> JsonPrimitive(v.asBoolean()); v.isString -> JsonPrimitive(v.asString()); v.fitsInLong() -> JsonPrimitive(v.asLong()); v.isNumber -> JsonPrimitive(v.asDouble()); v.hasArrayElements() -> JsonArray((0 until v.arraySize).map { toJson(v.getArrayElement(it)) }); v.hasMembers() -> JsonObject(v.memberKeys.associateWith { toJson(v.getMember(it)) }); else -> error("value is not JSON-compatible") }
