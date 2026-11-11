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

    fun matches(facts: TrainCandidateFacts?, carCount: Int): Boolean =
        facts?.tags.orEmpty().containsAll(requiredTags) &&
            (minimumCars == null || carCount >= minimumCars) &&
            (maximumCars == null || carCount <= maximumCars) &&
            (minimumSpeedMetersPerSecond == null ||
                facts?.maximumSpeedMetersPerSecond?.let { it >= minimumSpeedMetersPerSecond } == true) &&
            facts?.capabilities.orEmpty().containsAll(requiredCapabilities)
}

data class StablingPointSnapshot(val point: DepotStablingPoint)

/** Exact operation identity for the Dispatcher-owned reservation, not an inventory allocation. */
data class DepotStorageReservation(val assignmentId: String, val trainId: String, val stablingPointRef: StablingPointRef)

/** Infrastructure definitions and exact task/formation claims. Core owns physical membership. */
class DepotStore(private val fixedUnitsProvider: () -> Collection<FixedEmuUnit> = { emptyList() }) {
    private val lock = Any()
    private val depots = LinkedHashMap<DepotId, Depot>()
    private val points = LinkedHashMap<StablingPointRef, DepotStablingPoint>()
    private val controlCompatibility = LinkedHashMap<String, String>()
    private val fixedUnitAssignments = LinkedHashMap<String, FixedEmuUnitAssignment>()
    private val formationChanges = LinkedHashMap<String, FixedFormationChange>()
    private val formationRecoveryHolds = LinkedHashMap<String, FixedFormationRecoveryHold>()
    private val heldFormationOutputs = LinkedHashMap<String, FixedFormationHeldOutput>()
    private val recoveredFormationOutputIds = linkedSetOf<String>()
    private val fixedUnits: Map<String, FixedEmuUnit> get() {
        val values = fixedUnitsProvider().map { unit ->
            FixedEmuUnit(unit.id, unit.carIds, controlCompatibility[unit.id] ?: unit.controlCompatibilityKey)
        }
        require(values.map { it.id }.distinct().size == values.size) { "duplicate Core unit IDs" }
        require(values.flatMap { it.carIds }.let { it.distinct().size == it.size }) { "Core unit cars overlap" }
        return values.associateBy { it.id }
    }

