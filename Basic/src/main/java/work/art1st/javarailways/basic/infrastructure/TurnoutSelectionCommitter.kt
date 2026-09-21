package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.RailView
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.util.annotation.MainThread

/** Result of applying one validated admission's complete turnout selection list. */
internal sealed interface TurnoutCommitResult {
    data object Committed : TurnoutCommitResult

    data class Failed(
        val applyFailure: Exception,
        val rollbackFailures: List<Exception>
    ) : TurnoutCommitResult {
        val rollbackSucceeded: Boolean
            get() = rollbackFailures.isEmpty()
    }
}

internal fun interface TurnoutSelectionCommitter {
    /** Applies [selections] in route order and restores every snapshot on any failure. */
    @MainThread
    fun commit(selections: List<ResolvedTurnoutSelection>): TurnoutCommitResult
}

/**
 * Resolves stable topology ports to live Core rail views and commits their physical selection.
 *
 * A physical continuation is symmetric even though Core stores one active target on each arriving
 * directional view. Each route selection therefore updates both `from -> to` and `to -> from`.
 */
internal class LiveTurnoutSelectionCommitter(
    private val world: World,
    private val select: (RailView, RailView?) -> Unit = RailView::selectNextRail
) : TurnoutSelectionCommitter {
    @MainThread
    override fun commit(selections: List<ResolvedTurnoutSelection>): TurnoutCommitResult {
        val operations = try {
            selections.flatMap(::resolveBidirectional)
        } catch (failure: Exception) {
            return TurnoutCommitResult.Failed(failure, emptyList())
        }
        val snapshots = LinkedHashMap<RailView, RailView?>()
        operations.forEach { operation -> snapshots.putIfAbsent(operation.from, operation.from.nextActiveRail) }

        return try {
            operations.forEach { operation ->
                if (operation.from.nextActiveRail !== operation.to) {
                    select(operation.from, operation.to)
                }
            }
            TurnoutCommitResult.Committed
        } catch (applyFailure: Exception) {
            val rollbackFailures = mutableListOf<Exception>()
            snapshots.entries.toList().asReversed().forEach { (view, previous) ->
                try {
                    if (view.nextActiveRail !== previous) select(view, previous)
                } catch (rollbackFailure: Exception) {
                    rollbackFailures += rollbackFailure
                }
            }
            TurnoutCommitResult.Failed(applyFailure, java.util.List.copyOf(rollbackFailures))
        }
    }

    private fun resolveBidirectional(selection: ResolvedTurnoutSelection): List<SelectionOperation> {
        val from = arrivingView(selection.fromPort)
        val to = arrivingView(selection.toPort)
        val fromTarget = to.reversed
        val toTarget = from.reversed
        require(from.nextRails.any { it === fromTarget }) {
            "turnout continuation no longer exists: ${selection.fromPort} -> ${selection.toPort}"
        }
        require(to.nextRails.any { it === toTarget }) {
            "reverse turnout continuation no longer exists: ${selection.toPort} -> ${selection.fromPort}"
        }
        return listOf(
            SelectionOperation(from, fromTarget),
            SelectionOperation(to, toTarget)
        )
    }

    private fun arrivingView(port: RailEndpointRef): RailView {
        val rail = requireNotNull(world.getRail(port.railId)) {
            "turnout port rail no longer exists: ${port.railId}"
        }
        val direction = when (port.endpoint) {
            RailEndpoint.A -> ViewDirection.BA
            RailEndpoint.B -> ViewDirection.AB
        }
        return rail.getView(direction)
    }

    private data class SelectionOperation(
        val from: RailView,
        val to: RailView
    )
}
