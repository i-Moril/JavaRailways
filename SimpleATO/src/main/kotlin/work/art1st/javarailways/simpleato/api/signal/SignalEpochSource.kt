package work.art1st.javarailways.simpleato.api.signal

import work.art1st.javarailways.basic.api.observation.RailwayAreaOccupancyUpdate
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyAreaDefinition
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionUpdate
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.simpleato.api.NAMESPACED_SIMPLE_ATO_ID
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import java.util.Collections
import java.util.UUID

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

/** One baselined or changed live continuation selected for a watched rail endpoint. */
data class SignalRailContinuationUpdate(
    val entry: RailEndpointRef,
    val selectedExit: RailEndpointRef?
) {
    init {
        require(entry != selectedExit) { "an active continuation cannot return through its entry" }
    }
}

/**
 * Immutable input for one signal source invocation.
 *
 * [areaUpdates] and [continuationUpdates] contain only values watched by that source and only
 * values baselined or changed in [observation]. The callback runs synchronously before the physics
 * epoch may complete.
 *
 * @property observation coherent world observation for the current railway epoch
 * @property areaUpdates immutable baseline/changed updates owned by the invoked source
 * @property continuationUpdates immutable baseline/changed live selections watched by the source
 */
@ConsistentCopyVisibility
data class SignalEpochInput private constructor(
    val observation: RailwayObservation,
    val areaUpdates: List<RailwayAreaOccupancyUpdate>,
    val continuationUpdates: List<SignalRailContinuationUpdate>,
    val admissionUpdates: List<ActiveRouteAdmissionUpdate>,
    /** True on installation or a transaction touching this source's indexed physical interests. */
    val topologyBaseline: Boolean,
    /** Actual policy changes, scoped to this source's rail interests; baseline readers take a full subset. */
    val changedOperatingDirectionRailIds: Set<UUID>
) {
    internal companion object {
        fun create(
            observation: RailwayObservation,
            areaUpdates: Collection<RailwayAreaOccupancyUpdate>,
            continuationUpdates: Collection<SignalRailContinuationUpdate>,
            admissionUpdates: Collection<ActiveRouteAdmissionUpdate> = emptyList(),
            topologyBaseline: Boolean = false,
            changedOperatingDirectionRailIds: Collection<UUID> = emptySet()
        ): SignalEpochInput = SignalEpochInput(
            observation,
            Collections.unmodifiableList(ArrayList(areaUpdates)),
            Collections.unmodifiableList(ArrayList(continuationUpdates)),
            Collections.unmodifiableList(ArrayList(admissionUpdates)),
            topologyBaseline,
            Collections.unmodifiableSet(LinkedHashSet(changedOperatingDirectionRailIds))
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
 * [handler]. [areas] are plugin-owned physical monitoring definitions and
 * [watchedContinuationEntries] are neutral live-selection interests; the host routes only their
 * changed/baseline updates to this source and does not interpret their signal meaning. A running
 * world may replace its complete installed source set between epochs. One epoch always sees one
 * immutable source snapshot; newly installed definitions establish a fresh baseline.
 *
 * @property sourceId stable namespaced identity also used by every returned restriction snapshot
 * @property areas immutable monitored-area definitions owned by this source
 * @property watchedContinuationEntries immutable canonical endpoints whose live selection matters
 * @property handler synchronous callback invoked only for baselined/changed watched values
 */
@ConsistentCopyVisibility
data class SignalEpochSource private constructor(
    val sourceId: String,
    val areas: List<RailwayOccupancyAreaDefinition>,
    val watchedContinuationEntries: Set<RailEndpointRef>,
    /** Route admission changes touching these rails wake this source; no policy meaning is host-owned. */
    val watchedAdmissionRailIds: Set<UUID>,
    val watchedOperatingDirectionRailIds: Set<UUID>,
    val handler: SignalEpochHandler
) {
    constructor(
        sourceId: String,
        areas: Collection<RailwayOccupancyAreaDefinition>,
        handler: SignalEpochHandler
    ) : this(sourceId, areas, emptyList(), handler)

    constructor(
        sourceId: String,
        areas: Collection<RailwayOccupancyAreaDefinition>,
        watchedContinuationEntries: Collection<RailEndpointRef>,
        handler: SignalEpochHandler
    ) : this(sourceId, areas, watchedContinuationEntries, emptyList(), handler)

    constructor(
        sourceId: String,
        areas: Collection<RailwayOccupancyAreaDefinition>,
        watchedContinuationEntries: Collection<RailEndpointRef>,
        watchedAdmissionRailIds: Collection<UUID>,
        handler: SignalEpochHandler
    ) : this(sourceId, areas, watchedContinuationEntries, watchedAdmissionRailIds, emptySet(), handler)

    constructor(
        sourceId: String,
        areas: Collection<RailwayOccupancyAreaDefinition>,
        watchedContinuationEntries: Collection<RailEndpointRef>,
        watchedAdmissionRailIds: Collection<UUID>,
        watchedOperatingDirectionRailIds: Collection<UUID>,
        handler: SignalEpochHandler
    ) : this(
        sourceId,
        Collections.unmodifiableList(ArrayList(areas)),
        Collections.unmodifiableSet(LinkedHashSet(watchedContinuationEntries.sorted())),
        Collections.unmodifiableSet(LinkedHashSet(watchedAdmissionRailIds.sorted())),
        Collections.unmodifiableSet(LinkedHashSet(watchedOperatingDirectionRailIds.sorted())),
        handler
    )

    init {
        require(NAMESPACED_SIMPLE_ATO_ID.matches(sourceId)) { "sourceId must be namespaced" }
        require(areas.isNotEmpty() || watchedContinuationEntries.isNotEmpty() || watchedAdmissionRailIds.isNotEmpty() || watchedOperatingDirectionRailIds.isNotEmpty()) {
            "signal epoch source must declare a monitored area, continuation or admission rail"
        }
        require(areas.map(RailwayOccupancyAreaDefinition::areaId).distinct().size == areas.size) {
            "signal epoch source must not contain duplicate area IDs"
        }
    }
}