    internal fun operationalSnapshot(): DepotOperationalSnapshot = synchronized(lock) {
        DepotOperationalSnapshot(fixedUnits.values.toList(), points.values.map(::StablingPointSnapshot),
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
            val expected = resolveFixedUnits(source.carIds).map { FixedEmuUnitAssignment(it.id, source.trainId, source.assignmentId) }.toSet()
            require(fixedUnitAssignments.values.filter { it.trainId == source.trainId }.toSet() == expected) { "source unit ownership changed" }
        }
        change.outputs.forEach { output ->
            require(output.owner.trainId in sourceIds || output.owner.trainId !in physicalFormations) { "output train ID is already occupied" }
            require(output.owner.assignmentId in sourceAssignments ||
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
    fun commitFixedFormationChange(change: FixedFormationChange, physicalFormations: Map<String, List<String>>, physicalDirections: Map<String, Boolean>): FixedFormationChange = synchronized(lock) {
        require(formationChanges[change.eventId] === change) { "formation event token is stale" }
        require(change.phase == FixedFormationPhase.SOURCES_HELD) { "output ownership is already committed" }
        check(change.eventId !in formationRecoveryHolds) { "restored formation event requires explicit recovery" }
        require(matchesFormationSide(change.outputs.map { it.owner }, change, physicalFormations, physicalDirections)) { "published Core outputs do not match the prepared change" }
        val newClaims = change.outputs.flatMap { output ->
            resolveFixedUnits(output.owner.carIds).map { FixedEmuUnitAssignment(it.id, output.owner.trainId, output.owner.assignmentId) }
        }
        val sourceIds = change.sources.map { it.trainId }.toSet()
        fixedUnitAssignments.entries.removeIf { it.value.trainId in sourceIds }
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
        fixedUnitAssignments.values.any { it.assignmentId == assignmentId } ||
            formationChanges.values.any { change -> (change.sources + change.outputs.map { it.owner }).any { it.assignmentId == assignmentId } }

    private fun formationHoldsTrain(trainId: String): Boolean = trainId in heldFormationOutputs || formationChanges.values.any { change ->
        change.sources.any { it.trainId == trainId } || change.outputs.any { it.owner.trainId == trainId }
    }

    private fun validateFormationMembership(change: FixedFormationChange, units: Collection<FixedEmuUnit>) {
        (change.sources + change.outputs.map { it.owner }).forEach { resolveFixedUnits(it.carIds, units) }
        if (change.kind == FixedFormationChangeKind.COUPLE) {
            requireCompatibleControls(resolveFixedUnits(change.outputs.single().owner.carIds, units))
        }
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

    /** Optional operating declaration; Core remains the only owner of unit membership. */
    @MainThread
    fun registerUnitControlCompatibility(unitId: String, key: String) = synchronized(lock) {
        requireStableId("controlCompatibilityKey", key)
        require(unitId in fixedUnits) { "Core unit does not exist: $unitId" }
        require(fixedUnitAssignments[unitId] == null) { "unit is assigned" }
        require(unitId !in controlCompatibility) { "unit control compatibility is already declared" }
        controlCompatibility[unitId] = key
    }

    fun unitControlCompatibility(): Map<String, String> = synchronized(lock) {
        java.util.Map.copyOf(controlCompatibility.filterKeys { it in fixedUnits })
    }

    /** Unknown or conflicting declarations cannot authorize joining physical units. */
    fun validateFixedUnitCoupling(physicalCarIds: List<String>) = synchronized(lock) {
        requireCompatibleControls(resolveFixedUnits(physicalCarIds))
    }

    private fun requireCompatibleControls(units: List<FixedEmuUnit>) {
        require(units.all { it.controlCompatibilityKey != null }) { "fixed unit control compatibility is unproven" }
        require(units.map { it.controlCompatibilityKey }.distinct().size == 1) { "fixed units have incompatible declared control families" }
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
    fun restoreFixedUnitAssignments(claims: List<FixedEmuUnitAssignment>, physicalFormations: Map<String, List<String>>) = synchronized(lock) {
        validateFixedUnitRestore(fixedUnits.values.toList(), claims, physicalFormations)
        applyFixedUnitRestore(claims)
    }

    private fun ledgerFormationOwners(change: FixedFormationChange): List<FixedFormationOwner> = when (change.phase) {
        FixedFormationPhase.SOURCES_HELD -> change.sources
        FixedFormationPhase.OUTPUT_OWNERS_COMMITTED -> change.outputs.map { it.owner }
    }

    private fun validateFixedUnitRestore(units: List<FixedEmuUnit>, claims: List<FixedEmuUnitAssignment>, physicalFormations: Map<String, List<String>>, changes: List<FixedFormationChange> = emptyList()) {
        check(fixedUnitAssignments.isEmpty()) { "fixed unit claims must be empty before restore" }
        require(units.map { it.id }.distinct().size == units.size) { "duplicate fixed unit IDs" }
        val cars = units.flatMap { it.carIds }
        require(cars.distinct().size == cars.size) { "a car cannot belong to two fixed units" }
        val actualCars = physicalFormations.values.flatten()
        require(actualCars.distinct().size == actualCars.size) { "a car cannot belong to two physical formations" }
        require(units.all { unit -> physicalFormations.values.count { containsUnit(it, unit.carIds) } == 1 }) { "Core unit physical membership is invalid" }
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

    private fun applyFixedUnitRestore(claims: List<FixedEmuUnitAssignment>) {
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
        return claims
    }

    private fun resolveFixedUnits(physicalCarIds: List<String>, definitions: Collection<FixedEmuUnit> = fixedUnits.values): List<FixedEmuUnit> {
        require(physicalCarIds.isNotEmpty() && physicalCarIds.distinct().size == physicalCarIds.size) { "formation cars must be non-empty and unique" }
        val byCar = definitions.flatMap { unit -> unit.carIds.map { it to unit } }.toMap()
        val units = ArrayList<FixedEmuUnit>()
        var cursor = 0
        while (cursor < physicalCarIds.size) {
            val unit = requireNotNull(byCar[physicalCarIds[cursor]]) { "physical car has no Core unit: ${physicalCarIds[cursor]}" }
            val end = cursor + unit.carIds.size
            require(end <= physicalCarIds.size && physicalCarIds.subList(cursor, end).let { it == unit.carIds || it == unit.carIds.asReversed() }) { "formation contains an incomplete or reordered fixed unit: ${unit.id}" }
            units += unit
            cursor = end
        }
        require(units.map { it.id }.distinct().size == units.size) { "formation contains a unit more than once" }
        return units
    }

    private fun containsUnit(physicalCarIds: List<String>, unitCars: List<String>): Boolean =
        physicalCarIds.windowed(unitCars.size).any { it == unitCars || it == unitCars.asReversed() }

    @MainThread
    fun restoreOperationalLedger(claims: List<FixedEmuUnitAssignment>, physicalFormations: Map<String, List<String>>,
        changes: List<FixedFormationChange> = emptyList(), physicalDirections: Map<String, Boolean> = emptyMap(),
        heldOutputs: List<FixedFormationHeldOutput> = emptyList(), controlCompatibility: Map<String, String> = emptyMap()
    ) = synchronized(lock) {
        require(this.controlCompatibility.isEmpty()) { "unit control declarations already restored" }
        check(formationChanges.isEmpty() && formationRecoveryHolds.isEmpty() && heldFormationOutputs.isEmpty())
        val sourceUnits = fixedUnits
        controlCompatibility.forEach { (id, key) -> require(id in sourceUnits); requireStableId("controlCompatibilityKey", key) }
        val units = sourceUnits.values.map { FixedEmuUnit(it.id, it.carIds, controlCompatibility[it.id] ?: it.controlCompatibilityKey) }
        require(changes.map { it.eventId }.distinct().size == changes.size)
        val recovery = changes.mapIndexed { index, change ->
            validateFormationMembership(change, units)
            require((change.sources + change.outputs.map { it.owner }).none { owner ->
                changes.take(index).any { changesOverlap(it, owner) } }) { "unresolved formation events overlap" }
            val composition = when {
                change.phase == FixedFormationPhase.SOURCES_HELD && matchesFormationSide(change.sources, change, physicalFormations, physicalDirections) ->
                    RecoveredFormationComposition.UNCHANGED_SOURCES
                matchesFormationSide(change.outputs.map { it.owner }, change, physicalFormations, physicalDirections) ->
                    RecoveredFormationComposition.EXPECTED_OUTPUTS
                else -> throw IllegalArgumentException("unresolved formation event does not match Core composition")
            }
            FixedFormationRecoveryHold(change, composition)
        }
        validateFixedUnitRestore(units, claims, physicalFormations, changes)
        require(heldOutputs.map { it.owner.trainId }.distinct().size == heldOutputs.size)
        heldOutputs.forEachIndexed { index, held ->
            val owner = held.owner
            require(changes.none { changesOverlap(it, owner) } && heldOutputs.take(index).none { ownersOverlap(it.owner, owner) })
            require(physicalFormations[owner.trainId] == owner.carIds && physicalDirections[owner.trainId] == owner.reversed)
            val expected = resolveFixedUnits(owner.carIds, units).map { FixedEmuUnitAssignment(it.id, owner.trainId, owner.assignmentId) }.toSet()
            require(claims.filter { it.trainId == owner.trainId }.toSet() == expected) { "held formation unit ownership changed" }
        }
        this.controlCompatibility.putAll(controlCompatibility)
        applyFixedUnitRestore(claims)
        heldOutputs.forEach { heldFormationOutputs[it.owner.trainId] = it; recoveredFormationOutputIds += it.owner.trainId }
        changes.forEach { formationChanges[it.eventId] = it }
        recovery.forEach { formationRecoveryHolds[it.change.eventId] = it }
    }

    @MainThread
    fun addStablingPoint(depotId: DepotId, depotDisplayName: String, point: DepotStablingPoint) = synchronized(lock) {
        require(point.ref.depotId == depotId)
        check(points[point.ref] == null) { "StablingPoint already registered: ${point.ref}" }
        val current = depots[depotId]
        depots[depotId] = Depot(depotId, current?.displayName ?: depotDisplayName,
            current?.stablingPointRefs.orEmpty() + point.ref)
        points[point.ref] = point
    }

    @MainThread
    fun removeStablingPoint(ref: StablingPointRef): DepotStablingPoint? = synchronized(lock) {
        val point = points.remove(ref) ?: return@synchronized null
        depots[ref.depotId]?.let { depot ->
            val remaining = depot.stablingPointRefs.filterNot { it == ref }
            if (remaining.isEmpty()) depots.remove(ref.depotId)
            else depots[ref.depotId] = Depot(depot.id, depot.displayName, remaining)
        }
        point
    }

    fun depot(id: DepotId): Depot? = synchronized(lock) { depots[id] }
    fun allDepots(): List<Depot> = synchronized(lock) { depots.values.sortedBy { it.id.value } }
    fun stablingPoint(ref: StablingPointRef): StablingPointSnapshot? = synchronized(lock) {
        points[ref]?.let(::StablingPointSnapshot)
    }
    fun stablingPoints(depotId: DepotId): List<StablingPointSnapshot> = synchronized(lock) {
        depots[depotId]?.stablingPointRefs.orEmpty().mapNotNull { points[it]?.let(::StablingPointSnapshot) }
    }
}

internal fun requireStableId(label: String, value: String) {
    require(value.isNotBlank() && value == value.trim() && value.none(Char::isISOControl)) {
        "$label must be stable non-blank text"
    }
}

internal fun requireTags(label: String, values: Set<String>) {
    values.forEach { value -> requireStableId(label, value) }
}
