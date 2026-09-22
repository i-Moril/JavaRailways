package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.route.ProtectedRouteClaimStatus
import work.art1st.javarailways.basic.api.route.ProtectedRouteRequest
import work.art1st.javarailways.core.api.physics.curve.Curve

/** Validates one frozen route/window and returns the single resource set that may be claimed. */
internal class ProtectedRouteRequestValidator(
    private val catalog: ProtectionRegionCatalog,
    private val routeBoundResources: RouteBoundResourceCatalog
) {
    private val physicalResources = PhysicalRouteResourceCompiler(routeBoundResources)

    fun validate(request: ProtectedRouteRequest): ProtectedRouteValidation =
        when (val resolved = catalog.resolveWindow(request)) {
            is ProtectionRegionWindowResolution.Rejected -> ProtectedRouteValidation.Rejected(
                status = when (resolved.reason) {
                    ProtectionRegionWindowRejection.STALE_TOPOLOGY ->
                        ProtectedRouteClaimStatus.STALE_TOPOLOGY
                    ProtectionRegionWindowRejection.MISSING_REGION,
                    ProtectionRegionWindowRejection.STALE_REGION,
                    ProtectionRegionWindowRejection.INVALID_ROUTE ->
                        ProtectedRouteClaimStatus.INVALID_REQUEST
                },
                detail = resolved.detail
            )
            is ProtectionRegionWindowResolution.Resolved -> validateResolved(resolved)
        }

    private fun validateResolved(
        resolved: ProtectionRegionWindowResolution.Resolved
    ): ProtectedRouteValidation {
        val window = resolved.window?.request
        val queryEnd = window?.exitClearance?.clearUntil?.routeDistanceMeters
            ?: resolved.projection.occurrences.last().routeEndMeters
        val routeBoundHits = when (
            val queried = routeBoundResources.query(resolved.projection, 0.0, queryEnd)
        ) {
            is RouteBoundResourceQuery.Complete -> queried.hits
            is RouteBoundResourceQuery.Unavailable -> return rejectedStale(queried.detail)
            is RouteBoundResourceQuery.StaleTopology -> return rejectedStale(queried.detail)
            is RouteBoundResourceQuery.InvalidRoute -> return ProtectedRouteValidation.Rejected(
                ProtectedRouteClaimStatus.INVALID_REQUEST,
                queried.detail
            )
        }
        if (window == null) {
            return if (resolved.projection.turnoutTraversals.isEmpty() && routeBoundHits.isEmpty()) {
                ProtectedRouteValidation.NotRequired
            } else {
                rejectedProtection("route contains a controlled resource but no protection window")
            }
        }

        return compileRange(
            resolved = resolved,
            startRouteMeters = window.entry.routeDistanceMeters,
            endRouteMeters = queryEnd,
            routeBoundHits = routeBoundHits,
            previouslyCommittedControlledResources = emptySet(),
            controlledScanStartMeters = 0.0,
            excludeStartTurnout = false,
            retainedProgressPath = null
        )
    }

    fun compileExtension(
        context: ProtectedRouteExtensionContext,
        request: ProtectedRouteExtensionRequest
    ): ProtectedRouteExtensionValidation {
        if (request.route != context.projection.route) {
            return ProtectedRouteExtensionValidation.Rejected(
                ProtectedRouteClaimStatus.INVALID_REQUEST,
                "rolling extension must retain the exact frozen route"
            )
        }
        val window = request.window
        val nextFrontier = window.exitClearance.clearUntil.routeDistanceMeters
        if (nextFrontier <= context.authorityFrontierMeters + Curve.TOLERANCE) {
            return ProtectedRouteExtensionValidation.Rejected(
                ProtectedRouteClaimStatus.INVALID_REQUEST,
                "rolling extension boundary must be strictly beyond the committed authority frontier"
            )
        }
        val resolved = when (val result = catalog.resolveWindow(context.projection, window)) {
            is ProtectionRegionWindowResolution.Resolved -> result
            is ProtectionRegionWindowResolution.Rejected -> return ProtectedRouteExtensionValidation.Rejected(
                if (result.reason == ProtectionRegionWindowRejection.STALE_TOPOLOGY) {
                    ProtectedRouteClaimStatus.STALE_TOPOLOGY
                } else {
                    ProtectedRouteClaimStatus.INVALID_REQUEST
                },
                result.detail
            )
        }
        val hits = when (
            val queried = routeBoundResources.query(
                context.projection,
                context.authorityFrontierMeters,
                nextFrontier,
                context.frontierOccurrenceIndex
            )
        ) {
            is RouteBoundResourceQuery.Complete -> queried.hits
            is RouteBoundResourceQuery.Unavailable -> return extensionStale(queried.detail)
            is RouteBoundResourceQuery.StaleTopology -> return extensionStale(queried.detail)
            is RouteBoundResourceQuery.InvalidRoute -> return ProtectedRouteExtensionValidation.Rejected(
                ProtectedRouteClaimStatus.INVALID_REQUEST,
                queried.detail
            )
        }
        return when (
            val compiled = compileRange(
                resolved,
                context.authorityFrontierMeters,
                nextFrontier,
                hits,
                context.committedControlledResources,
                controlledScanStartMeters = context.authorityFrontierMeters,
                excludeStartTurnout = true,
                retainedProgressPath = context.routeProgressPath
            )
        ) {
            is ProtectedRouteValidation.Rejected -> ProtectedRouteExtensionValidation.Rejected(
                compiled.status,
                compiled.detail
            )
            ProtectedRouteValidation.NotRequired -> error("non-empty extension range cannot be not required")
            is ProtectedRouteValidation.Validated -> ProtectedRouteExtensionValidation.Validated(
                compiled.admission,
                ProtectedRouteExtensionContext(
                    context.projection,
                    nextFrontier,
                    context.committedControlledResources + compiled.admission.resources.resources
                        .filterIsInstance<StableInfrastructureResource>(),
                    context.routeProgressPath,
                    occurrenceIndexAt(context.projection.occurrences, nextFrontier)
                )
            )
        }
    }

    private fun compileRange(
        resolved: ProtectionRegionWindowResolution.Resolved,
        startRouteMeters: Double,
        endRouteMeters: Double,
        routeBoundHits: List<RouteBoundResourceHit>,
        previouslyCommittedControlledResources: Set<StableInfrastructureResource>,
        controlledScanStartMeters: Double,
        excludeStartTurnout: Boolean,
        retainedProgressPath: CompiledRouteProgressPath?
    ): ProtectedRouteValidation {
        val window = checkNotNull(resolved.window?.request)

        val entry = window.entry.routeDistanceMeters
        val exit = window.exit.routeDistanceMeters
        val clearUntil = endRouteMeters
        val relevantTurnouts = resolved.projection.turnoutTraversals.filter { traversal ->
            (if (excludeStartTurnout) {
                traversal.routeDistanceMeters > controlledScanStartMeters + Curve.TOLERANCE
            } else {
                traversal.routeDistanceMeters >= controlledScanStartMeters - Curve.TOLERANCE
            }) &&
                traversal.routeDistanceMeters <= clearUntil + Curve.TOLERANCE
        }
        val unprotected = relevantTurnouts.firstOrNull { traversal ->
            traversal.routeDistanceMeters < entry - Curve.TOLERANCE ||
                traversal.routeDistanceMeters > exit + Curve.TOLERANCE
        }
        if (unprotected != null) {
            return rejectedProtection(
                "turnout ${unprotected.resource.resourceId} at route distance " +
                    "${unprotected.routeDistanceMeters} is outside the protected entry-to-exit window"
            )
        }
        val unprotectedRouteBound = routeBoundHits.firstOrNull { hit ->
            hit.routeStartMeters < entry - Curve.TOLERANCE ||
                hit.routeEndMeters > exit + Curve.TOLERANCE
        }
        if (unprotectedRouteBound != null) {
            return rejectedProtection(
                "resource ${unprotectedRouteBound.resource.resourceId} at route distance " +
                    "${unprotectedRouteBound.routeStartMeters}..${unprotectedRouteBound.routeEndMeters} " +
                    "is outside the protected entry-to-exit window"
            )
        }

        val compiled = when (val result = physicalResources.compileResolved(
            projection = resolved.projection,
            range = PhysicalRouteRange(
                startRouteMeters,
                clearUntil,
                includeTurnoutAtStart = !excludeStartTurnout
            ),
            routeBoundHits = routeBoundHits,
            previouslyCommittedControlledResources = previouslyCommittedControlledResources,
            retainedProgressPath = retainedProgressPath
        )) {
            is PhysicalRouteCompilation.Compiled -> result
            is PhysicalRouteCompilation.Rejected -> return ProtectedRouteValidation.Rejected(
                status = if (result.reason == PhysicalRouteCompilationRejection.STALE_TOPOLOGY) {
                    ProtectedRouteClaimStatus.STALE_TOPOLOGY
                } else {
                    ProtectedRouteClaimStatus.INVALID_REQUEST
                },
                detail = result.detail
            )
        }
        val admission = compiled.admission
        return ProtectedRouteValidation.Validated(
            admission,
            ProtectedRouteExtensionContext(
                resolved.projection,
                clearUntil,
                compiled.controlledResources,
                admission.routeProgressPath,
                occurrenceIndexAt(resolved.projection.occurrences, clearUntil)
            )
        )
    }

    private fun rejectedProtection(detail: String) = ProtectedRouteValidation.Rejected(
        ProtectedRouteClaimStatus.PROTECTION_REQUIRED,
        detail
    )

    private fun rejectedStale(detail: String) = ProtectedRouteValidation.Rejected(
        ProtectedRouteClaimStatus.STALE_TOPOLOGY,
        detail
    )

    private fun extensionStale(detail: String) = ProtectedRouteExtensionValidation.Rejected(
        ProtectedRouteClaimStatus.STALE_TOPOLOGY,
        detail
    )

    private fun occurrenceIndexAt(
        occurrences: List<ProjectedRouteOccurrence>,
        routeMeters: Double
    ): Int = occurrences.indexOfFirst { it.routeEndMeters >= routeMeters - Curve.TOLERANCE }
        .takeIf { it >= 0 } ?: occurrences.lastIndex
}

