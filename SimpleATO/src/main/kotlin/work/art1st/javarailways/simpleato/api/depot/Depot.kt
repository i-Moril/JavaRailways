package work.art1st.javarailways.simpleato.api.depot

import work.art1st.javarailways.core.api.util.annotation.MainThread
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@JvmInline
value class DepotId(val value: String) {
    init {
        requireStableId("depotId", value)
    }

    override fun toString(): String = value
}

data class StablingPointRef(
    val depotId: DepotId,
    val pointId: String
) {
    init {
        requireStableId("stablingPointId", pointId)
    }

    override fun toString(): String = "${depotId.value}/$pointId"
}

data class Depot(
    val id: DepotId,
    val displayName: String,
    val stablingPointRefs: List<StablingPointRef>
) {
    init {
        require(displayName.isNotBlank() && displayName == displayName.trim())
        require(stablingPointRefs.all { it.depotId == id })
        require(stablingPointRefs.distinct().size == stablingPointRefs.size)
    }
}

data class DepotStablingPoint(
    val ref: StablingPointRef,
    val destinationId: String,
    val usableLengthMeters: Double? = null,
    val allowedTags: Set<String> = emptySet()
) {
    init {
        requireStableId("destinationId", destinationId)
        require(usableLengthMeters == null || usableLengthMeters.isFinite() && usableLengthMeters > 0.0)
        requireTags("allowedTags", allowedTags)
    }
}

data class RollingStockRecord(
    val trainId: String,
    val homeDepotId: DepotId,
    val modelPackageId: String?,
    val tags: Set<String>,
    val carCount: Int,
    val lengthMeters: Double,
    val maximumSpeedMetersPerSecond: Double,
    val capabilities: Set<String>
) {
    init {
        requireStableId("trainId", trainId)
        modelPackageId?.let { requireStableId("modelPackageId", it) }
        requireTags("tags", tags)
        require(carCount > 0)
        require(lengthMeters.isFinite() && lengthMeters > 0.0)
        require(maximumSpeedMetersPerSecond.isFinite() && maximumSpeedMetersPerSecond > 0.0)
        requireTags("capabilities", capabilities)
    }
}

data class DepotTrainSelector(
    val depotId: DepotId,
    val requiredTags: Set<String> = emptySet(),
    val minimumCars: Int? = null,
    val maximumCars: Int? = null,
    val minimumSpeedMetersPerSecond: Double? = null,
    val requiredCapabilities: Set<String> = emptySet()
) {
    init {
        requireTags("requiredTags", requiredTags)
        requireTags("requiredCapabilities", requiredCapabilities)
        require(minimumCars == null || minimumCars > 0)
        require(maximumCars == null || maximumCars > 0)
        require(minimumCars == null || maximumCars == null || minimumCars <= maximumCars)
        require(minimumSpeedMetersPerSecond == null ||
            minimumSpeedMetersPerSecond.isFinite() && minimumSpeedMetersPerSecond > 0.0)
    }

    fun matches(record: RollingStockRecord): Boolean =
        record.tags.containsAll(requiredTags) &&
            (minimumCars == null || record.carCount >= minimumCars) &&
            (maximumCars == null || record.carCount <= maximumCars) &&
            (minimumSpeedMetersPerSecond == null ||
                record.maximumSpeedMetersPerSecond >= minimumSpeedMetersPerSecond) &&
            record.capabilities.containsAll(requiredCapabilities)
}

enum class RollingStockState {
    AVAILABLE,
    RESERVED,
    IN_SERVICE
}

data class RollingStockSnapshot(
    val record: RollingStockRecord,
    val state: RollingStockState,
    val stablingPointRef: StablingPointRef?,
    val assignmentId: String? = null
)

data class StablingPointSnapshot(
    val point: DepotStablingPoint,
    val occupantTrainId: String?,
    val reservedByAssignmentId: String?
)

data class DepotTrainAllocation(
    val assignmentId: String,
    val trainId: String,
    val stablingPointRef: StablingPointRef,
    val selector: DepotTrainSelector
)

sealed interface DepotTrainAllocationResult {
    data class Allocated(val allocation: DepotTrainAllocation) : DepotTrainAllocationResult
    data class NoMatchingTrain(
        val depotId: DepotId,
        val selector: DepotTrainSelector,
        val availableTrainCount: Int
    ) : DepotTrainAllocationResult
    data class AssignmentConflict(val assignmentId: String) : DepotTrainAllocationResult
}

data class DepotResetAssignment(
    val trainId: String,
    val stablingPointRef: StablingPointRef,
    val destinationId: String
)

data class DepotResetPlan(
    val depotIds: Set<DepotId>,
    val assignments: List<DepotResetAssignment>
) {
    init {
        require(assignments.map { it.trainId }.distinct().size == assignments.size)
        require(assignments.map { it.stablingPointRef }.distinct().size == assignments.size)
        require(assignments.all { it.stablingPointRef.depotId in depotIds })
    }
}

sealed interface DepotResetPlanningResult {
    data class Planned(val plan: DepotResetPlan) : DepotResetPlanningResult
    data class Failure(val reason: String) : DepotResetPlanningResult
}

data class DepotStorageReservation(
    val assignmentId: String,
    val trainId: String,
    val stablingPointRef: StablingPointRef
)

sealed interface DepotStorageReservationResult {
    data class Reserved(val reservation: DepotStorageReservation) : DepotStorageReservationResult
    data class NoCompatiblePoint(
        val depotId: DepotId,
        val requestedPointIds: Set<String>
    ) : DepotStorageReservationResult
    data class RollingStockUnavailable(val trainId: String, val reason: String) :
        DepotStorageReservationResult
}

/** Main-thread owner of Depot configuration, occupancy, rolling stock, and atomic assignments. */
class DepotStore {
    private data class PointRuntime(
        val point: DepotStablingPoint,
        var occupantTrainId: String? = null,
        var reservedByAssignmentId: String? = null
    )

    private data class RollingRuntime(
        val record: RollingStockRecord,
        var state: RollingStockState,
        var stablingPointRef: StablingPointRef?,
        var assignmentId: String? = null
    )

    private val lock = Any()
    private val depots = LinkedHashMap<DepotId, Depot>()
    private val points = LinkedHashMap<StablingPointRef, PointRuntime>()
    private val rollingStock = LinkedHashMap<String, RollingRuntime>()
    private val assignments = LinkedHashMap<String, DepotTrainAllocation>()
    private val storageReservations = LinkedHashMap<String, DepotStorageReservation>()

    private val fixedUnits = LinkedHashMap<String, FixedEmuUnit>()
    private val fixedUnitAssignments = LinkedHashMap<String, FixedEmuUnitAssignment>()

    private val formationChanges = LinkedHashMap<String, FixedFormationChange>()
    private val formationRecoveryHolds = LinkedHashMap<String, FixedFormationRecoveryHold>()
    private val heldFormationOutputs = LinkedHashMap<String, FixedFormationHeldOutput>()
    private val recoveredFormationOutputIds = HashSet<String>()

    internal fun operationalSnapshot(): DepotOperationalSnapshot = synchronized(lock) {
        DepotOperationalSnapshot(fixedUnits.values.toList(), rollingStock.values.map { it.snapshot() },
            points.values.map { StablingPointSnapshot(it.point, it.occupantTrainId, it.reservedByAssignmentId) },
            fixedUnitAssignments.values.toList(), heldFormationOutputs.values.toList(), formationChanges.values.toList())
    }

    fun heldFormationOutputs(): List<FixedFormationHeldOutput> = synchronized(lock) { heldFormationOutputs.values.toList() }

