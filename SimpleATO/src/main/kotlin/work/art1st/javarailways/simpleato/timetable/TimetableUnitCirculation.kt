package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.simpleato.api.depot.*
import work.art1st.javarailways.simpleato.api.timetable.TimetableServiceActivation
import java.util.Collections

/** One coherent owner-captured ledger, not a model-derived source of imaginary vehicles. */
internal class UnitCirculationLedger(
    units: List<FixedEmuUnit>, stock: List<RollingStockSnapshot>, points: List<StablingPointSnapshot>,
    physicalCars: Map<String, List<String>>, directions: Map<String, Boolean>, carLengthsMeters: Map<String, Double>,
    claims: List<FixedEmuUnitAssignment> = emptyList(), heldOutputs: List<FixedFormationHeldOutput> = emptyList(),
    unresolvedChanges: List<FixedFormationChange> = emptyList()
) {
    val units = java.util.List.copyOf(units)
    val stock = java.util.List.copyOf(stock.map { it.copy(record = it.record.copy(
        tags = java.util.Set.copyOf(it.record.tags), capabilities = java.util.Set.copyOf(it.record.capabilities))) })
    val points = java.util.List.copyOf(points.map { it.copy(point = it.point.copy(allowedTags = java.util.Set.copyOf(it.point.allowedTags))) })
    val physicalCars: Map<String, List<String>> = Collections.unmodifiableMap(physicalCars.mapValues { java.util.List.copyOf(it.value) })
    val directions = java.util.Map.copyOf(directions)
    val carLengthsMeters = java.util.Map.copyOf(carLengthsMeters)
    val claims = java.util.List.copyOf(claims)
    val heldOutputs = java.util.List.copyOf(heldOutputs)
    val unresolvedChanges = java.util.List.copyOf(unresolvedChanges)
}

internal enum class CirculationProof { PROVEN, IMPOSSIBLE, UNPROVEN }
internal data class CirculationFinding(val code: String, val detail: String, val dutyId: String? = null, val stepIndex: Int? = null, val blocking: Boolean = false)
internal data class CirculationReport(
    val inventory: CirculationProof, val timing: CirculationProof, val capacity: CirculationProof,
    val findings: List<CirculationFinding>, val periodicInventoryCycles: Int? = null, val exploredStates: Int = 0
)
internal data class CirculationLimits(val maximumStates: Int = 10_000, val maximumCycles: Int = 32) {
    init { require(maximumStates > 0 && maximumCycles > 0) }
}

/**
 * Inventory-only, time-abstract search over the actual fixed timetable/catalog. A witness is
 * conditional on the fixed action graph and declared classification, never on driving compatibility,
 * action completion deadlines or free physical track. At.tick is deliberately not a deadline.
 */