internal data class ProtectedRouteExtensionRequest(
    val requestId: String,
    val owner: work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner,
    val trainId: String,
    val observationStamp: work.art1st.javarailways.basic.api.observation.RailwayStateStamp,
    val route: work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot,
    val window: work.art1st.javarailways.basic.api.route.ProtectedRouteWindow
)

internal data class ProtectedRouteExtensionContext(
    val projection: ProjectedTopologyRoute,
    val authorityFrontierMeters: Double,
    val committedControlledResources: Set<StableInfrastructureResource>,
    val routeProgressPath: CompiledRouteProgressPath,
    val frontierOccurrenceIndex: Int
)

internal sealed interface ProtectedRouteExtensionValidation {
    data class Validated(
        val admission: CompiledRouteAdmission,
        val nextContext: ProtectedRouteExtensionContext
    ) : ProtectedRouteExtensionValidation

    data class Rejected(
        val status: ProtectedRouteClaimStatus,
        val detail: String
    ) : ProtectedRouteExtensionValidation
}

internal sealed interface ProtectedRouteValidation {
    data object NotRequired : ProtectedRouteValidation

    data class Validated(
        val admission: CompiledRouteAdmission,
        val extensionContext: ProtectedRouteExtensionContext? = null
    ) : ProtectedRouteValidation

    data class Rejected(
        val status: ProtectedRouteClaimStatus,
        val detail: String
    ) : ProtectedRouteValidation {
        init {
            require(status in VALIDATION_REJECTION_STATUSES) {
                "route validation rejection must use a validation failure status"
            }
            require(detail.isNotBlank())
        }
    }

    private companion object {
        val VALIDATION_REJECTION_STATUSES = setOf(
            ProtectedRouteClaimStatus.PROTECTION_REQUIRED,
            ProtectedRouteClaimStatus.STALE_TOPOLOGY,
            ProtectedRouteClaimStatus.INVALID_REQUEST
        )
    }
}
