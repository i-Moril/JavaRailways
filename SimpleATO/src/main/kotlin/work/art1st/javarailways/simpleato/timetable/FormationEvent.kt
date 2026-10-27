package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.simpleato.api.timetable.TimetableServiceActivation

private fun formationId(value: String) {
    require(value.isNotBlank() && value == value.trim()) { "formation identity must be nonblank and trimmed" }
}

/** Immutable fixed declarations; no physical train/car/grant IDs or runtime selectors are authored. */
class TimetableFormationCatalog private constructor(private val entries: List<TimetableFormationEvent>) :
    List<TimetableFormationEvent> by entries {
    constructor(events: Collection<TimetableFormationEvent>) : this(java.util.List.copyOf(events))
    override fun equals(other: Any?): Boolean = entries == other
    override fun hashCode(): Int = entries.hashCode()
    override fun toString(): String = entries.toString()
}

enum class FormationStationaryEnd { HEAD, TAIL }

data class FormationRendezvous(val approachDestinationId: String, val stationaryDestinationId: String) {
    init { formationId(approachDestinationId); formationId(stationaryDestinationId) }
}

sealed interface FormationOutputDisposition {
    data class Duty(val dutyId: String) : FormationOutputDisposition { init { formationId(dutyId) } }
    data object Hold : FormationOutputDisposition
}

data class TimetableFormationOutput(val id: String, val disposition: FormationOutputDisposition) {
    init { formationId(id) }
}

sealed interface TimetableFormationEvent {
    val id: String
    val coordinatorDutyId: String
    val outputs: List<TimetableFormationOutput>
    val participantDutyIds: Set<String>

    data class Couple(
        override val id: String,
        override val coordinatorDutyId: String,
        val approachingDutyId: String,
        val stationaryDutyId: String,
        val stationaryEnd: FormationStationaryEnd,
        val rendezvous: FormationRendezvous,
        val maximumApproachSpeedMetersPerSecond: Double,
        val output: TimetableFormationOutput
    ) : TimetableFormationEvent {
        init {
            listOf(id, coordinatorDutyId, approachingDutyId, stationaryDutyId).forEach(::formationId)
            require(approachingDutyId != stationaryDutyId) { "formation event cannot couple a duty to itself" }
            require(coordinatorDutyId in setOf(approachingDutyId, stationaryDutyId)) { "coordinator must be a coupling participant" }
            require(maximumApproachSpeedMetersPerSecond.isFinite() && maximumApproachSpeedMetersPerSecond > 0.0)
        }
        override val outputs: List<TimetableFormationOutput> get() = listOf(output)
        override val participantDutyIds: Set<String> get() = setOf(approachingDutyId, stationaryDutyId)
    }

    /** FIRST contains firstUnitCount complete operational units from the actual operating HEAD. */
    data class Split(
        override val id: String,
        override val coordinatorDutyId: String,
        val firstUnitCount: Int,
        val first: TimetableFormationOutput,
        val rest: TimetableFormationOutput
    ) : TimetableFormationEvent {
        init {
            formationId(id); formationId(coordinatorDutyId)
            require(firstUnitCount > 0) { "HEAD partition firstUnitCount must be positive" }
            require(first.id != rest.id) { "split output identities must be distinct" }
            val duties = listOfNotNull((first.disposition as? FormationOutputDisposition.Duty)?.dutyId,
                (rest.disposition as? FormationOutputDisposition.Duty)?.dutyId)
            require(duties.distinct().size == duties.size) { "split outputs cannot share a duty" }
        }
        override val outputs: List<TimetableFormationOutput> get() = listOf(first, rest)
        override val participantDutyIds: Set<String> get() = setOf(coordinatorDutyId)
    }
}