internal object TimetableUnitCirculation {
    fun analyze(timetable: Timetable, ledger: UnitCirculationLedger, limits: CirculationLimits = CirculationLimits()): CirculationReport {
        val findings = arrayListOf(CirculationFinding("COMPLETION_BOUNDS_MISSING",
            "At ticks are earliest entry only; completion bounds and overlapping-cycle timing/capacity are unproven"))
        fun report(inventory: CirculationProof, capacity: CirculationProof = CirculationProof.UNPROVEN,
            cycles: Int? = null, explored: Int = 0) = CirculationReport(inventory, CirculationProof.UNPROVEN, capacity,
                java.util.List.copyOf(findings), cycles, explored)
        val catalogErrors = FormationEventValidator.validate(timetable)
        if (catalogErrors.isNotEmpty()) {
            findings += catalogErrors.map { CirculationFinding("INVALID_FIXED_GRAPH", it, blocking = true) }
            return report(CirculationProof.IMPOSSIBLE)
        }
        if (ledger.stock.any { it.state != RollingStockState.AVAILABLE } || ledger.claims.isNotEmpty() ||
            ledger.heldOutputs.isNotEmpty() || ledger.unresolvedChanges.isNotEmpty() ||
            ledger.points.any { it.reservedByAssignmentId != null }) {
            findings += CirculationFinding("OUTSTANDING_OWNERS", "In-service, held, pending or reserved ownership requires explicit cross-cycle obligations")
            return report(CirculationProof.UNPROVEN)
        }
        if (timetable.initialPositions.isNotEmpty()) {
            findings += CirculationFinding("INITIAL_PLACEMENT_UNPROVEN", "Initial placement must be reconciled with the captured physical depot ledger first")
            return report(CirculationProof.UNPROVEN)
        }
        val initial = try { initial(ledger) } catch (failure: MissingEvidence) {
            findings += CirculationFinding("UNIT_EVIDENCE_MISSING", failure.message.orEmpty())
            return report(CirculationProof.UNPROVEN)
        } catch (failure: IllegalArgumentException) {
            findings += CirculationFinding("INVALID_LEDGER", failure.message.orEmpty(), blocking = true)
            return report(CirculationProof.IMPOSSIBLE)
        }
        val unsupported = timetable.services.filter { duty -> duty.activation != TimetableServiceActivation.Scheduled ||
            duty.trainSelector != TrainSelector.AnyTrain ||
            duty.steps.firstOrNull().let { it !is TimetableStep.At ||
                (it.action !is TimetableAction.TakeFromDepot && it.action !is TimetableAction.TakeFromFormationOutput) } }
        if (unsupported.isNotEmpty()) {
            findings += unsupported.map { CirculationFinding("UNBOUNDED_ACQUISITION", "Requires explicit physical-depot or same-cycle formation acquisition and scheduled activation", it.id) }
            return report(CirculationProof.UNPROVEN)
        }
        val duties = timetable.services.associateBy { it.id }
        if (duties.size != timetable.services.size) {
            findings += CirculationFinding("INVALID_FIXED_GRAPH", "Duplicate duty identity", blocking = true)
            return report(CirculationProof.IMPOSSIBLE)
        }
        // No structure changes means complete physical formations cannot be created, divided or
        // change classification. Net physical-depot loss is impossible under every timing/order.
        if (timetable.formationEvents.isEmpty()) {
            val balance = linkedMapOf<DepotId, Int>()
            val classEdges = arrayListOf<Triple<DepotId, DepotId?, Set<DeclaredClass>>>()
            for (duty in timetable.services) {
                val take = ((duty.steps.first() as TimetableStep.At).action as TimetableAction.TakeFromDepot)
                balance[take.selector.depotId] = (balance[take.selector.depotId] ?: 0) - 1
                val stores = duty.steps.mapNotNull { ((it as? TimetableStep.At)?.action as? TimetableAction.StoreAtDepot) }
                if (duty.steps.drop(1).any { ((it as? TimetableStep.At)?.action) is TimetableAction.TakeFromDepot } || stores.size > 1) {
                    findings += CirculationFinding("OWNERSHIP_FLOW_UNPROVEN", "Multiple acquisitions/storage require authoritative normalized flow", duty.id)
                    return report(CirculationProof.UNPROVEN)
                }
                stores.forEach { balance[it.depotId] = (balance[it.depotId] ?: 0) + 1 }
                val candidates = initial.filter { take.selector.matches(it.record) &&
                    (duty.trainSelector !is TrainSelector.TrainId || duty.trainSelector.trainId == it.trainId) }
                classEdges += Triple(take.selector.depotId, stores.singleOrNull()?.depotId,
                    candidates.map { declaredClass(it, ledger.units) }.toSet())
                if (stores.any { store -> candidates.isNotEmpty() && candidates.none { candidate -> ledger.points.any { point ->
                        point.point.ref.depotId == store.depotId &&
                            (store.stablingPointIds.isEmpty() || point.point.ref.pointId in store.stablingPointIds) && accepts(point.point, candidate.record)
                    } } }) {
                    findings += CirculationFinding("NO_COMPATIBLE_STORAGE_POINT", "No physical point fits any eligible complete formation", duty.id, blocking = true)
                    return report(CirculationProof.IMPOSSIBLE, CirculationProof.IMPOSSIBLE)
                }
                if (initial.none { take.selector.matches(it.record) &&
                        (duty.trainSelector !is TrainSelector.TrainId || duty.trainSelector.trainId == it.trainId) }) {
                    findings += CirculationFinding("NO_COMPATIBLE_FORMATION", "No actual whole formation has the required declared eligibility", duty.id, 0, blocking = true)
                    return report(CirculationProof.IMPOSSIBLE)
                }
            }
            if (balance.values.any { it < 0 }) {
                findings += CirculationFinding("RECURRING_DEPOT_DEFICIT", "Whole-formation net flow per cycle: " + balance.entries.joinToString { "${it.key.value}=${it.value}" }, blocking = true)
                return report(CirculationProof.IMPOSSIBLE)
            }
            val classes = classEdges.flatMap { it.third }.toSet()
            val depots = classEdges.flatMap { listOfNotNull(it.first, it.second) }.toSet()
            for (type in classes) for (depot in depots) {
                val forcedTakes = classEdges.count { it.first == depot && it.third == setOf(type) }
                val possibleReturns = classEdges.count { it.second == depot && type in it.third }
                if (forcedTakes > possibleReturns) {
                    findings += CirculationFinding("RECURRING_CLASS_DEFICIT",
                        "${depot.value} has $forcedTakes forced acquisitions but at most $possibleReturns returns of declared class $type per cycle", blocking = true)
                    return report(CirculationProof.IMPOSSIBLE)
                }
            }
        }
        val search = Search(timetable, ledger, duties, limits)
        val period = search.periodic(initial)
        if (period != null) {
            findings += CirculationFinding("INVENTORY_WITNESS_ONLY", "Exact permanent-unit grouping, physical depot and point recur; unit homes and Core container counts are not asset balance")
            findings += CirculationFinding("DECLARED_COMPATIBILITY_ONLY", "Control-family equality and declared tags/capabilities do not prove physical traction/braking compatibility")
            return report(CirculationProof.PROVEN, cycles = period, explored = search.explored)
        }
        findings += CirculationFinding(if (search.limited) "SEARCH_LIMIT" else "CROSS_CYCLE_OBLIGATIONS_UNPROVEN",
            if (search.limited) "Finite circulation search ceiling reached without a proof" else
                "No complete-cycle inventory witness; outstanding returns or cross-cycle work cannot be ruled out by earliest ticks")
        return report(CirculationProof.UNPROVEN, explored = search.explored)
    }