    fun unresolvedFormationChanges(): List<FixedFormationChange> = synchronized(lock) { formationChanges.values.toList() }
    fun recoveredFormationHolds(): List<FixedFormationRecoveryHold> = synchronized(lock) { formationRecoveryHolds.values.toList() }

    /** Stage only after physical/admission preflight; original claims stay live until reconciliation. */
    @MainThread
    fun prepareFixedFormationChange(change: FixedFormationChange, physicalFormations: Map<String, List<String>>, physicalDirections: Map<String, Boolean>) = synchronized(lock) {
        require(change.phase == FixedFormationPhase.SOURCES_HELD) { "new formation must retain source owners" }
        require(change.eventId !in formationChanges) { "formation event already exists: ${change.eventId}" }
        validateFormationMembership(change, fixedUnits.values)
        val involved = change.sources + change.outputs.map { it.owner }
        require(involved.none { owner -> formationChanges.values.any { existing -> changesOverlap(existing, owner) } ||
            heldFormationOutputs.values.any { held -> ownersOverlap(held.owner, owner) } }) {
            "formation is already held by another structural event"
        }
        val sourceIds = change.sources.map { it.trainId }.toSet()
        val sourceAssignments = change.sources.map { it.assignmentId }.toSet()
        change.sources.forEach { source ->
            require(physicalFormations[source.trainId] == source.carIds && physicalDirections[source.trainId] == source.reversed) { "source Core membership changed: ${source.trainId}" }
            val stock = requireNotNull(rollingStock[source.trainId]) { "source rolling stock is missing: ${source.trainId}" }
            require(stock.state == RollingStockState.IN_SERVICE && stock.assignmentId == source.assignmentId && stock.stablingPointRef == null && stock.record.carCount == source.carIds.size) {
                "source must be IN_SERVICE after complete departure: ${source.trainId}"
            }
            require(storageReservations[source.assignmentId] == null) { "source has a storage reservation" }
            val expected = resolveFixedUnits(source.carIds).map { FixedEmuUnitAssignment(it.id, source.trainId, source.assignmentId) }.toSet()
            require(fixedUnitAssignments.values.filter { it.trainId == source.trainId }.toSet() == expected) { "source unit ownership changed" }
        }
        change.outputs.forEach { output ->
            require(output.owner.trainId in sourceIds || output.owner.trainId !in rollingStock && output.owner.trainId !in physicalFormations) { "output train ID is already occupied" }
            require(output.owner.assignmentId in sourceAssignments ||
                rollingStock.values.none { it.assignmentId == output.owner.assignmentId } &&
                fixedUnitAssignments.values.none { it.assignmentId == output.owner.assignmentId }) { "output assignment ID is already occupied" }
        }
        formationChanges[change.eventId] = change
    }

    /** Coordinator calls only after Basic has confirmed an unsealed abort; no physical rollback. */
    @MainThread
    internal fun abortFixedFormationChange(change: FixedFormationChange,
        physicalFormations: Map<String, List<String>>, physicalDirections: Map<String, Boolean>) = synchronized(lock) {
        require(formationChanges[change.eventId] === change) { "formation event token is stale" }
        require(change.phase == FixedFormationPhase.SOURCES_HELD) { "output ownership is already committed" }
        check(change.eventId !in formationRecoveryHolds) { "restored formation event requires explicit recovery" }
        require(matchesFormationSide(change.sources, change, physicalFormations, physicalDirections)) {
            "published formation no longer matches the original sources"
        }
        formationChanges.remove(change.eventId)
    }

    /** Called only after Basic observes and holds every published output; no release/reclaim interval. */
    @MainThread
    fun commitFixedFormationChange(change: FixedFormationChange, physicalFormations: Map<String, List<String>>, outputRecords: List<RollingStockRecord>, physicalDirections: Map<String, Boolean>): FixedFormationChange = synchronized(lock) {
        require(formationChanges[change.eventId] === change) { "formation event token is stale" }
        require(change.phase == FixedFormationPhase.SOURCES_HELD) { "output ownership is already committed" }
        check(change.eventId !in formationRecoveryHolds) { "restored formation event requires explicit recovery" }
        require(matchesFormationSide(change.outputs.map { it.owner }, change, physicalFormations, physicalDirections)) { "published Core outputs do not match the prepared change" }
        require(outputRecords.map { it.trainId }.toSet() == change.outputs.map { it.owner.trainId }.toSet() && outputRecords.size == change.outputs.size) { "output metadata is incomplete" }
        val records = outputRecords.associateBy { it.trainId }
        val newClaims = change.outputs.flatMap { output ->
            val record = records.getValue(output.owner.trainId)
            require(record.carCount == output.owner.carIds.size && record.homeDepotId in depots) { "output metadata does not match the formation" }
            resolveFixedUnits(output.owner.carIds).map { FixedEmuUnitAssignment(it.id, output.owner.trainId, output.owner.assignmentId) }
        }
        change.sources.forEach { source ->
            rollingStock.remove(source.trainId)
            assignments.remove(source.assignmentId)
        }
        change.outputs.forEach { output ->
            rollingStock[output.owner.trainId] = RollingRuntime(records.getValue(output.owner.trainId), RollingStockState.IN_SERVICE, null, output.owner.assignmentId)
        }
        newClaims.forEach { fixedUnitAssignments[it.unitId] = it }
        FixedFormationChange(change.eventId, change.kind, change.sources, change.outputs,
            FixedFormationPhase.OUTPUT_OWNERS_COMMITTED).also { formationChanges[change.eventId] = it }
    }

    /**
     * The serialized coordinator calls only after installing every mission/disposition, adopting
     * all ready grants before the next physics frame. Each pending continuation then has its own
     * persisted fence ownership; a departed peer no longer belongs to this unresolved group.
     */
    @MainThread
    internal fun finishFixedFormationChange(change: FixedFormationChange, physicalFormations: Map<String, List<String>>,
        physicalDirections: Map<String, Boolean>, installedOutputOwners: List<FixedFormationOwner>,
        pendingContinuingOwners: List<FixedFormationOwner> = emptyList()) = synchronized(lock) {
        require(formationChanges[change.eventId] === change) { "formation event token is stale" }
        require(change.phase == FixedFormationPhase.OUTPUT_OWNERS_COMMITTED) { "output ownership is not committed" }
        check(change.eventId !in formationRecoveryHolds) { "restored formation event requires explicit recovery" }
        val outputs = change.outputs.map { it.owner }
        require(installedOutputOwners.size == outputs.size && installedOutputOwners.toSet() == outputs.toSet()) {
            "every exact output disposition must be installed before finishing"
        }
        val continuing = change.outputs.filter { it.disposition == FixedFormationDisposition.CONTINUE_DUTY }.map { it.owner }.toSet()
        require(pendingContinuingOwners.distinct().size == pendingContinuingOwners.size &&
            pendingContinuingOwners.all { it in continuing }) { "pending continuation owners must be exact continuing outputs" }
        require(matchesFormationSide(outputs, change, physicalFormations, physicalDirections)) { "physical outputs changed before finish" }
        outputs.forEach { owner ->
            val stock = requireNotNull(rollingStock[owner.trainId])
            require(stock.state == RollingStockState.IN_SERVICE && stock.assignmentId == owner.assignmentId &&
                stock.stablingPointRef == null && stock.record.carCount == owner.carIds.size) { "output metadata changed before finish" }
            val expected = resolveFixedUnits(owner.carIds).map { FixedEmuUnitAssignment(it.id, owner.trainId, owner.assignmentId) }.toSet()
            require(fixedUnitAssignments.values.filter { it.trainId == owner.trainId }.toSet() == expected) { "output unit claims changed before finish" }
        }
        change.outputs.filter { it.disposition == FixedFormationDisposition.HOLD || it.owner in pendingContinuingOwners }.forEach { output ->
            heldFormationOutputs[output.owner.trainId] = FixedFormationHeldOutput(output.owner, output.disposition)
        }
        formationChanges.remove(change.eventId)
    }