/** Pure reciprocal ownership validation. Actual unit counts/partition geometry remain runtime checks. */
object FormationEventValidator {
    fun validate(timetable: Timetable): List<String> {
        val errors = arrayListOf<String>()
        val services = timetable.services.groupBy { it.id }
        val events = timetable.formationEvents.groupBy { it.id }
        events.filterValues { it.size != 1 }.keys.forEach { errors += "formation event $it is declared more than once" }
        fun service(id: String): TimetableService? = services[id]?.singleOrNull()
        fun event(id: String): TimetableFormationEvent? = events[id]?.singleOrNull()
        fun scheduled(id: String, context: String) {
            val duty = service(id)
            if (duty == null) errors += "$context references missing or duplicate duty $id"
            else if (duty.activation != TimetableServiceActivation.Scheduled)
                errors += "$context duty $id must be statically scheduled"
        }
        val acquisitions = mutableMapOf<Pair<String, String>, MutableList<String>>()
        val invocations = mutableMapOf<String, MutableList<Pair<String, TimetableAction>>>()
        val dependencies = mutableMapOf<String, Set<String>>()
        val eventDependencies = linkedMapOf<String, MutableSet<String>>()
        timetable.services.forEach { duty ->
            var previousEventId: String? = null
            duty.steps.forEachIndexed { index, step ->
                val action = (step as? TimetableStep.At)?.action ?: return@forEachIndexed
                when (action) {
                    is TimetableAction.TakeFromFormationOutput -> {
                        val context = "[${duty.id}] step#$index formation acquisition"
                        if (index != 0 || duty.trainSelector != TrainSelector.AnyTrain)
                            errors += "$context must be the first step with AnyTrain"
                        scheduled(duty.id, context)
                        acquisitions.getOrPut(action.eventId to action.outputId) { arrayListOf() } += duty.id
                        previousEventId = action.eventId
                        val declaration = event(action.eventId)
                        val output = declaration?.outputs?.singleOrNull { it.id == action.outputId }
                        if (output?.disposition != FormationOutputDisposition.Duty(duty.id))
                            errors += "$context must reciprocally own ${action.eventId}/${action.outputId}"
                        if (declaration != null) {
                            if (duty.id in declaration.participantDutyIds)
                                errors += "$context cannot reacquire its own participating output"
                            dependencies[duty.id] = declaration.participantDutyIds
                        }
                    }
                    is TimetableAction.Couple, is TimetableAction.Split -> {
                        val id = if (action is TimetableAction.Couple) action.eventId else (action as TimetableAction.Split).eventId
                        previousEventId?.let { before -> eventDependencies.getOrPut(before) { linkedSetOf() } += id }
                        previousEventId = id
                        invocations.getOrPut(id) { arrayListOf() } += duty.id to action
                        val declaration = event(id)
                        if (declaration == null || duty.id !in declaration.participantDutyIds)
                            errors += "[${duty.id}] step#$index is not a participant of formation event $id"
                        else {
                            if ((declaration is TimetableFormationEvent.Couple) != (action is TimetableAction.Couple))
                                errors += "[${duty.id}] step#$index formation action kind does not match $id"
                            val continues = declaration.outputs.any { it.disposition == FormationOutputDisposition.Duty(duty.id) }
                            if (!continues && duty.steps.drop(index + 1).any { (it as? TimetableStep.At)?.action !is TimetableAction.TaskEnd })
                                errors += "[${duty.id}] consumed formation participant must end at event $id"
                        }
                    }
                    else -> Unit
                }
            }
        }
        timetable.formationEvents.forEach { declaration ->
            val context = "formation event ${declaration.id}"
            declaration.participantDutyIds.forEach { id ->
                scheduled(id, context)
                if (invocations[declaration.id].orEmpty().count { it.first == id } != 1)
                    errors += "$context requires exactly one action from participant $id"
            }
            declaration.outputs.forEach { output ->
                val target = (output.disposition as? FormationOutputDisposition.Duty)?.dutyId
                val claims = acquisitions[declaration.id to output.id].orEmpty()
                if (target == null) {
                    if (claims.isNotEmpty()) errors += "$context HOLD output ${output.id} cannot be acquired"
                } else {
                    scheduled(target, context)
                    if (target in declaration.participantDutyIds) {
                        if (claims.isNotEmpty()) errors += "$context continuing output ${output.id} must not be reacquired"
                    } else if (claims != listOf(target))
                        errors += "$context output ${output.id} requires exactly one reciprocal acquisition by $target"
                }
            }
        }
        val active = hashSetOf<String>()
        val visited = hashSetOf<String>()
        fun visit(id: String) {
            if (id in active) { errors += "formation output acquisition cycle at duty $id"; return }
            if (!visited.add(id)) return
            active += id
            dependencies[id].orEmpty().forEach(::visit)
            active -= id
        }
        dependencies.keys.forEach(::visit)
        val eventPath = arrayListOf<String>()
        val completedEvents = hashSetOf<String>()
        fun visitEvent(id: String) {
            val cycleStart = eventPath.indexOf(id)
            if (cycleStart >= 0) {
                errors += "formation event ordering cycle: " + (eventPath.drop(cycleStart) + id).joinToString(" -> ")
                return
            }
            if (!completedEvents.add(id)) return
            eventPath += id
            eventDependencies[id].orEmpty().forEach(::visitEvent)
            eventPath.removeAt(eventPath.lastIndex)
        }
        eventDependencies.keys.forEach(::visitEvent)
        return errors.distinct()
    }
}
