package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import java.util.UUID

/** Decoded restore inputs, prepared once without consulting live world state. */
internal class RailSnapshotRestoreInputs(
    val currentSaved: Map<UUID, CoreRail.SavedRailV1>,
    val targetSaved: Map<UUID, CoreRail.SavedRailV1>,
    val currentCurves: Map<UUID, PrimeCurve>,
    val targetCurves: Map<UUID, PrimeCurve>,
    val removedOrReplacedRailIds: Set<UUID>,
    val createdOrReplacedRailIds: Set<UUID>,
    val targetCreations: List<PreparedRailCreation>
)

internal fun prepareRailSnapshotRestoreInputs(
    currentSnapshot: List<CoreRail.SavedRailV1>,
    targetSnapshot: List<CoreRail.SavedRailV1>
): RailSnapshotRestoreInputs {
    fun index(snapshot: List<CoreRail.SavedRailV1>): Map<UUID, CoreRail.SavedRailV1> {
        val indexed = snapshot.associateBy { UUID.fromString(it.id) }
        require(indexed.size == snapshot.size) { "rail snapshot contains duplicate IDs" }
        return indexed
    }
    val currentSaved = index(currentSnapshot)
    val targetSaved = index(targetSnapshot)
    val removedOrReplacedRailIds = currentSaved.keys.filterTo(linkedSetOf()) { railId ->
        val target = targetSaved[railId]
        target == null || currentSaved.getValue(railId).curveAB != target.curveAB
    }
    val createdOrReplacedRailIds = targetSaved.keys.filterTo(linkedSetOf()) { railId ->
        val current = currentSaved[railId]
        current == null || current.curveAB != targetSaved.getValue(railId).curveAB
    }
    // Decode each snapshot curve once; creations, connection validation and the target
    // topology all consume these instances instead of rebuilding them per stage.
    val currentCurves = currentSaved.mapValues { (_, saved) -> saved.curveAB.load(Unit) }
    val targetCurves = targetSaved.mapValues { (_, saved) -> saved.curveAB.load(Unit) }
    val targetCreations = createdOrReplacedRailIds.map { railId ->
        PreparedRailCreation(railId, targetCurves.getValue(railId))
    }
    return RailSnapshotRestoreInputs(
        currentSaved = currentSaved,
        targetSaved = targetSaved,
        currentCurves = currentCurves,
        targetCurves = targetCurves,
        removedOrReplacedRailIds = removedOrReplacedRailIds,
        createdOrReplacedRailIds = createdOrReplacedRailIds,
        targetCreations = targetCreations
    )
}

/** Computes target saved rail values from captured inputs; owns no live state or locks. */
internal fun buildPreparedTargetRailSnapshot(
    baseSnapshot: List<CoreRail.SavedRailV1>,
    targetRailIds: Set<UUID>,
    targetConnections: Map<RailEndpointRef, Set<RailEndpointRef>>,
    finalConnectionOrder: Map<RailEndpointRef, List<RailEndpointRef>>,
    addedRailCurves: Map<UUID, PrimeCurve>,
    finalActiveEndpoints: Map<RailEndpointRef, RailEndpointRef?>
): List<CoreRail.SavedRailV1> {
    val baseById = baseSnapshot.associateBy { saved -> UUID.fromString(saved.id) }
    fun connections(endpoint: RailEndpointRef, base: List<SavedRailEndpointRef>): List<SavedRailEndpointRef> {
        val targets = targetConnections[endpoint].orEmpty().mapTo(linkedSetOf(), SavedRailEndpointRef::fromEndpoint)
        val explicit = finalConnectionOrder[endpoint]
        if (explicit != null) return explicit.map(SavedRailEndpointRef::fromEndpoint)
        val retained = base.filter { it in targets }
        return retained + (targets - retained.toSet()).sortedWith(compareBy({ it.railId }, { it.endpoint }))
    }
    val decodedBaseCurves = mutableMapOf<UUID, PrimeCurve>()
    fun targetCurve(railId: UUID): PrimeCurve = addedRailCurves[railId] ?: decodedBaseCurves.getOrPut(railId) {
        baseById.getValue(railId).curveAB.load(Unit)
    }
    fun active(endpoint: RailEndpointRef, connected: List<SavedRailEndpointRef>, base: SavedRailEndpointRef?): SavedRailEndpointRef? {
        val explicit = finalActiveEndpoints[endpoint]?.let(SavedRailEndpointRef::fromEndpoint)
        return when {
            endpoint in finalActiveEndpoints -> explicit
            base in connected -> base
            else -> straightestEndpoint(endpoint, connected.map {
                RailEndpointRef(UUID.fromString(it.railId), it.endpoint)
            }, ::targetCurve)?.let(SavedRailEndpointRef::fromEndpoint)
        }
    }
    return targetRailIds.sortedBy(UUID::toString).map { railId ->
        val base = baseById[railId]
        val curve = addedRailCurves[railId]?.save() as? work.art1st.javarailways.core.physics.rail.curve.PrimeCurveImpl.SavedPrimeCurveV1
            ?: requireNotNull(base) { "missing curve for prepared target rail $railId" }.curveAB
        val endpointA = RailEndpointRef(railId, RailEndpoint.A)
        val endpointB = RailEndpointRef(railId, RailEndpoint.B)
        val atA = connections(endpointA, base?.connectionsAtA.orEmpty())
        val atB = connections(endpointB, base?.connectionsAtB.orEmpty())
        CoreRail.SavedRailV1(
            id = railId.toString(),
            curveAB = curve,
            connectionsAtA = atA,
            connectionsAtB = atB,
            activeConnectionAtA = active(endpointA, atA, base?.activeConnectionAtA),
            activeConnectionAtB = active(endpointB, atB, base?.activeConnectionAtB)
        )
    }
}