    /** Caller validates the actual stopped Core frame before releasing its Basic fence. */
    @MainThread
    internal fun validatePendingFormationOutput(owner: FixedFormationOwner, physicalFormations: Map<String, List<String>>,
        physicalDirections: Map<String, Boolean>): FixedFormationHeldOutput = synchronized(lock) {
        val held = requireNotNull(heldFormationOutputs[owner.trainId]) { "pending formation output is missing" }
        require(held.owner == owner && held.disposition == FixedFormationDisposition.CONTINUE_DUTY) { "output is not this pending continuation" }
        check(owner.trainId !in recoveredFormationOutputIds) { "restored formation output requires explicit recovery" }
        validatePendingFormationOwnership(owner, physicalFormations, physicalDirections)
        held
    }

    private fun validatePendingFormationOwnership(owner: FixedFormationOwner,
        physicalFormations: Map<String, List<String>>, physicalDirections: Map<String, Boolean>) {
        require(physicalFormations.filterValues { cars -> cars.any(owner.carIds::contains) } == mapOf(owner.trainId to owner.carIds) &&
            physicalDirections[owner.trainId] == owner.reversed) { "pending physical formation changed" }
        val stock = requireNotNull(rollingStock[owner.trainId]) { "pending output metadata is missing" }
        require(stock.state == RollingStockState.IN_SERVICE && stock.assignmentId == owner.assignmentId &&
            stock.stablingPointRef == null && stock.record.carCount == owner.carIds.size) { "pending output metadata changed" }
        val expected = resolveFixedUnits(owner.carIds).map { FixedEmuUnitAssignment(it.id, owner.trainId, owner.assignmentId) }.toSet()
        require(fixedUnitAssignments.values.filter { it.trainId == owner.trainId }.toSet() == expected) { "pending unit ownership changed" }
    }

    /** Called immediately after the authorized stopped Core reversal, before another frame. */
    @MainThread
    internal fun recordPendingFormationReversal(token: FixedFormationHeldOutput,
        physicalFormations: Map<String, List<String>>, physicalDirections: Map<String, Boolean>): FixedFormationHeldOutput = synchronized(lock) {
        val owner = token.owner
        require(heldFormationOutputs[owner.trainId] === token) { "pending output reversal token is stale" }
        require(token.disposition == FixedFormationDisposition.CONTINUE_DUTY) { "HOLD output cannot reverse as a duty" }
        check(owner.trainId !in recoveredFormationOutputIds) { "restored formation output requires explicit recovery" }
        val reversed = FixedFormationOwner(owner.trainId, owner.assignmentId, owner.carIds, !owner.reversed)
        validatePendingFormationOwnership(reversed, physicalFormations, physicalDirections)
        FixedFormationHeldOutput(reversed, token.disposition).also { heldFormationOutputs[owner.trainId] = it }
    }

    /** Same serialized callback, immediately after successful Basic resume; never release a peer. */
    @MainThread
    internal fun releasePendingFormationOutput(token: FixedFormationHeldOutput, physicalFormations: Map<String, List<String>>,
        physicalDirections: Map<String, Boolean>) = synchronized(lock) {
        require(heldFormationOutputs[token.owner.trainId] === token) { "pending output token is stale" }
        check(validatePendingFormationOutput(token.owner, physicalFormations, physicalDirections) === token)
        heldFormationOutputs.remove(token.owner.trainId)
        Unit
    }

    private fun ownersOverlap(first: FixedFormationOwner, second: FixedFormationOwner): Boolean =
        first.trainId == second.trainId || first.assignmentId == second.assignmentId || first.carIds.any(second.carIds::contains)

    private fun changesOverlap(change: FixedFormationChange, owner: FixedFormationOwner): Boolean =
        (change.sources + change.outputs.map { it.owner }).any {
            it.trainId == owner.trainId || it.assignmentId == owner.assignmentId || it.carIds.any(owner.carIds::contains)
        }

    private fun assignmentIsOwned(assignmentId: String): Boolean =
        assignmentId in assignments || rollingStock.values.any { it.assignmentId == assignmentId } ||
            fixedUnitAssignments.values.any { it.assignmentId == assignmentId } ||
            formationChanges.values.any { change -> (change.sources + change.outputs.map { it.owner }).any { it.assignmentId == assignmentId } }

    private fun formationHoldsTrain(trainId: String): Boolean = trainId in heldFormationOutputs || formationChanges.values.any { change ->
        change.sources.any { it.trainId == trainId } || change.outputs.any { it.owner.trainId == trainId }
    }

    private fun validateFormationMembership(change: FixedFormationChange, units: Collection<FixedEmuUnit>) {
        (change.sources + change.outputs.map { it.owner }).forEach { resolveFixedUnits(it.carIds, units) }
        if (change.kind == FixedFormationChangeKind.SPLIT) {
            val source = change.sources.single().carIds
            val first = change.outputs[0].owner.carIds
            val second = change.outputs[1].owner.carIds
            require(first + second == source) { "separation outputs must preserve the ordered partition" }
        }
    }

    private fun matchesFormationSide(side: List<FixedFormationOwner>, change: FixedFormationChange, physical: Map<String, List<String>>, directions: Map<String, Boolean>): Boolean {
        val relevantCars = change.sources.flatMap { it.carIds }.toSet()
        val observed = physical.filter { (id, cars) ->
            cars.any(relevantCars::contains) || (change.sources + change.outputs.map { it.owner }).any { it.trainId == id }
        }
        return observed == side.associate { it.trainId to it.carIds } && side.all { directions[it.trainId] == it.reversed }
    }

    /** Caller supplies ordered stable car IDs from the current Core formation. */
    @MainThread
    fun registerFixedUnit(unit: FixedEmuUnit, physicalCarIds: List<String>) = synchronized(lock) {
        require(unit.homeDepotId in depots) { "unit home Depot not found: ${unit.homeDepotId}" }
        require(unit.id !in fixedUnits) { "unit already registered: ${unit.id}" }
        require(fixedUnits.values.none { it.carIds.any(unit.carIds::contains) }) { "a car cannot belong to two fixed units" }
        require(physicalCarIds.distinct().size == physicalCarIds.size) { "physical car IDs must be unique" }
        require(containsUnit(physicalCarIds, unit.carIds)) { "unit membership is not contiguous in the physical formation" }
        fixedUnits[unit.id] = unit
    }

    /** Resolves complete units in actual formation order, allowing a whole unit to be reversed. */
    fun fixedUnitsForFormation(physicalCarIds: List<String>): List<FixedEmuUnit> = synchronized(lock) {
        resolveFixedUnits(physicalCarIds)
    }

    @MainThread
    fun claimFixedFormation(trainId: String, physicalCarIds: List<String>, assignmentId: String): List<FixedEmuUnitAssignment> = synchronized(lock) {
        val claims = prepareFixedClaims(trainId, physicalCarIds, assignmentId, allowUnregistered = false)
        claims.forEach { fixedUnitAssignments[it.unitId] = it }
        claims
    }