    private data class DeclaredClass(val family: String, val units: Int, val model: String?, val tags: Set<String>,
        val cars: Int, val length: Double, val speed: Double, val capabilities: Set<String>)
    private fun declaredClass(formation: Formation, units: List<FixedEmuUnit>): DeclaredClass {
        val members = unitsFor(formation.cars, units)
        val stock = formation.record
        return DeclaredClass(members.first().controlCompatibilityKey, members.size, stock.modelPackageId,
            stock.tags, stock.carCount, stock.lengthMeters, stock.maximumSpeedMetersPerSecond, stock.capabilities)
    }

    private class MissingEvidence(message: String) : IllegalArgumentException(message)
    private data class Formation(val cars: List<String>, val reversed: Boolean, val record: RollingStockRecord,
        val trainId: String?, val point: StablingPointRef? = null)
    private data class State(val bank: List<Formation>, val cursors: Map<String, Int>, val active: Map<String, Formation>,
        val outputs: Map<Pair<String, String>, Formation>, val completedEvents: Set<String>, val held: List<Formation>)

    private fun initial(ledger: UnitCirculationLedger): List<Formation> {
        require(ledger.units.map { it.id }.distinct().size == ledger.units.size) { "duplicate unit IDs" }
        val stockIds = ledger.stock.map { it.record.trainId }.toSet()
        if (ledger.physicalCars.keys.any { it !in stockIds } || ledger.points.any {
                it.occupantTrainId != null && it.occupantTrainId !in stockIds }) {
            throw MissingEvidence("Physical formations or point occupants outside the rolling stock ledger may affect acquisition")
        }
        val unitCars = ledger.units.flatMap { it.carIds }
        require(unitCars.distinct().size == unitCars.size) { "permanent units overlap" }
        val physicalCars = ledger.physicalCars.values.flatten()
        require(physicalCars.distinct().size == physicalCars.size) { "physical cars appear in multiple trains" }
        if (!unitCars.all(physicalCars::contains)) throw MissingEvidence("Permanent unit cars have no captured physical owner")
        val stockCars = ledger.stock.flatMap { ledger.physicalCars[it.record.trainId].orEmpty() }
        if (unitCars.any { it !in stockCars }) throw MissingEvidence("Some physical permanent units have no rolling stock metadata")
        require(ledger.stock.map { it.record.trainId }.distinct().size == ledger.stock.size) { "duplicate rolling stock" }
        require(ledger.points.map { it.point.ref }.distinct().size == ledger.points.size) { "duplicate physical points" }
        require(ledger.stock.mapNotNull { it.stablingPointRef }.distinct().size == ledger.stock.count { it.stablingPointRef != null }) { "multiple formations occupy one physical point" }
        return ledger.stock.map { stock ->
            val cars = ledger.physicalCars[stock.record.trainId]
                ?: throw MissingEvidence("Stock has no captured physical membership")
            if (cars.none { car -> ledger.units.any { car in it.carIds } }) throw MissingEvidence("Legacy physical stock has no permanent unit registration")
            unitsFor(cars, ledger.units)
            require(cars.size == stock.record.carCount && cars.sumOf { id ->
                val length = requireNotNull(ledger.carLengthsMeters[id]); require(length.isFinite() && length > 0.0); length
            } == stock.record.lengthMeters) { "stock length/count does not match actual cars" }
            val point = stock.stablingPointRef
            if (stock.state == RollingStockState.AVAILABLE) {
                require(stock.assignmentId == null && point != null) { "available stock has no exact physical point" }
                val place = requireNotNull(ledger.points.singleOrNull { it.point.ref == point }) { "physical point missing" }
                require(place.occupantTrainId == stock.record.trainId && accepts(place.point, stock.record)) { "point owner or physical compatibility disagrees" }
            }
            Formation(java.util.List.copyOf(cars), requireNotNull(ledger.directions[stock.record.trainId]),
                stock.record.copy(trainId = key(cars)), stock.record.trainId, point)
        }.filterIndexed { index, _ -> ledger.stock[index].state == RollingStockState.AVAILABLE }
    }

