package work.art1st.javarailways.basic.rail

import kotlinx.serialization.Serializable
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.core.api.physics.rail.Rail
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.physics.rail.CoreRail
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class RailInfrastructureMetadata(
    val authoring: RailAuthoringMetadata? = null,
    val operating: RailOperatingMetadata = RailOperatingMetadata(),
    val speedLimits: SpeedLimitMetadata = SpeedLimitMetadata(),
    val curveSafety: CurveSafetyMetadata = CurveSafetyMetadata()
) {
    fun isEmpty(): Boolean = this == EMPTY

    companion object {
        val EMPTY = RailInfrastructureMetadata()
    }
}

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

    operator fun get(railId: UUID): RailInfrastructureMetadata? = synchronized(transactionLock) { values[railId] }

    fun getValue(railId: UUID): RailInfrastructureMetadata = synchronized(transactionLock) {
        values[railId] ?: throw NoSuchElementException("Rail infrastructure metadata not found: $railId")
    }

    fun getOrDefault(railId: UUID): RailInfrastructureMetadata = synchronized(transactionLock) {
        values[railId] ?: RailInfrastructureMetadata.EMPTY
    }

    fun put(railId: UUID, metadata: RailInfrastructureMetadata) = synchronized(transactionLock) {
        values[railId] = metadata
    }

    fun remove(railId: UUID): RailInfrastructureMetadata? = synchronized(transactionLock) { values.remove(railId) }

    fun replace(removals: Set<UUID>, additions: Map<UUID, RailInfrastructureMetadata>) =
        synchronized(transactionLock) {
            removals.forEach(values::remove)
            values.putAll(additions)
        }

    fun snapshot(): Map<UUID, RailInfrastructureMetadata> = synchronized(transactionLock) {
        values.toSortedMap(compareBy(UUID::toString))
    }

    /** Detached static limits for exactly these rails; null means neither CURVE nor SUBGRADE exists. */
    fun speedLimits(topology: TopologyState, railIds: Collection<UUID>): Map<UUID, Double?> = synchronized(transactionLock) {
        require(railIds.all(topology.railProjection::containsKey)) { "speed limit subset references a missing rail" }
        java.util.Collections.unmodifiableMap(railIds.associateWith { railId ->
            values[railId]?.speedLimits?.effectiveLimitMetersPerSecond()
        })
    }

    fun restore(restored: Map<UUID, RailInfrastructureMetadata>) = synchronized(transactionLock) {
        require(values.isEmpty()) { "Rail infrastructure metadata store is not empty" }
        values.putAll(restored)
    }
}