    /** Exact ownership only; physical resources are released separately by their existing owners. */
    @MainThread
    fun releaseFixedFormation(trainId: String, assignmentId: String): Boolean = synchronized(lock) {
        if (formationHoldsTrain(trainId)) return@synchronized false
        val selected = fixedUnitAssignments.values.filter { it.trainId == trainId && it.assignmentId == assignmentId }
        selected.forEach { fixedUnitAssignments.remove(it.unitId) }
        selected.isNotEmpty()
    }

    /** Reject an internal-car split before invoking any Core structure mutation. */
    fun validateFixedUnitSplit(physicalCarIds: List<String>, beforeCarId: String): Pair<List<FixedEmuUnit>, List<FixedEmuUnit>> = synchronized(lock) {
        val units = resolveFixedUnits(physicalCarIds)
        val splitIndex = physicalCarIds.indexOf(beforeCarId)
        require(splitIndex > 0 && splitIndex < physicalCarIds.size) { "split must produce two non-empty formations" }
        var boundary = 0
        val unitIndex = units.indexOfFirst { unit ->
            val atBoundary = boundary == splitIndex
            boundary += unit.carIds.size
            atBoundary
        }
        require(unitIndex > 0) { "cannot split inside a fixed EMU unit" }
        units.take(unitIndex) to units.drop(unitIndex)
    }

    fun allFixedUnits(): List<FixedEmuUnit> = synchronized(lock) { fixedUnits.values.sortedBy { it.id } }
    fun allFixedUnitAssignments(): List<FixedEmuUnitAssignment> = synchronized(lock) { fixedUnitAssignments.values.sortedBy { it.unitId } }

    /** Restore definitions/claims only; no pending structural operation is replayed. */
    @MainThread
    fun restoreFixedUnits(units: List<FixedEmuUnit>, claims: List<FixedEmuUnitAssignment>, physicalFormations: Map<String, List<String>>) = synchronized(lock) {
        validateFixedUnitRestore(units, claims, physicalFormations)
        applyFixedUnitRestore(units, claims)
    }

    private fun ledgerFormationOwners(change: FixedFormationChange): List<FixedFormationOwner> = when (change.phase) {
        FixedFormationPhase.SOURCES_HELD -> change.sources
        FixedFormationPhase.OUTPUT_OWNERS_COMMITTED -> change.outputs.map { it.owner }
    }

    private fun validateFixedUnitRestore(units: List<FixedEmuUnit>, claims: List<FixedEmuUnitAssignment>, physicalFormations: Map<String, List<String>>, changes: List<FixedFormationChange> = emptyList()) {
        check(fixedUnits.isEmpty() && fixedUnitAssignments.isEmpty()) { "fixed unit ledger must be empty before restore" }
        require(units.map { it.id }.distinct().size == units.size) { "duplicate fixed unit IDs" }
        val cars = units.flatMap { it.carIds }
        require(cars.distinct().size == cars.size) { "a car cannot belong to two fixed units" }
        val actualCars = physicalFormations.values.flatten()
        require(actualCars.distinct().size == actualCars.size) { "a car cannot belong to two physical formations" }
        require(units.all { unit -> unit.homeDepotId in depots && physicalFormations.values.count { containsUnit(it, unit.carIds) } == 1 }) { "fixed unit home or physical membership is invalid" }
        val byId = units.associateBy { it.id }
        require(claims.map { it.unitId }.distinct().size == claims.size) { "duplicate fixed unit claims" }
        val pendingClaims = changes.flatMap { change -> ledgerFormationOwners(change).flatMap { source ->
            resolveFixedUnits(source.carIds, units).map { FixedEmuUnitAssignment(it.id, source.trainId, source.assignmentId) }
        } }.toSet()
        require(claims.containsAll(pendingClaims)) { "unresolved formation event is missing original unit claims" }
        require(claims.all { claim -> claim in pendingClaims || byId[claim.unitId]?.let { unit -> physicalFormations[claim.trainId]?.let { containsUnit(it, unit.carIds) } } == true }) { "fixed unit claim does not match Core formation" }
        require(claims.groupBy { it.trainId }.values.all { group -> group.map { it.assignmentId }.distinct().size == 1 } &&
            claims.groupBy { it.assignmentId }.values.all { group -> group.map { it.trainId }.distinct().size == 1 }) { "formation has multiple assignment owners" }
        // Check the entire claimed formation, not just each individually valid member.
        physicalFormations.forEach { (trainId, formationCars) ->
            if (formationCars.any(cars::contains)) {
                val members = resolveFixedUnits(formationCars, units).map { it.id }.toSet()
                val assigned = claims.filter { it.trainId == trainId && it !in pendingClaims }.map { it.unitId }.toSet()
                require(assigned.isEmpty() || assigned == members) { "formation assignment must include every fixed unit" }
            }
        }
    }

    private fun applyFixedUnitRestore(units: List<FixedEmuUnit>, claims: List<FixedEmuUnitAssignment>) {
        fixedUnits.putAll(units.associate { unit -> unit.id to unit })
        claims.forEach { fixedUnitAssignments[it.unitId] = it }
    }

    private fun prepareFixedClaims(trainId: String, physicalCarIds: List<String>?, assignmentId: String, allowUnregistered: Boolean = true): List<FixedEmuUnitAssignment> {
        if (allowUnregistered && fixedUnits.isEmpty()) return emptyList()
        requireNotNull(physicalCarIds) { "current Core car IDs are required for fixed-unit acquisition" }
        if (allowUnregistered && fixedUnits.values.none { unit -> unit.carIds.any(physicalCarIds::contains) }) return emptyList()
        requireStableId("trainId", trainId)
        requireStableId("assignmentId", assignmentId)
        require(trainId !in heldFormationOutputs && formationChanges.values.none { change ->
            changesOverlap(change, FixedFormationOwner(trainId, assignmentId, physicalCarIds))
        }) { "formation is held by an unresolved structural event" }
        val units = resolveFixedUnits(physicalCarIds)
        val claims = units.map { FixedEmuUnitAssignment(it.id, trainId, assignmentId) }
        val existingMembers = fixedUnitAssignments.values.filter { it.trainId == trainId }.map { it.unitId }.toSet()
        require(existingMembers.isEmpty() || existingMembers == units.map { it.id }.toSet()) { "claimed formation membership changed" }
        require(fixedUnitAssignments.values.none { (it.trainId == trainId && it.assignmentId != assignmentId) ||
            (it.assignmentId == assignmentId && it.trainId != trainId) }) { "formation has another assignment" }
        require(claims.all { fixedUnitAssignments[it.unitId] == null || fixedUnitAssignments[it.unitId] == it }) { "fixed unit is already assigned" }
        rollingStock[trainId]?.assignmentId?.let { require(it == assignmentId) { "rolling stock has another assignment" } }
        return claims
    }

    private fun resolveFixedUnits(physicalCarIds: List<String>, definitions: Collection<FixedEmuUnit> = fixedUnits.values): List<FixedEmuUnit> {
        require(physicalCarIds.isNotEmpty() && physicalCarIds.distinct().size == physicalCarIds.size) { "formation cars must be non-empty and unique" }
        val byCar = definitions.flatMap { unit -> unit.carIds.map { it to unit } }.toMap()
        val units = ArrayList<FixedEmuUnit>()
        var cursor = 0
        while (cursor < physicalCarIds.size) {
            val unit = requireNotNull(byCar[physicalCarIds[cursor]]) { "unregistered fixed-unit car: ${physicalCarIds[cursor]}" }
            val end = cursor + unit.carIds.size
            require(end <= physicalCarIds.size && physicalCarIds.subList(cursor, end).let { it == unit.carIds || it == unit.carIds.asReversed() }) { "formation contains an incomplete or reordered fixed unit: ${unit.id}" }
            units += unit
            cursor = end
        }
        require(units.map { it.id }.distinct().size == units.size) { "formation contains a unit more than once" }
        require(units.map { it.controlCompatibilityKey }.distinct().size == 1) { "fixed units have incompatible declared control families" }
        return units
    }

