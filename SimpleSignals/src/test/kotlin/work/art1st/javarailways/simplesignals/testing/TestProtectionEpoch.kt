package work.art1st.javarailways.simplesignals.testing

import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionUpdate
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChange
import work.art1st.javarailways.simpleato.api.inspection.InspectionClearanceSnapshot
import work.art1st.javarailways.simpleato.api.inspection.InspectionEntryClearanceState
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublisher
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationResult
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.simpleato.api.signal.SignalEpochSource
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simplesignals.region.PROTECTION_REGION_INSPECTION_SOURCE_ID
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntime

internal fun protectionTestEpoch(world: SimpleSignalsWorldRuntime, worldId: String,
    publisher: TrainRestrictionPublisher,
    completeEpoch: (RailwayStateStamp, Set<String>) -> Unit = { _, _ -> },
    invalidateInspectionDeclarations: (String, Set<String>) -> Unit = { _, _ -> }) =
    TestProtectionEpoch(world, SignalEpochRuntime(worldId, publisher, completeEpoch, invalidateInspectionDeclarations))

/** Selector/graph unit fixtures supply explicit 1m-train movement proofs, not controller/geometry validation. */
internal class TestProtectionEpoch(private val world: SimpleSignalsWorldRuntime, val runtime: SignalEpochRuntime) {
    private val admissions = linkedMapOf<work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner, ActiveRouteAdmissionUpdate>()
    private val inspection = InspectionRuntime(TrainRestrictionPublisher {
        TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, it.revision)
    }) { _, _, _ -> }
    var supplyMovementProof: Boolean = true

    init { runtime.attachInspectionRuntime(inspection) }

    fun publishAdmissionUpdate(update: ActiveRouteAdmissionUpdate) {
        if (update.current == null) admissions.remove(update.owner) else admissions[update.owner] = update
        runtime.publishAdmissionUpdate(update)
    }

    fun process(frame: RailwayWorldFrameSnapshot) {
        val stamp = RailwayStateStamp(world.currentDefinitions().worldId.toString(), frame.physicsEpoch, frame.topologyRevision)
        admissions.values.mapNotNull { it.current }.forEach { request ->
            try {
                if (!inspection.hasActiveRoutes()) inspection.replaceSources(listOf(world.protectionRegionInspectionSource))
                inspection.synchronizeRoute(stamp, request.trainId, request.route, 1.0, 0.0)
                val requirements = world.protectionRegionInspectionSource.bindingProvider.bind(
                    work.art1st.javarailways.simpleato.api.inspection.InspectionBindingInput(stamp, request.trainId, request.route)).entryClearances
                inspection.publishClearance(InspectionClearanceSnapshot(PROTECTION_REGION_INSPECTION_SOURCE_ID,
                    request.trainId, stamp, FrozenRouteBinding.from(request.route), requirements.map { requirement ->
                        val headClear = requirement.tailClearRouteDistanceMeters + 1.0
                        InspectionEntryClearanceState(requirement.declarationId, requirement.entryRouteDistanceMeters,
                            requirement.tailClearRouteDistanceMeters, headClear, headClear,
                            supplyMovementProof && headClear <= request.route.segments.sumOf { it.lengthMeters })
                    }))
            } catch (_: IllegalArgumentException) {
                // Deliberately invalid route fixtures must still exercise selector rejection.
            } catch (_: IllegalStateException) {
            }
        }
        runtime.process(frame)
    }

    private fun watching(sources: Collection<SignalEpochSource>) = sources.map { source ->
        SignalEpochSource(source.sourceId, source.areas, source.watchedContinuationEntries,
            source.watchedAdmissionRailIds, source.watchedOperatingDirectionRailIds,
            source.watchedInspectionSourceIds + PROTECTION_REGION_INSPECTION_SOURCE_ID, source.handler)
    }
    fun replaceSources(sources: Collection<SignalEpochSource>) = runtime.replaceSources(watching(sources))
    fun replaceSourcesAtomically(sources: Collection<SignalEpochSource>, commit: () -> Unit) =
        runtime.replaceSourcesAtomically(watching(sources), commit)
    fun invalidateTopology(change: RailTopologyChange) {
        inspection.invalidateTopology(change)
        runtime.invalidateTopology(change)
    }
}
