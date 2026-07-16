package work.art1st.mineRails.basic.api.depot

import work.art1st.mineRails.core.api.util.annotation.MainThread
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

    fun stablingPoint(ref: StablingPointRef): StablingPointSnapshot? = synchronized(lock) {
        points[ref]?.let { StablingPointSnapshot(it.point, it.occupantTrainId, it.reservedByAssignmentId) }
    }

    fun stablingPoints(depotId: DepotId): List<StablingPointSnapshot> = synchronized(lock) {
        depots[depotId]?.stablingPointRefs.orEmpty().mapNotNull { ref ->
            points[ref]?.let { StablingPointSnapshot(it.point, it.occupantTrainId, it.reservedByAssignmentId) }
        }
    }

    fun rollingStock(trainId: String): RollingStockSnapshot? = synchronized(lock) {
        rollingStock[trainId]?.snapshot()
    }

    fun allRollingStock(): List<RollingStockSnapshot> = synchronized(lock) {
        rollingStock.values.sortedBy { it.record.trainId }.map { it.snapshot() }
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

private fun requireStableId(label: String, value: String) {
    require(value.isNotBlank() && value == value.trim() && value.none(Char::isISOControl)) {
        "$label must be stable non-blank text"
    }
}

private fun requireTags(label: String, values: Set<String>) {
    values.forEach { value -> requireStableId(label, value) }
}