    private fun containsUnit(physicalCarIds: List<String>, unitCars: List<String>): Boolean =
        physicalCarIds.windowed(unitCars.size).any { it == unitCars || it == unitCars.asReversed() }

    /** Build a deterministic full matching without mutating occupancy or rolling-stock state. */
    @MainThread
    fun planReset(requestedDepotIds: Set<DepotId>? = null): DepotResetPlanningResult =
        synchronized(lock) {
            val depotIds = (requestedDepotIds ?: rollingStock.values
                .flatMap { resetDepots(it) }
                .toSet()).toSet()
            if (requestedDepotIds != null) {
                requestedDepotIds.firstOrNull { it !in depots }?.let {
                    return@synchronized DepotResetPlanningResult.Failure("Depot not found: $it")
                }
            }
            val records = rollingStock.values
                .filter { resetDepots(it).any(depotIds::contains) }
                .sortedBy { it.record.trainId }
            val candidates = records.associate { runtime ->
                runtime.record.trainId to points.values.asSequence()
                    .filter { it.point.ref.depotId in resetDepots(runtime) && it.point.ref.depotId in depotIds }
                    .filter { pointAccepts(it.point, runtime.record) }
                    .map { it.point }
                    .sortedBy { it.ref.pointId }
                    .toList()
            }
            candidates.entries.firstOrNull { it.value.isEmpty() }?.let { (trainId, _) ->
                return@synchronized DepotResetPlanningResult.Failure(
                    "no compatible StablingPoint for RollingStock $trainId"
                )
            }
            val pointOwners = LinkedHashMap<StablingPointRef, String>()
            val recordsById = records.associateBy { it.record.trainId }

            fun match(trainId: String, visited: MutableSet<StablingPointRef>): Boolean {
                for (point in candidates.getValue(trainId)) {
                    if (!visited.add(point.ref)) continue
                    val currentOwner = pointOwners[point.ref]
                    if (currentOwner == null || match(currentOwner, visited)) {
                        pointOwners[point.ref] = trainId
                        return true
                    }
                }
                return false
            }

            records.forEach { runtime ->
                if (!match(runtime.record.trainId, LinkedHashSet())) {
                    return@synchronized DepotResetPlanningResult.Failure(
                        "insufficient compatible StablingPoint capacity for Depot " +
                            resetDepots(runtime).joinToString()
                    )
                }
            }
            val pointByTrain = pointOwners.entries.associate { (point, trainId) -> trainId to point }
            val assignments = records.map { runtime ->
                val ref = requireNotNull(pointByTrain[runtime.record.trainId])
                DepotResetAssignment(
                    runtime.record.trainId,
                    ref,
                    points.getValue(ref).point.destinationId
                )
            }
            check(assignments.size == recordsById.size)
            DepotResetPlanningResult.Planned(DepotResetPlan(depotIds, assignments))
        }

    /** Verify the exact plan can still be committed; this method never mutates state. */
    @MainThread
    fun validateResetPlan(plan: DepotResetPlan): String? = synchronized(lock) {
        validateResetPlanLocked(plan)
    }

    /** Rebuild selected Depot occupancy and rolling-stock lifecycle in one synchronized commit. */
    @MainThread
    fun applyReset(plan: DepotResetPlan): Boolean = synchronized(lock) {
        if (validateResetPlanLocked(plan) != null) return@synchronized false
        val trainIds = plan.assignments.mapTo(LinkedHashSet()) { it.trainId }
        val assignmentIds = trainIds.mapNotNullTo(HashSet()) { rollingStock.getValue(it).assignmentId }
        points.values.forEach { point ->
            if (point.occupantTrainId in trainIds) point.occupantTrainId = null
            if (point.reservedByAssignmentId in assignmentIds) point.reservedByAssignmentId = null
        }
        assignments.entries.removeIf { it.value.trainId in trainIds }
        storageReservations.entries.removeIf { it.value.trainId in trainIds }
        fixedUnitAssignments.entries.removeIf { it.value.trainId in trainIds }
        trainIds.forEach { trainId ->
            val runtime = rollingStock.getValue(trainId)
            runtime.state = RollingStockState.AVAILABLE
            runtime.stablingPointRef = null
            runtime.assignmentId = null
        }
        plan.assignments.forEach { assignment ->
            val point = points.getValue(assignment.stablingPointRef)
            point.occupantTrainId = assignment.trainId
            val runtime = rollingStock.getValue(assignment.trainId)
            runtime.stablingPointRef = assignment.stablingPointRef
        }
        true
    }

    @MainThread
    fun addStablingPoint(
        depotId: DepotId,
        depotDisplayName: String,
        point: DepotStablingPoint
    ) = synchronized(lock) {
        require(point.ref.depotId == depotId)
        check(points[point.ref] == null) { "StablingPoint already registered: ${point.ref}" }
        val current = depots[depotId]
        val updated = if (current == null) {
            Depot(depotId, depotDisplayName, listOf(point.ref))
        } else {
            Depot(current.id, current.displayName, current.stablingPointRefs + point.ref)
        }
        points[point.ref] = PointRuntime(point)
        depots[depotId] = updated
    }

    @MainThread
    fun removeStablingPoint(ref: StablingPointRef): DepotStablingPoint? = synchronized(lock) {
        val runtime = points[ref] ?: return@synchronized null
        check(runtime.occupantTrainId == null && runtime.reservedByAssignmentId == null) {
            "cannot remove occupied or reserved StablingPoint: $ref"
        }
        if (depots[ref.depotId]?.stablingPointRefs?.size == 1) {
            check(rollingStock.values.none { it.record.homeDepotId == ref.depotId } && fixedUnits.values.none { it.homeDepotId == ref.depotId }) {
                "cannot remove the final point of a Depot with registered home stock or units: ${ref.depotId}"
            }
        }
        points.remove(ref)
        depots[ref.depotId]?.let { depot ->
            val remaining = depot.stablingPointRefs.filterNot { it == ref }
            if (remaining.isEmpty()) depots.remove(ref.depotId)
            else depots[ref.depotId] = Depot(depot.id, depot.displayName, remaining)
        }
        runtime.point
    }

    @MainThread
    fun registerRollingStock(record: RollingStockRecord, at: StablingPointRef) = synchronized(lock) {
        require(!formationHoldsTrain(record.trainId)) { "train ID is held by an unresolved formation event" }
        check(rollingStock[record.trainId] == null) { "RollingStock already registered: ${record.trainId}" }
        val point = points[at] ?: throw IllegalArgumentException("StablingPoint not found: $at")
        require(record.homeDepotId in depots) { "RollingStock home Depot not found: ${record.homeDepotId}" }
        require(point.occupantTrainId == null && point.reservedByAssignmentId == null) {
            "StablingPoint is occupied or reserved: $at"
        }
        require(pointAccepts(point.point, record)) { "RollingStock ${record.trainId} does not fit $at" }
        point.occupantTrainId = record.trainId
        rollingStock[record.trainId] = RollingRuntime(record, RollingStockState.AVAILABLE, at)
    }

    /** Restore one complete persisted ledger after validating every reference, without partial mutation. */
    @MainThread
    fun restoreRollingStock(snapshots: List<RollingStockSnapshot>) = synchronized(lock) {
        validateRollingStockRestore(snapshots)
        applyRollingStockRestore(snapshots)
    }

