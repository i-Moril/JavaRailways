package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.RailView
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.util.annotation.MainThread

/** Main-thread state of one physical infrastructure preparation attempt. */
internal enum class InfrastructurePreparationState {
    PREPARING,
    READY,
    ABORTED_SAFE,
    UNSAFE
}

/** Immutable observation of one infrastructure preparation ticket. */
internal data class InfrastructurePreparationSnapshot(
    val state: InfrastructurePreparationState,
    val detail: String = ""
) {
    init {
        require(state != InfrastructurePreparationState.UNSAFE || detail.isNotBlank()) {
            "UNSAFE infrastructure preparation snapshots require detail"
        }
    }
}

/**
 * Stable handle for one accepted, ordered infrastructure-preparation batch.
 *
 * [abort] is idempotent and asks the resource-owned implementation to reach its own safe aborted
 * state. It does not prescribe restoration to a previous or default state. Callers continue reading
 * [snapshot] until ABORTED_SAFE or UNSAFE. Aborting after READY or another terminal state is a no-op.
 */
internal interface InfrastructurePreparationTicket {
    @MainThread
    fun snapshot(): InfrastructurePreparationSnapshot

    @MainThread
    fun abort()
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
    private val world: World
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
        operations.forEach { operation ->
            if (operation.from.nextActiveRail !== operation.to) {
                operation.from.selectNextRail(operation.to)
            }
        }
        return InfrastructurePreparationStart.Accepted(ReadyTicket)
    }

    private object ReadyTicket : InfrastructurePreparationTicket {
        @MainThread
        override fun snapshot(): InfrastructurePreparationSnapshot =
            InfrastructurePreparationSnapshot(InfrastructurePreparationState.READY)

        @MainThread
        override fun abort() = Unit
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
