package work.art1st.javarailways.simpleato.api.inspection

import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.simpleato.api.NAMESPACED_SIMPLE_ATO_ID
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding

/** A restriction at this exact entrance which must not block its own readiness proof. */
data class InspectionRestrictionIdentity(val sourceId: String, val restrictionId: String) {
    init {
        require(NAMESPACED_SIMPLE_ATO_ID.matches(sourceId))
        require(NAMESPACED_SIMPLE_ATO_ID.matches(restrictionId))
    }
}

/** Neutral requirement: before crossing the entrance the complete train must be able to clear this coordinate. */
class InspectionEntryClearance(
    val declarationId: String,
    val entryRouteDistanceMeters: Double,
    val tailClearRouteDistanceMeters: Double,
    ignoredEntryRestrictions: Collection<InspectionRestrictionIdentity> = emptyList()
) {
    val ignoredEntryRestrictions: Set<InspectionRestrictionIdentity> = java.util.Set.copyOf(ignoredEntryRestrictions)
    init {
        require(NAMESPACED_SIMPLE_ATO_ID.matches(declarationId))
        require(entryRouteDistanceMeters.isFinite() && entryRouteDistanceMeters >= 0.0)
        require(tailClearRouteDistanceMeters.isFinite() && tailClearRouteDistanceMeters > entryRouteDistanceMeters)
    }
}

/** Controller movement-side proof. Basic authority and physical device readiness remain separate requirements. */
data class InspectionEntryClearanceState(
    val declarationId: String,
    val entryRouteDistanceMeters: Double,
    val tailClearRouteDistanceMeters: Double,
    val minimumHeadClearRouteDistanceMeters: Double,
    val requiredAuthorityEndRouteDistanceMeters: Double,
    val movementReady: Boolean
) {
    init {
        require(NAMESPACED_SIMPLE_ATO_ID.matches(declarationId))
        require(entryRouteDistanceMeters.isFinite() && entryRouteDistanceMeters >= 0.0)
        require(tailClearRouteDistanceMeters.isFinite() && tailClearRouteDistanceMeters > entryRouteDistanceMeters)
        require(minimumHeadClearRouteDistanceMeters.isFinite() && minimumHeadClearRouteDistanceMeters >= tailClearRouteDistanceMeters)
        require(requiredAuthorityEndRouteDistanceMeters.isFinite() && requiredAuthorityEndRouteDistanceMeters >= minimumHeadClearRouteDistanceMeters)
        require(!movementReady || minimumHeadClearRouteDistanceMeters > tailClearRouteDistanceMeters)
    }
}

/** Complete controller replacement for one inspection source/train/route, consumed at the next serial signal boundary. */
class InspectionClearanceSnapshot(
    val sourceId: String,
    val trainId: String,
    val observationStamp: RailwayStateStamp,
    val route: FrozenRouteBinding,
    clearances: Collection<InspectionEntryClearanceState>
) {
    val clearances: List<InspectionEntryClearanceState> = java.util.List.copyOf(clearances)
    init {
        require(NAMESPACED_SIMPLE_ATO_ID.matches(sourceId))
        require(trainId.isNotBlank())
        require(clearances.map { it.declarationId to
            if (it.entryRouteDistanceMeters == 0.0) 0.0 else it.entryRouteDistanceMeters }.distinct().size == clearances.size)
        clearances.forEach {
            require(it.tailClearRouteDistanceMeters <= route.totalLengthMeters)
            require(!it.movementReady || it.requiredAuthorityEndRouteDistanceMeters <= route.totalLengthMeters)
        }
    }
}