    /** Validate both ledgers against restored Core membership before publishing either one. */
    @MainThread
    fun restoreOperationalLedger(snapshots: List<RollingStockSnapshot>, units: List<FixedEmuUnit>, claims: List<FixedEmuUnitAssignment>, physicalFormations: Map<String, List<String>>, changes: List<FixedFormationChange> = emptyList(), physicalDirections: Map<String, Boolean> = emptyMap(), heldOutputs: List<FixedFormationHeldOutput> = emptyList()) = synchronized(lock) {
        validateRollingStockRestore(snapshots)
        check(formationChanges.isEmpty() && formationRecoveryHolds.isEmpty() && heldFormationOutputs.isEmpty())
        require(changes.map { it.eventId }.distinct().size == changes.size) { "duplicate unresolved formation events" }
        val recoveryHolds = changes.mapIndexed { index, change ->
            validateFormationMembership(change, units)
            require((change.sources + change.outputs.map { it.owner }).none { owner -> changes.take(index).any { changesOverlap(it, owner) } }) { "unresolved formation events overlap" }
            val ledgerOwners = ledgerFormationOwners(change)
            val ledgerTrainIds = ledgerOwners.map { it.trainId }.toSet()
            val ledgerAssignments = ledgerOwners.map { it.assignmentId }.toSet()
            ledgerOwners.forEach { owner ->
                val stock = requireNotNull(snapshots.find { it.record.trainId == owner.trainId }) { "unresolved formation owner metadata is missing" }
                require(stock.state == RollingStockState.IN_SERVICE && stock.assignmentId == owner.assignmentId && stock.stablingPointRef == null &&
                    stock.record.carCount == owner.carIds.size) { "unresolved formation ledger owner is invalid" }
            }
            (change.sources + change.outputs.map { it.owner }).forEach { owner ->
                require(snapshots.none { (it.record.trainId == owner.trainId && it.record.trainId !in ledgerTrainIds) ||
                    (it.assignmentId == owner.assignmentId && it.assignmentId !in ledgerAssignments) }) { "unresolved formation conflicts with unrelated stock" }
            }
            val composition = when {
                change.phase == FixedFormationPhase.SOURCES_HELD && matchesFormationSide(change.sources, change, physicalFormations, physicalDirections) ->
                    RecoveredFormationComposition.UNCHANGED_SOURCES
                matchesFormationSide(change.outputs.map { it.owner }, change, physicalFormations, physicalDirections) ->
                    RecoveredFormationComposition.EXPECTED_OUTPUTS
                else -> throw IllegalArgumentException("unresolved formation event ${change.eventId} does not match its phase and Core composition")
            }
            FixedFormationRecoveryHold(change, composition)
        }
        validateFixedUnitRestore(units, claims, physicalFormations, changes)
        claims.forEach { claim ->
            val stock = requireNotNull(snapshots.find { it.record.trainId == claim.trainId }) { "claimed fixed formation has no rolling stock metadata" }
            require(stock.assignmentId == claim.assignmentId && stock.state != RollingStockState.AVAILABLE) { "unit and rolling stock assignment disagree" }
        }
        val unitCars = units.flatMap { it.carIds }.toSet()
        snapshots.filter { it.assignmentId != null && physicalFormations[it.record.trainId].orEmpty().any(unitCars::contains) }.forEach { stock ->
            require(claims.any { it.trainId == stock.record.trainId && it.assignmentId == stock.assignmentId }) { "assigned rolling stock has no unit assignment" }
        }
        require(heldOutputs.map { it.owner.trainId }.distinct().size == heldOutputs.size) { "duplicate held formation outputs" }
        heldOutputs.forEachIndexed { index, held ->
            val owner = held.owner
            require(changes.none { changesOverlap(it, owner) } && heldOutputs.take(index).none { ownersOverlap(it.owner, owner) }) { "held formation owners overlap" }
            require(physicalFormations[owner.trainId] == owner.carIds && physicalDirections[owner.trainId] == owner.reversed) { "held Core formation changed" }
            val stock = requireNotNull(snapshots.find { it.record.trainId == owner.trainId }) { "held formation metadata missing" }
            require(stock.state == RollingStockState.IN_SERVICE && stock.assignmentId == owner.assignmentId && stock.stablingPointRef == null &&
                stock.record.carCount == owner.carIds.size) { "held formation owner metadata changed" }
            val expected = resolveFixedUnits(owner.carIds, units).map { FixedEmuUnitAssignment(it.id, owner.trainId, owner.assignmentId) }.toSet()
            require(claims.filter { it.trainId == owner.trainId }.toSet() == expected) { "held formation unit ownership changed" }
        }
        applyFixedUnitRestore(units, claims)
        applyRollingStockRestore(snapshots)
        heldOutputs.forEach { heldFormationOutputs[it.owner.trainId] = it; recoveredFormationOutputIds += it.owner.trainId }
        changes.forEach { formationChanges[it.eventId] = it }
        recoveryHolds.forEach { formationRecoveryHolds[it.change.eventId] = it }
    }

    private fun validateRollingStockRestore(snapshots: List<RollingStockSnapshot>) {
        check(rollingStock.isEmpty()) { "RollingStock ledger must be empty before restore" }
        require(snapshots.map { it.record.trainId }.distinct().size == snapshots.size) {
            "RollingStock snapshot contains duplicate train IDs"
        }
        val occupiedPoints = snapshots.mapNotNull { it.stablingPointRef }
        require(occupiedPoints.distinct().size == occupiedPoints.size) {
            "RollingStock snapshot assigns one StablingPoint more than once"
        }
        val assignmentIds = snapshots.mapNotNull { it.assignmentId }
        require(assignmentIds.distinct().size == assignmentIds.size) {
            "RollingStock snapshot assigns one assignment ID more than once"
        }
        snapshots.forEach { snapshot ->
            require(snapshot.record.homeDepotId in depots) {
                "RollingStock home Depot not found: ${snapshot.record.homeDepotId}"
            }
            snapshot.assignmentId?.let { requireStableId("assignmentId", it) }
            val point = snapshot.stablingPointRef?.let { ref ->
                points[ref] ?: throw IllegalArgumentException("StablingPoint not found: $ref")
            }
            when (snapshot.state) {
                RollingStockState.AVAILABLE -> require(
                    snapshot.stablingPointRef != null && snapshot.assignmentId == null
                ) { "AVAILABLE RollingStock must occupy a StablingPoint without an assignment" }
                RollingStockState.RESERVED -> require(
                    snapshot.stablingPointRef != null && snapshot.assignmentId != null
                ) { "RESERVED RollingStock must occupy a StablingPoint with an assignment" }
                RollingStockState.IN_SERVICE -> require(snapshot.assignmentId != null) {
                    "IN_SERVICE RollingStock must retain its assignment"
                }
            }
            if (point != null) {
                require(point.occupantTrainId == null && point.reservedByAssignmentId == null)
                require(pointAccepts(point.point, snapshot.record))
            }
        }
    }

    private fun applyRollingStockRestore(snapshots: List<RollingStockSnapshot>) {
        snapshots.forEach { snapshot ->
            snapshot.stablingPointRef?.let { ref ->
                points.getValue(ref).apply {
                    occupantTrainId = snapshot.record.trainId
                    reservedByAssignmentId = snapshot.assignmentId
                }
            }
            rollingStock[snapshot.record.trainId] = RollingRuntime(
                snapshot.record,
                snapshot.state,
                snapshot.stablingPointRef,
                snapshot.assignmentId
            )
        }
    }