    private fun unitsFor(cars: List<String>, units: List<FixedEmuUnit>): List<FixedEmuUnit> {
        require(cars.isNotEmpty() && cars.distinct().size == cars.size)
        val result = arrayListOf<FixedEmuUnit>(); var at = 0
        while (at < cars.size) {
            val unit = requireNotNull(units.singleOrNull { cars[at] in it.carIds }) { "unknown permanent unit membership" }
            require(at + unit.carIds.size <= cars.size) { "partial permanent unit" }
            val slice = cars.subList(at, at + unit.carIds.size)
            require(slice == unit.carIds || slice == unit.carIds.asReversed()) { "partial or reordered permanent unit" }
            result += unit; at += unit.carIds.size
        }
        require(result.map { it.controlCompatibilityKey }.distinct().size == 1) { "formation has incompatible declared control families" }
        return result
    }
    private fun accepts(point: DepotStablingPoint, record: RollingStockRecord) =
        (point.usableLengthMeters == null || record.lengthMeters <= point.usableLengthMeters) &&
            (point.allowedTags.isEmpty() || record.tags.any(point.allowedTags::contains))
    private fun key(cars: List<String>) = "circulation:" + cars.joinToString(":") { "${it.length}:$it" }
    private fun canonical(bank: Collection<Formation>) = bank.sortedWith(compareBy({ it.point?.toString() }, { key(it.cars) }))

