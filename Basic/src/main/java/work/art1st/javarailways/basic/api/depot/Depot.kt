package work.art1st.javarailways.basic.api.depot

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
    val modelPackageId: String,
    val tags: Set<String>,
    val carCount: Int,
    val lengthMeters: Double,
    val maximumSpeedMetersPerSecond: Double,
    val capabilities: Set<String>
) {
    init {
        requireStableId("trainId", trainId)
        requireStableId("modelPackageId", modelPackageId)
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
        record.homeDepotId == depotId &&
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

    /** Build a deterministic full matching without mutating occupancy or rolling-stock state. */
    @MainThread
    fun planReset(requestedDepotIds: Set<DepotId>? = null): DepotResetPlanningResult =
        synchronized(lock) {
            val depotIds = (requestedDepotIds ?: rollingStock.values
                .map { it.record.homeDepotId }
                .toSet()).toSet()
            if (requestedDepotIds != null) {
                requestedDepotIds.firstOrNull { it !in depots }?.let {
                    return@synchronized DepotResetPlanningResult.Failure("Depot not found: $it")
                }
            }
            val records = rollingStock.values
                .filter { it.record.homeDepotId in depotIds }
                .sortedBy { it.record.trainId }
            val candidates = records.associate { runtime ->
                runtime.record.trainId to points.values.asSequence()
                    .filter { it.point.ref.depotId == runtime.record.homeDepotId }
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
                            runtime.record.homeDepotId
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
        points.values.filter { it.point.ref.depotId in plan.depotIds }.forEach { point ->
            point.occupantTrainId = null
            point.reservedByAssignmentId = null
        }
        assignments.entries.removeIf { it.value.trainId in trainIds }
        storageReservations.entries.removeIf { it.value.trainId in trainIds }
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
        check(rollingStock[record.trainId] == null) { "RollingStock already registered: ${record.trainId}" }
        val point = points[at] ?: throw IllegalArgumentException("StablingPoint not found: $at")
        require(at.depotId == record.homeDepotId) { "initial point must belong to home depot" }
        require(point.occupantTrainId == null && point.reservedByAssignmentId == null) {
            "StablingPoint is occupied or reserved: $at"
        }
        require(pointAccepts(point.point, record)) { "RollingStock ${record.trainId} does not fit $at" }
        point.occupantTrainId = record.trainId
        rollingStock[record.trainId] = RollingRuntime(record, RollingStockState.AVAILABLE, at)
    }

    /** Restore a persisted logical record without recreating transient allocation/reservation objects. */
    @MainThread
    fun restoreRollingStock(snapshot: RollingStockSnapshot) = synchronized(lock) {
        check(rollingStock[snapshot.record.trainId] == null) {
            "RollingStock already registered: ${snapshot.record.trainId}"
        }
        val point = snapshot.stablingPointRef?.let { ref ->
            points[ref] ?: throw IllegalArgumentException("StablingPoint not found: $ref")
        }
        require(snapshot.stablingPointRef == null ||
            snapshot.stablingPointRef.depotId == snapshot.record.homeDepotId)
        if (point != null) {
            require(point.occupantTrainId == null && point.reservedByAssignmentId == null)
            require(pointAccepts(point.point, snapshot.record))
            point.occupantTrainId = snapshot.record.trainId
            point.reservedByAssignmentId = snapshot.assignmentId
        }
        rollingStock[snapshot.record.trainId] = RollingRuntime(
            snapshot.record,
            snapshot.state,
            snapshot.stablingPointRef,
            snapshot.assignmentId
        )
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
     * DepotStore is not an availability/selection authority; this only preserves optional legacy
     * rolling-stock lifecycle metadata for an already registered train.
     */
    @MainThread
    fun reservePhysicallySelected(
        trainId: String,
        pointRef: StablingPointRef,
        selector: DepotTrainSelector,
        assignmentId: String
    ): DepotTrainAllocation? = synchronized(lock) {
        if (assignments[assignmentId] != null) return@synchronized null
        val runtime = rollingStock[trainId] ?: return@synchronized null
        val point = points[pointRef] ?: return@synchronized null
        if (runtime.state != RollingStockState.AVAILABLE || runtime.assignmentId != null ||
            runtime.stablingPointRef != pointRef || point.occupantTrainId != trainId ||
            !selector.matches(runtime.record)
        ) return@synchronized null
        val allocation = DepotTrainAllocation(assignmentId, trainId, pointRef, selector)
        runtime.state = RollingStockState.RESERVED
        runtime.assignmentId = assignmentId
        point.reservedByAssignmentId = assignmentId
        assignments[assignmentId] = allocation
        allocation
    }

    @MainThread
    fun allocate(
        selector: DepotTrainSelector,
        assignmentId: String,
        stableSeed: String
    ): DepotTrainAllocationResult = synchronized(lock) {
        requireStableId("assignmentId", assignmentId)
        if (assignments.containsKey(assignmentId)) {
            return@synchronized DepotTrainAllocationResult.AssignmentConflict(assignmentId)
        }
        val availableInDepot = rollingStock.values.count {
            it.record.homeDepotId == selector.depotId && it.state == RollingStockState.AVAILABLE
        }
        val candidates = rollingStock.values.asSequence()
            .filter { it.state == RollingStockState.AVAILABLE && selector.matches(it.record) }
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
        val runtime = rollingStock[trainId]
            ?: return@synchronized DepotStorageReservationResult.RollingStockUnavailable(
                trainId,
                "RollingStock is not registered"
            )
        if (runtime.homeAssignmentMismatch(depotId, assignmentId)) {
            return@synchronized DepotStorageReservationResult.RollingStockUnavailable(
                trainId,
                "RollingStock is not IN_SERVICE for assignment $assignmentId in its home Depot"
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
        if (storageReservations.remove(reservation.assignmentId) != reservation) {
            return@synchronized false
        }
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
        val allocation = assignments[reservation.assignmentId] ?: return@synchronized false
        if (allocation.trainId != reservation.trainId) return@synchronized false
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
        if (assignments.remove(allocation.assignmentId) != allocation) return@synchronized false
        val runtime = rollingStock[allocation.trainId] ?: return@synchronized false
        if (runtime.state != RollingStockState.RESERVED) return@synchronized false
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
        val expectedTrainIds = rollingStock.values.asSequence()
            .filter { it.record.homeDepotId in plan.depotIds }
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
            if (runtime.record.homeDepotId != assignment.stablingPointRef.depotId ||
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

    private fun RollingRuntime.homeAssignmentMismatch(
        depotId: DepotId,
        expectedAssignmentId: String
    ): Boolean = record.homeDepotId != depotId ||
        state != RollingStockState.IN_SERVICE ||
        assignmentId != expectedAssignmentId

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

private fun requireStableId(label: String, value: String) {
    require(value.isNotBlank() && value == value.trim() && value.none(Char::isISOControl)) {
        "$label must be stable non-blank text"
    }
}

private fun requireTags(label: String, values: Set<String>) {
    values.forEach { value -> requireStableId(label, value) }
}
