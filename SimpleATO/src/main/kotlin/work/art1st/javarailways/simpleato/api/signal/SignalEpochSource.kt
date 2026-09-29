package work.art1st.javarailways.simpleato.api.signal

import work.art1st.javarailways.basic.api.observation.RailwayAreaOccupancyUpdate
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyAreaDefinition
import work.art1st.javarailways.simpleato.api.NAMESPACED_SIMPLE_ATO_ID
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import java.util.Collections

/**
 * Synchronous Java/Kotlin signal-system callback for one completed railway epoch.
 *
 * The host invokes this callback serially on the world's physics-coordinator thread. It must return
 * all work synchronously, must not call Bukkit or blocking I/O, and may throw to fail the epoch
 * closed before another physics epoch starts.
 */
fun interface SignalEpochHandler {
    /** Computes complete train-restriction publications from this source's changed areas. */
    fun update(input: SignalEpochInput): SignalEpochResult
}

/**
 * Immutable input for one signal source invocation.
 *
 * [areaUpdates] contains only areas owned by that source and only values baselined or changed in
 * [observation]. The callback runs synchronously before the physics epoch may complete.
 *
 * @property observation coherent world observation for the current railway epoch
 * @property areaUpdates immutable baseline/changed updates owned by the invoked source
 */
@ConsistentCopyVisibility
data class SignalEpochInput private constructor(
    val observation: RailwayObservation,
    val areaUpdates: List<RailwayAreaOccupancyUpdate>
) {
    internal companion object {
        fun create(
            observation: RailwayObservation,
            areaUpdates: Collection<RailwayAreaOccupancyUpdate>
        ): SignalEpochInput = SignalEpochInput(
            observation,
            Collections.unmodifiableList(ArrayList(areaUpdates))
        )
    }
}

/**
 * Immutable output from one source invocation.
 *
 * Empty publications mean that this source has no restriction replacement to publish; an empty
 * invalidation set means that no same-source inspection declaration changed. Clearing an existing
 * restriction stream requires an explicit higher-revision empty
 * [TrainRestrictionSnapshot], not an empty epoch result.
 *
 * @property publications complete restriction snapshots to publish after whole-batch validation
 * @property invalidatedInspectionDeclarationIds declarations in the inspection source with the
 * same [SignalEpochSource.sourceId] whose active train bindings must be recomputed before control
 */
@ConsistentCopyVisibility
data class SignalEpochResult private constructor(
    val publications: List<TrainRestrictionSnapshot>,
    val invalidatedInspectionDeclarationIds: Set<String>
) {
    constructor(
        publications: Collection<TrainRestrictionSnapshot>,
        invalidatedInspectionDeclarationIds: Collection<String> = emptyList()
    ) : this(
        Collections.unmodifiableList(ArrayList(publications)),
        Collections.unmodifiableSet(LinkedHashSet(invalidatedInspectionDeclarationIds))
    )

    init {
        require(
            invalidatedInspectionDeclarationIds.all(NAMESPACED_SIMPLE_ATO_ID::matches)
        ) { "invalidated inspection declaration IDs must be namespaced" }
    }

    /** Factory for the common no-change result. */
    companion object {
        /** Returns an immutable result with neither restriction nor inspection changes. */
        @JvmStatic
        fun empty(): SignalEpochResult = SignalEpochResult(emptyList(), emptyList())
    }
}

/**
 * Immutable binding for one independently implemented signal-system source.
 *
 * [sourceId] uses the same stable namespaced identity as every restriction snapshot produced by
 * [handler]. [areas] are plugin-owned physical monitoring definitions; the host routes only their
 * changed/baseline updates to this source and does not interpret their signal meaning. A running
 * world may replace its complete installed source set between epochs. One epoch always sees one
 * immutable source snapshot; newly installed definitions establish a fresh baseline.
 *
 * @property sourceId stable namespaced identity also used by every returned restriction snapshot
 * @property areas immutable nonempty monitored-area definitions owned by this source
 * @property handler synchronous platform-neutral callback invoked only for baseline/changed areas
 */
@ConsistentCopyVisibility
data class SignalEpochSource private constructor(
    val sourceId: String,
    val areas: List<RailwayOccupancyAreaDefinition>,
    val handler: SignalEpochHandler
) {
    constructor(
        sourceId: String,
        areas: Collection<RailwayOccupancyAreaDefinition>,
        handler: SignalEpochHandler
    ) : this(sourceId, Collections.unmodifiableList(ArrayList(areas)), handler)

    init {
        require(NAMESPACED_SIMPLE_ATO_ID.matches(sourceId)) { "sourceId must be namespaced" }
        require(areas.isNotEmpty()) { "signal epoch source must own at least one monitored area" }
        require(areas.map(RailwayOccupancyAreaDefinition::areaId).distinct().size == areas.size) {
            "signal epoch source must not contain duplicate area IDs"
        }
    }
}