    @MainThread
    fun unregisterRollingStock(trainId: String): RollingStockRecord? = synchronized(lock) {
        val runtime = rollingStock[trainId] ?: return@synchronized null
        check(runtime.state == RollingStockState.AVAILABLE && runtime.assignmentId == null) {
            "cannot unregister assigned RollingStock: $trainId"
        }
        runtime.stablingPointRef?.let { ref ->
            points[ref]?.takeIf { it.occupantTrainId == trainId }?.occupantTrainId = null
        }
        rollingStock.remove(trainId)
        runtime.record
    }

    /**
     * Reserve an exact train only after the dispatcher has selected it from physical occupancy.
     * DepotStore is not an availability/selection authority; this updates optional rolling-stock
     * lifecycle metadata for an already registered train.
     */
    @MainThread
    fun reservePhysicallySelected(
        trainId: String,
        pointRef: StablingPointRef,
        selector: DepotTrainSelector,
        assignmentId: String,
        physicalCarIds: List<String>? = null
    ): DepotTrainAllocation? = synchronized(lock) {
        val claims = prepareFixedClaims(trainId, physicalCarIds, assignmentId)
        if (claims.isNotEmpty()) require(rollingStock[trainId]?.let { it.state == RollingStockState.AVAILABLE && it.assignmentId == null } == true) {
            "registered fixed formation is not AVAILABLE rolling stock"
        }
        require(!assignmentIsOwned(assignmentId)) { "assignment is already owned: $assignmentId" }
        val runtime = rollingStock[trainId] ?: return@synchronized null
        val point = points[pointRef] ?: return@synchronized null
        if (runtime.state != RollingStockState.AVAILABLE || runtime.assignmentId != null ||
            pointRef.depotId != selector.depotId ||
            runtime.stablingPointRef != pointRef || point.occupantTrainId != trainId ||
            !selector.matches(runtime.record)
        ) return@synchronized null
        val allocation = DepotTrainAllocation(assignmentId, trainId, pointRef, selector)
        runtime.state = RollingStockState.RESERVED
        runtime.assignmentId = assignmentId
        point.reservedByAssignmentId = assignmentId
        assignments[assignmentId] = allocation
        claims.forEach { fixedUnitAssignments[it.unitId] = it }
        allocation
    }

    @MainThread
    fun allocate(
        selector: DepotTrainSelector,
        assignmentId: String,
        stableSeed: String,
        physicalCarsByTrain: Map<String, List<String>> = emptyMap()
    ): DepotTrainAllocationResult = synchronized(lock) {
        requireStableId("assignmentId", assignmentId)
        if (assignmentIsOwned(assignmentId)) {
            return@synchronized DepotTrainAllocationResult.AssignmentConflict(assignmentId)
        }
        val availableInDepot = rollingStock.values.count {
            it.stablingPointRef?.depotId == selector.depotId && it.state == RollingStockState.AVAILABLE
        }
        val candidates = rollingStock.values.asSequence()
            .filter { it.state == RollingStockState.AVAILABLE &&
                it.stablingPointRef?.depotId == selector.depotId && selector.matches(it.record) }
            .filter { runtime ->
                val ref = runtime.stablingPointRef ?: return@filter false
                val point = points[ref] ?: return@filter false
                point.occupantTrainId == runtime.record.trainId &&
                    point.reservedByAssignmentId == null &&
                    pointAccepts(point.point, runtime.record)
            }
            .sortedBy { it.record.trainId }
            .toList()
        if (candidates.isEmpty()) {
            return@synchronized DepotTrainAllocationResult.NoMatchingTrain(
                selector.depotId,
                selector,
                availableInDepot
            )
        }
        val selected = candidates[stableSelectionIndex(stableSeed, candidates.size)]
        val claims = prepareFixedClaims(selected.record.trainId, physicalCarsByTrain[selected.record.trainId], assignmentId)
        val pointRef = requireNotNull(selected.stablingPointRef)
        val allocation = DepotTrainAllocation(
            assignmentId,
            selected.record.trainId,
            pointRef,
            selector
        )
        selected.state = RollingStockState.RESERVED
        selected.assignmentId = assignmentId
        points.getValue(pointRef).reservedByAssignmentId = assignmentId
        assignments[assignmentId] = allocation
        claims.forEach { fixedUnitAssignments[it.unitId] = it }
        DepotTrainAllocationResult.Allocated(allocation)
    }

    /** Release the occupied departure point only after the train tail has entered its outbound route. */
    @MainThread
    fun releaseDeparture(allocation: DepotTrainAllocation): Boolean = synchronized(lock) {
        if (assignments[allocation.assignmentId] != allocation) return@synchronized false
        val runtime = rollingStock[allocation.trainId] ?: return@synchronized false
        if (runtime.state != RollingStockState.IN_SERVICE ||
            runtime.assignmentId != allocation.assignmentId ||
            runtime.stablingPointRef != allocation.stablingPointRef
        ) return@synchronized false
        val point = points[allocation.stablingPointRef] ?: return@synchronized false
        if (point.occupantTrainId != allocation.trainId ||
            point.reservedByAssignmentId != allocation.assignmentId
        ) return@synchronized false
        point.occupantTrainId = null
        point.reservedByAssignmentId = null
        runtime.stablingPointRef = null
        true
    }

    @MainThread
    fun reserveStorage(
        trainId: String,
        depotId: DepotId,
        requestedPointIds: Set<String>,
        assignmentId: String,
        stableSeed: String
    ): DepotStorageReservationResult = synchronized(lock) {
        if (formationHoldsTrain(trainId)) return@synchronized DepotStorageReservationResult.RollingStockUnavailable(trainId, "formation is held by an unresolved structural event")
        val runtime = rollingStock[trainId]
            ?: return@synchronized DepotStorageReservationResult.RollingStockUnavailable(
                trainId,
                "RollingStock is not registered"
            )
        if (runtime.state != RollingStockState.IN_SERVICE || runtime.assignmentId != assignmentId) {
            return@synchronized DepotStorageReservationResult.RollingStockUnavailable(
                trainId,
                "RollingStock is not IN_SERVICE for assignment $assignmentId"
            )
        }
        storageReservations[assignmentId]?.let { existing ->
            return@synchronized if (existing.trainId == trainId &&
                existing.stablingPointRef.depotId == depotId &&
                (requestedPointIds.isEmpty() || existing.stablingPointRef.pointId in requestedPointIds)
            ) {
                DepotStorageReservationResult.Reserved(existing)
            } else {
                DepotStorageReservationResult.RollingStockUnavailable(
                    trainId,
                    "assignment already has another storage reservation"
                )
            }
        }
        val candidates = points.values.asSequence()
            .filter { it.point.ref.depotId == depotId }
            .filter { requestedPointIds.isEmpty() || it.point.ref.pointId in requestedPointIds }
            .filter { it.occupantTrainId == null && it.reservedByAssignmentId == null }
            .filter { pointAccepts(it.point, runtime.record) }
            .sortedBy { it.point.ref.pointId }
            .toList()
        if (candidates.isEmpty()) {
            return@synchronized DepotStorageReservationResult.NoCompatiblePoint(
                depotId,
                requestedPointIds.toSet()
            )
        }
        val selected = candidates[stableSelectionIndex(stableSeed, candidates.size)]
        val reservation = DepotStorageReservation(assignmentId, trainId, selected.point.ref)
        selected.reservedByAssignmentId = assignmentId
        storageReservations[assignmentId] = reservation
        DepotStorageReservationResult.Reserved(reservation)
    }

