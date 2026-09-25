package work.art1st.javarailways.basic.rail

import kotlinx.serialization.Serializable
import work.art1st.javarailways.basic.pathFinding.basicWorldData
import work.art1st.javarailways.core.api.physics.rail.Rail
import work.art1st.javarailways.core.physics.rail.CoreRail
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class RailInfrastructureMetadata(
    val authoring: RailAuthoringMetadata? = null,
    val operating: RailOperatingMetadata = RailOperatingMetadata(),
    val speedLimits: SpeedLimitMetadata = SpeedLimitMetadata(),
    val curveSafety: CurveSafetyMetadata = CurveSafetyMetadata()
) {
    constructor(
        designSpeedMetersPerSecond: Double? = null,
        speedLimitMetersPerSecond: Double? = designSpeedMetersPerSecond,
        derailSpeedMetersPerSecond: Double? = null,
        allowedDirection: work.art1st.javarailways.core.api.physics.rail.ViewDirection? = null,
        geometry: RailGeometryMetadata = RailGeometryMetadata(),
        assembly: RailAssemblyMetadata? = null,
        createdBySplit: RailSplitMetadata? = null,
        turnoutAtA: TurnoutEndpointMetadata? = null,
        turnoutAtB: TurnoutEndpointMetadata? = null,
        gradeLockedAtA: Boolean = false,
        gradeLockedAtB: Boolean = false,
        curveSpeedLimitAutomatic: Boolean = true
    ) : this(
        authoring = RailAuthoringMetadata(
            designSpeedMetersPerSecond,
            geometry,
            assembly,
            createdBySplit,
            gradeLockedAtA,
            gradeLockedAtB,
            curveSpeedLimitAutomatic
        ),
        operating = RailOperatingMetadata(allowedDirection, turnoutAtA, turnoutAtB),
        speedLimits = legacySpeedLimits(designSpeedMetersPerSecond, speedLimitMetersPerSecond),
        curveSafety = CurveSafetyMetadata(derailSpeedMetersPerSecond)
    )

    val designSpeedMetersPerSecond: Double? get() = authoring?.designSpeedMetersPerSecond
    val speedLimitMetersPerSecond: Double? get() = speedLimits.effectiveLimitMetersPerSecond()
    val derailSpeedMetersPerSecond: Double? get() = curveSafety.baseCurveDerailSpeedMetersPerSecond
    val allowedDirection get() = operating.allowedDirection
    val geometry: RailGeometryMetadata get() = authoring?.geometry ?: RailGeometryMetadata()
    val assembly: RailAssemblyMetadata? get() = authoring?.assembly
    val createdBySplit: RailSplitMetadata? get() = authoring?.createdBySplit
    val turnoutAtA: TurnoutEndpointMetadata? get() = operating.turnoutAtA
    val turnoutAtB: TurnoutEndpointMetadata? get() = operating.turnoutAtB
    val gradeLockedAtA: Boolean get() = authoring?.gradeLockedAtA == true
    val gradeLockedAtB: Boolean get() = authoring?.gradeLockedAtB == true

    @Suppress("LongParameterList")
    fun copy(
        designSpeedMetersPerSecond: Double? = this.designSpeedMetersPerSecond,
        speedLimitMetersPerSecond: Double? = this.speedLimitMetersPerSecond,
        derailSpeedMetersPerSecond: Double? = this.derailSpeedMetersPerSecond,
        allowedDirection: work.art1st.javarailways.core.api.physics.rail.ViewDirection? = this.allowedDirection,
        geometry: RailGeometryMetadata = this.geometry,
        assembly: RailAssemblyMetadata? = this.assembly,
        createdBySplit: RailSplitMetadata? = this.createdBySplit,
        turnoutAtA: TurnoutEndpointMetadata? = this.turnoutAtA,
        turnoutAtB: TurnoutEndpointMetadata? = this.turnoutAtB,
        gradeLockedAtA: Boolean = this.gradeLockedAtA,
        gradeLockedAtB: Boolean = this.gradeLockedAtB,
        curveSpeedLimitAutomatic: Boolean = authoring?.curveSpeedLimitAutomatic ?: true
    ): RailInfrastructureMetadata = RailInfrastructureMetadata(
        designSpeedMetersPerSecond,
        speedLimitMetersPerSecond,
        derailSpeedMetersPerSecond,
        allowedDirection,
        geometry,
        assembly,
        createdBySplit,
        turnoutAtA,
        turnoutAtB,
        gradeLockedAtA,
        gradeLockedAtB,
        curveSpeedLimitAutomatic
    )

    fun isEmpty(): Boolean = this == EMPTY

    companion object {
        val EMPTY = RailInfrastructureMetadata()

        private fun legacySpeedLimits(designSpeed: Double?, effectiveSpeed: Double?): SpeedLimitMetadata {
            val entries = buildList {
                designSpeed?.let { add(SpeedLimitEntry(it, SpeedLimitType.SUBGRADE)) }
                effectiveSpeed?.takeIf { designSpeed == null || it < designSpeed }?.let {
                    add(SpeedLimitEntry(it, SpeedLimitType.CURVE))
                }
            }
            return SpeedLimitMetadata(entries)
        }
    }
}

