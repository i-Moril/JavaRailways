package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.RailView
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.util.annotation.MainThread
import java.util.UUID

/** Main-thread state of one physical infrastructure preparation attempt. */
internal enum class InfrastructurePreparationState {
    PREPARING,
    RESTORING,
    READY,
    RESTORED,
    UNSAFE
}

/** Immutable observation of one infrastructure preparation ticket. */
internal data class InfrastructurePreparationSnapshot(
    val ticketId: String,
    val state: InfrastructurePreparationState,
    val detail: String = ""
) {
    init {
        require(ticketId.isNotBlank()) { "ticketId must not be blank" }
        require(state != InfrastructurePreparationState.UNSAFE || detail.isNotBlank()) {
            "UNSAFE infrastructure preparation snapshots require detail"
        }
    }
}

/**
 * Stable handle for one accepted, ordered infrastructure-preparation batch.
 *
 * [cancel] is idempotent and, while non-terminal, initiates restoration. It does not imply that
 * restoration has completed; callers must continue reading [snapshot] until RESTORED or UNSAFE.
 * Cancellation after READY, RESTORED, or UNSAFE is a no-op.
 */
internal interface InfrastructurePreparationTicket {
    val ticketId: String

    @MainThread
    fun snapshot(): InfrastructurePreparationSnapshot

    @MainThread
    fun cancel()
}

/** Result of asking the physical actuator to begin one validated preparation batch. */
internal sealed interface InfrastructurePreparationStart {
    /** Pre-resolution failed before any preparation side effect occurred. */
    data class Rejected(val failure: Exception) : InfrastructurePreparationStart

    /** Physical preparation was accepted; the ticket may already be terminal. */
    data class Accepted(val ticket: InfrastructurePreparationTicket) : InfrastructurePreparationStart
}

internal fun interface InfrastructurePreparationActuator {
    /** Starts [requirements] in order. Rejection guarantees that no preparation side effect occurred. */
    @MainThread
    fun start(requirements: List<InfrastructurePreparationRequirement>): InfrastructurePreparationStart
}

/**
 * Immediate Core [RailView] preparation actuator and the first concrete batch implementation.
 *
 * It currently supports only [ResolvedTurnoutSelection]. The complete heterogeneous requirement
 * list is type-checked and every supported live view is resolved before the first write, so an
 * unsupported requirement rejects the whole batch without partially preparing earlier turnouts.
 * A physical turnout continuation is symmetric even though Core stores one active target on each
 * arriving directional view, so every turnout requirement updates both directions.
 */
internal class LiveInfrastructurePreparationActuator(
    private val world: World,
    private val select: (RailView, RailView?) -> Unit = RailView::selectNextRail
) : InfrastructurePreparationActuator {
    @MainThread
    override fun start(
        requirements: List<InfrastructurePreparationRequirement>
    ): InfrastructurePreparationStart {
        val operations = try {
            requirements.map { requirement ->
                require(requirement is ResolvedTurnoutSelection) {
                    "unsupported infrastructure preparation requirement: ${requirement::class.qualifiedName}"
                }
                requirement
            }.flatMap(::resolveBidirectional)
        } catch (failure: Exception) {
            return InfrastructurePreparationStart.Rejected(failure)
        }
        val snapshots = LinkedHashMap<RailView, RailView?>()
        operations.forEach { operation -> snapshots.putIfAbsent(operation.from, operation.from.nextActiveRail) }
        return InfrastructurePreparationStart.Accepted(ImmediateTicket(operations, snapshots))
    }

    private inner class ImmediateTicket(
        private val operations: List<SelectionOperation>,
        snapshots: LinkedHashMap<RailView, RailView?>
    ) : InfrastructurePreparationTicket {
        override val ticketId: String = UUID.randomUUID().toString()
        private val previousSelections = snapshots.entries.map { it.key to it.value }
        private var state: InfrastructurePreparationState = InfrastructurePreparationState.PREPARING
        private var detail: String = ""

        init {
            try {
                operations.forEach { operation ->
                    if (operation.from.nextActiveRail !== operation.to) {
                        select(operation.from, operation.to)
                    }
                }
                state = InfrastructurePreparationState.READY
            } catch (applyFailure: Exception) {
                restore("infrastructure preparation failed: ${failureDetail(applyFailure)}", applyFailure)
            }
        }

        @MainThread
        override fun snapshot(): InfrastructurePreparationSnapshot =
            InfrastructurePreparationSnapshot(ticketId, state, detail)

        @MainThread
        override fun cancel() {
            when (state) {
                InfrastructurePreparationState.PREPARING -> restore("infrastructure preparation was cancelled", null)
                InfrastructurePreparationState.RESTORING,
                InfrastructurePreparationState.READY,
                InfrastructurePreparationState.RESTORED,
                InfrastructurePreparationState.UNSAFE -> Unit
            }
        }

        private fun restore(reason: String, primaryFailure: Exception?) {
            state = InfrastructurePreparationState.RESTORING
            val rollbackFailures = mutableListOf<Exception>()
            previousSelections.asReversed().forEach { (view, previous) ->
                try {
                    if (view.nextActiveRail !== previous) select(view, previous)
                } catch (rollbackFailure: Exception) {
                    rollbackFailures += rollbackFailure
                }
            }
            if (rollbackFailures.isEmpty()) {
                state = InfrastructurePreparationState.RESTORED
                detail = reason
            } else {
                state = InfrastructurePreparationState.UNSAFE
                detail = buildString {
                    append(reason)
                    append("; ")
                    append(rollbackFailures.size)
                    append(" preparation restoration(s) failed")
                    primaryFailure?.let { append(" after ${failureDetail(it)}") }
                }
            }
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

    private companion object {
        fun failureDetail(failure: Exception): String =
            failure::class.simpleName + ": " + failure.message.orEmpty()
    }
}