    @MainThread
    fun cancelStorage(reservation: DepotStorageReservation): Boolean = synchronized(lock) {
        if (storageReservations[reservation.assignmentId] != reservation) return@synchronized false
        storageReservations.remove(reservation.assignmentId)
        points[reservation.stablingPointRef]?.let { point ->
            if (point.reservedByAssignmentId == reservation.assignmentId &&
                point.occupantTrainId == null
            ) {
                point.reservedByAssignmentId = null
            }
        }
        true
    }

    /** Atomic reservation-to-occupancy handoff after Dispatcher verifies the full train envelope. */
    @MainThread
    fun completeStorage(reservation: DepotStorageReservation): Boolean = synchronized(lock) {
        if (storageReservations[reservation.assignmentId] != reservation) return@synchronized false
        if (formationHoldsTrain(reservation.trainId)) return@synchronized false
        val runtime = rollingStock[reservation.trainId] ?: return@synchronized false
        if (runtime.state != RollingStockState.IN_SERVICE ||
            runtime.assignmentId != reservation.assignmentId ||
            runtime.stablingPointRef != null
        ) return@synchronized false
        val point = points[reservation.stablingPointRef] ?: return@synchronized false
        if (point.occupantTrainId != null ||
            point.reservedByAssignmentId != reservation.assignmentId ||
            !pointAccepts(point.point, runtime.record)
        ) return@synchronized false
        point.occupantTrainId = reservation.trainId
        point.reservedByAssignmentId = null
        runtime.state = RollingStockState.AVAILABLE
        runtime.stablingPointRef = reservation.stablingPointRef
        runtime.assignmentId = null
        storageReservations.remove(reservation.assignmentId)
        assignments.remove(reservation.assignmentId)
        fixedUnitAssignments.entries.removeIf { it.value.trainId == reservation.trainId && it.value.assignmentId == reservation.assignmentId }
        true
    }

    @MainThread
    fun confirmInService(allocation: DepotTrainAllocation): Boolean = synchronized(lock) {
        if (assignments[allocation.assignmentId] != allocation) return@synchronized false
        val runtime = rollingStock[allocation.trainId] ?: return@synchronized false
        if (runtime.state != RollingStockState.RESERVED ||
            runtime.assignmentId != allocation.assignmentId
        ) return@synchronized false
        runtime.state = RollingStockState.IN_SERVICE
        true
    }

    @MainThread
    fun cancelReservation(allocation: DepotTrainAllocation): Boolean = synchronized(lock) {
        if (assignments[allocation.assignmentId] != allocation) return@synchronized false
        val runtime = rollingStock[allocation.trainId] ?: return@synchronized false
        if (runtime.state != RollingStockState.RESERVED || runtime.assignmentId != allocation.assignmentId) return@synchronized false
        assignments.remove(allocation.assignmentId)
        fixedUnitAssignments.entries.removeIf { it.value.trainId == allocation.trainId && it.value.assignmentId == allocation.assignmentId }
        runtime.state = RollingStockState.AVAILABLE
        runtime.assignmentId = null
        points[allocation.stablingPointRef]?.let { point ->
            if (point.reservedByAssignmentId == allocation.assignmentId) {
                point.reservedByAssignmentId = null
            }
        }
        true
    }

    fun depot(id: DepotId): Depot? = synchronized(lock) { depots[id] }

    /** Deterministic immutable snapshot for discovery APIs such as WebUI metadata. */
    fun allDepots(): List<Depot> = synchronized(lock) { depots.values.sortedBy { it.id.value } }

    fun stablingPoint(ref: StablingPointRef): StablingPointSnapshot? = synchronized(lock) {
        points[ref]?.let { StablingPointSnapshot(it.point, it.occupantTrainId, it.reservedByAssignmentId) }
    }

    fun stablingPoints(depotId: DepotId): List<StablingPointSnapshot> = synchronized(lock) {
        depots[depotId]?.stablingPointRefs.orEmpty().mapNotNull { ref ->
            points[ref]?.let { StablingPointSnapshot(it.point, it.occupantTrainId, it.reservedByAssignmentId) }
        }
    }

    /** Stabling points that can hold at least one registered record matching [selector]. */
    fun compatibleStablingPoints(selector: DepotTrainSelector): List<StablingPointSnapshot> =
        synchronized(lock) {
            val matchingRecords = rollingStock.values.asSequence()
                .map { it.record }
                .filter(selector::matches)
                .toList()
            points.values.asSequence()
                .filter { it.point.ref.depotId == selector.depotId }
                .filter { point -> matchingRecords.any { pointAccepts(point.point, it) } }
                .sortedBy { it.point.ref.pointId }
                .map {
                    StablingPointSnapshot(
                        it.point,
                        it.occupantTrainId,
                        it.reservedByAssignmentId
                    )
                }
                .toList()
        }

    fun rollingStock(trainId: String): RollingStockSnapshot? = synchronized(lock) {
        rollingStock[trainId]?.snapshot()
    }

    fun allRollingStock(): List<RollingStockSnapshot> = synchronized(lock) {
        rollingStock.values.sortedBy { it.record.trainId }.map { it.snapshot() }
    }

    private fun validateResetPlanLocked(plan: DepotResetPlan): String? {
        if (plan.assignments.any { formationHoldsTrain(it.trainId) }) return "Reset intersects an unresolved formation event"
        val expectedTrainIds = rollingStock.values.asSequence()
            .filter { resetDepots(it).any(plan.depotIds::contains) }
            .map { it.record.trainId }
            .toSet()
        val assignedTrainIds = plan.assignments.map { it.trainId }.toSet()
        if (assignedTrainIds != expectedTrainIds) {
            return "Reset plan RollingStock set changed"
        }
        if (plan.assignments.map { it.stablingPointRef }.distinct().size != plan.assignments.size) {
            return "Reset plan assigns one StablingPoint more than once"
        }
        for (assignment in plan.assignments) {
            val runtime = rollingStock[assignment.trainId]
                ?: return "RollingStock disappeared: ${assignment.trainId}"
            val point = points[assignment.stablingPointRef]
                ?: return "StablingPoint disappeared: ${assignment.stablingPointRef}"
            if (assignment.stablingPointRef.depotId !in resetDepots(runtime) ||
                !pointAccepts(point.point, runtime.record)
            ) {
                return "RollingStock ${assignment.trainId} no longer fits ${assignment.stablingPointRef}"
            }
            if (point.point.destinationId != assignment.destinationId) {
                return "StablingPoint Destination changed: ${assignment.stablingPointRef}"
            }
        }
        return null
    }

    private fun resetDepots(runtime: RollingRuntime): Set<DepotId> = buildSet {
        runtime.stablingPointRef?.let { add(it.depotId) }
        runtime.assignmentId?.let(storageReservations::get)?.let { add(it.stablingPointRef.depotId) }
    }

    private fun RollingRuntime.snapshot(): RollingStockSnapshot = RollingStockSnapshot(
        record,
        state,
        stablingPointRef,
        assignmentId
    )

    private fun pointAccepts(point: DepotStablingPoint, record: RollingStockRecord): Boolean =
        (point.usableLengthMeters == null || record.lengthMeters <= point.usableLengthMeters) &&
            (point.allowedTags.isEmpty() || record.tags.any { it in point.allowedTags })
}

private fun stableSelectionIndex(seed: String, size: Int): Int {
    require(size > 0)
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(seed.toByteArray(StandardCharsets.UTF_8))
    val value = ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long and Long.MAX_VALUE
    return (value % size.toLong()).toInt()
}

internal fun requireStableId(label: String, value: String) {
    require(value.isNotBlank() && value == value.trim() && value.none(Char::isISOControl)) {
        "$label must be stable non-blank text"
    }
}

private fun requireTags(label: String, values: Set<String>) {
    values.forEach { value -> requireStableId(label, value) }
}