typealias RailMetadata = RailInfrastructureMetadata

var Rail.infrastructureMetadata: RailInfrastructureMetadata
    get() = world.basicWorldData.railMetadata.getOrDefault(id)
    set(value) {
        world.basicWorldData.railMetadata.put(id, value)
        (this as? CoreRail)?.let(OperationalSpeedLimitSignalMaterializer::materializeRail)
    }

fun Rail.updateInfrastructureMetadata(
    transform: (RailInfrastructureMetadata) -> RailInfrastructureMetadata
): RailInfrastructureMetadata = transform(infrastructureMetadata).also {
    infrastructureMetadata = it
}

class RailInfrastructureMetadataStore {
    private val values = ConcurrentHashMap<UUID, RailInfrastructureMetadata>()
    private val transactionLock = Any()
    private var revision = 0L

    operator fun get(railId: UUID): RailInfrastructureMetadata? = synchronized(transactionLock) { values[railId] }

    fun getValue(railId: UUID): RailInfrastructureMetadata = synchronized(transactionLock) {
        values[railId] ?: throw NoSuchElementException("Rail infrastructure metadata not found: $railId")
    }

    fun getOrDefault(railId: UUID): RailInfrastructureMetadata = synchronized(transactionLock) {
        values[railId] ?: RailInfrastructureMetadata.EMPTY
    }

    fun put(railId: UUID, metadata: RailInfrastructureMetadata) = synchronized(transactionLock) {
        if (values[railId] == metadata) return@synchronized
        val nextRevision = Math.incrementExact(revision)
        values[railId] = metadata
        revision = nextRevision
    }

    fun remove(railId: UUID): RailInfrastructureMetadata? = synchronized(transactionLock) {
        val existing = values[railId] ?: return@synchronized null
        val nextRevision = Math.incrementExact(revision)
        values.remove(railId)
        revision = nextRevision
        existing
    }

    fun replace(removals: Set<UUID>, additions: Map<UUID, RailInfrastructureMetadata>) =
        synchronized(transactionLock) {
            val changed = removals.any { railId ->
                val replacement = additions[railId]
                if (replacement == null) values.containsKey(railId) else values[railId] != replacement
            } || additions.any { (railId, metadata) -> values[railId] != metadata }
            if (!changed) return@synchronized
            val nextRevision = Math.incrementExact(revision)
            removals.forEach(values::remove)
            values.putAll(additions)
            revision = nextRevision
        }

    fun snapshot(): Map<UUID, RailInfrastructureMetadata> = synchronized(transactionLock) {
        values.toSortedMap(compareBy(UUID::toString))
    }

    /** Captures metadata and its store-local revision under the same lock. */
    fun snapshotWithRevision(): RailInfrastructureMetadataSnapshot = synchronized(transactionLock) {
        RailInfrastructureMetadataSnapshot(
            revision,
            Collections.unmodifiableMap(values.toSortedMap(compareBy(UUID::toString)))
        )
    }

    fun restore(restored: Map<UUID, RailInfrastructureMetadata>) = synchronized(transactionLock) {
        require(values.isEmpty()) { "Rail infrastructure metadata store is not empty" }
        values.putAll(restored)
    }
}

/** One immutable runtime view of the transitional Basic rail-metadata store. */
@ConsistentCopyVisibility
data class RailInfrastructureMetadataSnapshot internal constructor(
    val revision: Long,
    val values: Map<UUID, RailInfrastructureMetadata>
) {
    init {
        require(revision >= 0L) { "rail metadata revision must be non-negative" }
    }
}