    private class Search(val timetable: Timetable, val ledger: UnitCirculationLedger,
        val duties: Map<String, TimetableService>, val limits: CirculationLimits) {
        var explored = 0; var limited = false
        private val deadBanks = hashSetOf<List<Formation>>()
        private fun budget(): Boolean {
            if (explored >= limits.maximumStates) { limited = true; return false }; explored++; return true
        }
        fun periodic(bank: List<Formation>, path: Map<List<Formation>, Int> = emptyMap(), depth: Int = 0, stackDepth: Int = 0): Int? {
            val ordered = canonical(bank)
            path[ordered]?.let { return depth - it }
            if (depth >= limits.maximumCycles || stackDepth >= 256) { limited = true; return null }
            if (ordered in deadBanks || !budget()) return null
            val seen = hashSetOf<State>()
            fun visit(state: State, level: Int): Int? {
                if (level >= 256) { limited = true; return null }
                if (!seen.add(state) || !budget()) return null
                if (state.cursors.all { (id, index) -> index >= duties.getValue(id).steps.size }) {
                    if (state.active.isNotEmpty() || state.outputs.isNotEmpty() || state.held.isNotEmpty()) return null
                    return periodic(state.bank, path + (ordered to depth), depth + 1, level + 1)
                }
                for (id in duties.keys.sorted()) {
                    for (next in next(state, id)) visit(next, level + 1)?.let { return it }
                    if (limited) return null
                }
                return null
            }
            val result = visit(State(ordered, duties.keys.associateWith { 0 }, emptyMap(), emptyMap(), emptySet(), emptyList()), stackDepth + 1)
            if (!limited && result == null) deadBanks += ordered
            return result
        }
        private fun next(state: State, id: String): List<State> {
            val duty = duties.getValue(id); val index = state.cursors.getValue(id)
            if (index >= duty.steps.size) return emptyList()
            val action = (duty.steps[index] as? TimetableStep.At)?.action
            fun advance(active: Map<String, Formation> = state.active, bank: List<Formation> = state.bank,
                outputs: Map<Pair<String, String>, Formation> = state.outputs) =
                state.copy(active = active, bank = canonical(bank), outputs = outputs, cursors = state.cursors + (id to index + 1))
            return when (action) {
                is TimetableAction.TakeFromDepot -> if (id in state.active) emptyList() else state.bank.filter { it.point?.depotId == action.selector.depotId &&
                    action.selector.matches(it.record) && (duty.trainSelector !is TrainSelector.TrainId || duty.trainSelector.trainId == it.trainId) }
                    .map { advance(state.active + (id to it.copy(point = null)), state.bank - it) }
                is TimetableAction.TakeFromFormationOutput -> {
                    if (id in state.active) return emptyList()
                    val output = state.outputs[action.eventId to action.outputId] ?: return emptyList()
                    listOf(advance(state.active + (id to output), outputs = state.outputs - (action.eventId to action.outputId)))
                }
                is TimetableAction.StoreAtDepot -> {
                    val formation = state.active[id] ?: return emptyList()
                    ledger.points.filter { it.point.ref.depotId == action.depotId &&
                        (action.stablingPointIds.isEmpty() || it.point.ref.pointId in action.stablingPointIds) &&
                        accepts(it.point, formation.record) && state.bank.none { f -> f.point == it.point.ref } }
                        .map { advance(state.active - id, state.bank + formation.copy(point = it.point.ref)) }
                }
                is TimetableAction.Reverse -> state.active[id]?.let { listOf(advance(state.active + (id to it.copy(reversed = !it.reversed)))) }.orEmpty()
                is TimetableAction.Couple, is TimetableAction.Split -> event(state,
                    if (action is TimetableAction.Couple) action.eventId else (action as TimetableAction.Split).eventId)
                is TimetableAction.Halt -> listOf(state.copy(cursors = state.cursors + (id to duty.steps.size),
                    active = state.active - id, held = state.held + listOfNotNull(state.active[id])))
                else -> listOf(advance()) // Position, dwell, inspection and extension timing are not inventory proof.
            }
        }
        private fun event(state: State, eventId: String): List<State> {
            if (eventId in state.completedEvents) return emptyList()
            val event = timetable.formationEvents.single { it.id == eventId }
            if (event.participantDutyIds.any { id ->
                    val action = ((duties.getValue(id).steps.getOrNull(state.cursors.getValue(id))) as? TimetableStep.At)?.action
                    ((action as? TimetableAction.Couple)?.eventId ?: (action as? TimetableAction.Split)?.eventId) != eventId || id !in state.active
                }) return emptyList()
            val formed: Map<String, Formation> = try {
                when (event) {
                    is TimetableFormationEvent.Couple -> {
                        val a = state.active.getValue(event.approachingDutyId); val b = state.active.getValue(event.stationaryDutyId)
                        val movingEndB = a.reversed
                        val stationaryEndB = if (event.stationaryEnd == FormationStationaryEnd.HEAD) b.reversed else !b.reversed
                        val incoming = if (movingEndB == stationaryEndB) b.cars.asReversed() else b.cars
                        val cars = if (movingEndB) a.cars + incoming else incoming + a.cars
                        unitsFor(cars, ledger.units)
                        val metadata = FormationOutputMetadata.couple(listOf(a.record, b.record),
                            mapOf(a.record.trainId to a.cars, b.record.trainId to b.cars), cars,
                            ledger.carLengthsMeters.filterKeys(cars::contains))
                        mapOf(event.output.id to Formation(cars, a.reversed, metadata.toRecord(key(cars)), a.trainId))
                    }
                    is TimetableFormationEvent.Split -> {
                        val source = state.active.getValue(event.coordinatorDutyId)
                        val units = unitsFor(source.cars, ledger.units)
                        require(event.firstUnitCount < units.size)
                        val prefixCount = if (source.reversed) units.size - event.firstUnitCount else event.firstUnitCount
                        val cut = units.take(prefixCount).sumOf { it.carIds.size }
                        val prefix = source.cars.take(cut); val suffix = source.cars.drop(cut)
                        fun form(cars: List<String>, retained: Boolean) = Formation(cars, source.reversed,
                            FormationOutputMetadata.split(source.record, source.cars, cars,
                                ledger.carLengthsMeters.filterKeys(source.cars::contains)).toRecord(key(cars)),
                            if (retained) source.trainId else null)
                        val first = if (source.reversed) form(suffix, false) else form(prefix, true)
                        val rest = if (source.reversed) form(prefix, true) else form(suffix, false)
                        mapOf(event.first.id to first, event.rest.id to rest)
                    }
                }
            } catch (_: IllegalArgumentException) { return emptyList() }
            var active = state.active - event.participantDutyIds
            var outputs = state.outputs; var held = state.held
            var cursors = state.cursors
            event.participantDutyIds.forEach { cursors = cursors + (it to cursors.getValue(it) + 1) }
            event.outputs.forEach { output ->
                val formation = formed.getValue(output.id)
                when (val disposition = output.disposition) {
                    FormationOutputDisposition.Hold -> held = held + formation
                    is FormationOutputDisposition.Duty -> if (disposition.dutyId in event.participantDutyIds)
                        active = active + (disposition.dutyId to formation)
                    else outputs = outputs + ((event.id to output.id) to formation)
                }
            }
            return listOf(state.copy(active = active, outputs = outputs, held = held, cursors = cursors,
                completedEvents = state.completedEvents + eventId))
        }
    }
}
