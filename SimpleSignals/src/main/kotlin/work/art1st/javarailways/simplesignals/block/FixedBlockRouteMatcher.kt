package work.art1st.javarailways.simplesignals.block

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simplesignals.route.DirectedRouteOccurrence
import work.art1st.javarailways.simplesignals.route.DirectedRouteProjectionResult
import work.art1st.javarailways.simplesignals.route.DirectedRouteProjector
import kotlin.math.max
import kotlin.math.min

internal data class FixedBlockRouteWindow(
    val blockId: String,
    val entryRouteDistanceMeters: Double,
    val exitRouteDistanceMeters: Double
)

internal sealed interface FixedBlockAuthorityMatchResult {
    data class Matched(val windows: List<FixedBlockRouteWindow>) : FixedBlockAuthorityMatchResult
    data class Rejected(val detail: String) : FixedBlockAuthorityMatchResult
}

/**
 * Matches only the requested authority prefix against directed fixed-block traversals.
 * A positive prefix intersection must contain the whole block; stopping inside a block is rejected.
 */
internal object FixedBlockRouteMatcher {
    fun matchAuthority(
        snapshot: FixedBlockReadSnapshot,
        route: RouteSnapshot,
        authorityEndRouteMeters: Double
    ): FixedBlockAuthorityMatchResult {
        require(authorityEndRouteMeters.isFinite() && authorityEndRouteMeters >= 0.0) {
            "authorityEndRouteMeters must be finite and non-negative"
        }
        require(authorityEndRouteMeters <= route.totalLengthMeters) {
            "authorityEndRouteMeters exceeds the frozen route"
        }
        if (snapshot.blocksById.isEmpty()) {
            return FixedBlockAuthorityMatchResult.Matched(emptyList())
        }
        val projection = when (val projected = DirectedRouteProjector.project(route, snapshot.routeTopology)) {
            is DirectedRouteProjectionResult.Projected -> projected.route
            is DirectedRouteProjectionResult.Rejected -> return FixedBlockAuthorityMatchResult.Rejected(
                projected.failure.detail
            )
        }
        val candidates = snapshot.candidates(projection.occurrencesByRail.keys)
        val windows = ArrayList<FixedBlockRouteWindow>()
        for (block in candidates) {
            val pieces = overlapPieces(block, projection.occurrencesByRail, authorityEndRouteMeters)
            if (pieces.isEmpty()) continue
            val runs = mergeContiguousPieces(pieces)
            val complete = runs.filter { run ->
                run.blockStartMeters <= Curve.TOLERANCE &&
                    block.lengthMeters - run.blockEndMeters <= Curve.TOLERANCE
            }
            if (complete.size != runs.size) {
                return FixedBlockAuthorityMatchResult.Rejected(
                    "authority ending at $authorityEndRouteMeters metres partially traverses fixed block " +
                        block.definition.blockId
                )
            }
            if (complete.size > 1) {
                return FixedBlockAuthorityMatchResult.Rejected(
                    "authority traverses fixed block ${block.definition.blockId} more than once"
                )
            }
            val run = complete.single()
            windows += FixedBlockRouteWindow(
                block.definition.blockId,
                run.routeStartMeters,
                run.routeEndMeters
            )
        }
        return FixedBlockAuthorityMatchResult.Matched(
            java.util.List.copyOf(
                windows.sortedWith(
                    compareBy(FixedBlockRouteWindow::entryRouteDistanceMeters)
                        .thenBy(FixedBlockRouteWindow::blockId)
                )
            )
        )
    }

    private fun overlapPieces(
        block: CompiledFixedBlock,
        occurrencesByRail: Map<java.util.UUID, List<DirectedRouteOccurrence>>,
        authorityEndRouteMeters: Double
    ): List<OverlapPiece> {
        val pieces = ArrayList<OverlapPiece>()
        var blockTraversalStart = 0.0
        for (traversal in block.traversals) {
            for (occurrence in occurrencesByRail[traversal.railId].orEmpty()) {
                if (occurrence.facing != traversal.facing) continue
                val routeLow = min(
                    occurrence.canonicalStartAbMeters,
                    occurrence.canonicalEndAbMeters
                )
                val routeHigh = max(
                    occurrence.canonicalStartAbMeters,
                    occurrence.canonicalEndAbMeters
                )
                val overlapLow = max(routeLow, traversal.lowerAbMeters)
                val overlapHigh = min(routeHigh, traversal.upperAbMeters)
                if (overlapHigh - overlapLow <= Curve.TOLERANCE) continue
                val routeStart = when (occurrence.facing) {
                    ViewDirection.AB ->
                        occurrence.routeStartMeters + overlapLow - occurrence.canonicalStartAbMeters
                    ViewDirection.BA ->
                        occurrence.routeStartMeters + occurrence.canonicalStartAbMeters - overlapHigh
                }
                if (routeStart >= authorityEndRouteMeters - Curve.TOLERANCE) continue
                val fullLength = overlapHigh - overlapLow
                val retainedLength = min(fullLength, authorityEndRouteMeters - routeStart)
                if (retainedLength <= Curve.TOLERANCE) continue
                val blockStart = when (traversal.facing) {
                    ViewDirection.AB -> blockTraversalStart + overlapLow - traversal.lowerAbMeters
                    ViewDirection.BA -> blockTraversalStart + traversal.upperAbMeters - overlapHigh
                }
                pieces += OverlapPiece(
                    routeStartMeters = routeStart,
                    routeEndMeters = routeStart + retainedLength,
                    blockStartMeters = blockStart,
                    blockEndMeters = blockStart + retainedLength
                )
            }
            blockTraversalStart += traversal.lengthMeters
        }
        return pieces
    }

    private fun mergeContiguousPieces(pieces: List<OverlapPiece>): List<OverlapPiece> {
        val merged = ArrayList<OverlapPiece>()
        for (piece in pieces.sortedWith(
            compareBy(OverlapPiece::routeStartMeters).thenBy(OverlapPiece::blockStartMeters)
        )) {
            val previous = merged.lastOrNull()
            if (previous != null &&
                kotlin.math.abs(piece.routeStartMeters - previous.routeEndMeters) <= Curve.TOLERANCE &&
                kotlin.math.abs(piece.blockStartMeters - previous.blockEndMeters) <= Curve.TOLERANCE
            ) {
                merged[merged.lastIndex] = OverlapPiece(
                    previous.routeStartMeters,
                    max(previous.routeEndMeters, piece.routeEndMeters),
                    previous.blockStartMeters,
                    max(previous.blockEndMeters, piece.blockEndMeters)
                )
            } else {
                merged += piece
            }
        }
        return merged
    }

    private data class OverlapPiece(
        val routeStartMeters: Double,
        val routeEndMeters: Double,
        val blockStartMeters: Double,
        val blockEndMeters: Double
    )
}
