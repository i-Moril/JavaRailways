package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor
import work.art1st.javarailways.basic.api.route.ResourcePreparationSnapshot
import work.art1st.javarailways.basic.api.route.ResourcePreparationState
import work.art1st.javarailways.basic.api.route.ResourcePreparationTicket
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.RailView
import work.art1st.javarailways.core.api.util.annotation.MainThread

/** Result of asking the physical actuator to begin one validated preparation batch. */
internal sealed interface InfrastructurePreparationStart {
    /** Pre-resolution failed before any preparation side effect occurred. */
    data class Rejected(val failure: Exception) : InfrastructurePreparationStart

    /** Physical preparation was accepted; the ticket may already be terminal. */
    data class Accepted(val ticket: ResourcePreparationTicket) : InfrastructurePreparationStart
}

internal fun interface InfrastructurePreparationActuator {
    /** Starts [requirements] in order. Rejection guarantees that no preparation side effect occurred. */
    @MainThread
    fun start(requirements: List<InfrastructurePreparationRequirement>): InfrastructurePreparationStart
}

/**
 * One ordered batch combining immediate Core [RailView] turnout work and provider-owned tickets.
 *
 * The complete heterogeneous requirement list is resolved to starters before the first write, so
 * an unsupported turnout or missing provider handler rejects without partially preparing an earlier
 * resource. Once starting begins, an exception is no longer provably side-effect-free and propagates
 * to the admission owner, which retains the claim and stalls the world. A physical turnout
 * continuation is symmetric even though Core stores one active target on each arriving directional
 * view, so every turnout requirement updates both directions.
 */
internal class LiveInfrastructurePreparationActuator(
    private val world: World,
    private val resolveContributedPreparation: (
        StableInfrastructureResource,
        ResourcePreparationDescriptor
    ) -> (() -> ResourcePreparationTicket)? = { _, _ -> null }
) : InfrastructurePreparationActuator {
    private val turnoutSelection = LiveTurnoutSelection(world)

    @MainThread
    override fun start(
        requirements: List<InfrastructurePreparationRequirement>
    ): InfrastructurePreparationStart {
        val starters: List<() -> ResourcePreparationTicket> = try {
            requirements.map { requirement ->
                when (requirement) {
                    is ResolvedTurnoutSelection -> {
                        val prepared = turnoutSelection.resolve(
                            requirement.resource,
                            requirement.fromPort,
                            requirement.toPort
                        )
                        val starter: () -> ResourcePreparationTicket = {
                            prepared.apply()
                            ReadyTicket
                        }
                        starter
                    }
                    is ResolvedContributedResourcePreparation ->
                        requireNotNull(resolveContributedPreparation(
                            requirement.resource,
                            requirement.descriptor
                        )) {
                            "preparation handler is unavailable for " +
                                "${requirement.resource.typeId}/${requirement.descriptor.kindId}"
                        }
                    else -> error(
                        "unsupported infrastructure preparation requirement: " +
                            requirement::class.qualifiedName
                    )
                }
            }
        } catch (failure: Exception) {
            return InfrastructurePreparationStart.Rejected(failure)
        }
        val tickets = starters.map { it() }
        return InfrastructurePreparationStart.Accepted(
            if (tickets.all { it === ReadyTicket }) ReadyTicket else CompositeTicket(tickets)
        )
    }

    private object ReadyTicket : ResourcePreparationTicket {
        @MainThread
        override fun snapshot(): ResourcePreparationSnapshot =
            ResourcePreparationSnapshot(ResourcePreparationState.READY)

        @MainThread
        override fun abort() = Unit
    }

    private class CompositeTicket(
        tickets: List<ResourcePreparationTicket>
    ) : ResourcePreparationTicket {
        private val tickets = java.util.List.copyOf(tickets)
        private val abortSent = BooleanArray(tickets.size)
        private var abortRequested = false
        private var terminal: ResourcePreparationSnapshot? = null

        @MainThread
        override fun snapshot(): ResourcePreparationSnapshot {
            terminal?.let { return it }
            val snapshots = tickets.map(ResourcePreparationTicket::snapshot)
            snapshots.firstOrNull { it.state == ResourcePreparationState.UNSAFE }?.let {
                terminal = it
                return it
            }
            if (abortRequested || snapshots.any { it.state == ResourcePreparationState.ABORTED_SAFE }) {
                requestChildAborts(snapshots)
                return if (snapshots.any { it.state == ResourcePreparationState.PREPARING }) {
                    ResourcePreparationSnapshot(ResourcePreparationState.PREPARING)
                } else {
                    ResourcePreparationSnapshot(
                        ResourcePreparationState.ABORTED_SAFE,
                        snapshots.firstOrNull { it.state == ResourcePreparationState.ABORTED_SAFE }
                            ?.detail.orEmpty()
                    ).also { terminal = it }
                }
            }
            return if (snapshots.all { it.state == ResourcePreparationState.READY }) {
                ResourcePreparationSnapshot(ResourcePreparationState.READY).also { terminal = it }
            } else {
                ResourcePreparationSnapshot(ResourcePreparationState.PREPARING)
            }
        }

        @MainThread
        override fun abort() {
            if (abortRequested || terminal != null) return
            val snapshots = tickets.map(ResourcePreparationTicket::snapshot)
            snapshots.firstOrNull { it.state == ResourcePreparationState.UNSAFE }?.let {
                terminal = it
                return
            }
            if (snapshots.all { it.state == ResourcePreparationState.READY }) {
                terminal = ResourcePreparationSnapshot(ResourcePreparationState.READY)
                return
            }
            abortRequested = true
            requestChildAborts(snapshots)
        }

        private fun requestChildAborts(snapshots: List<ResourcePreparationSnapshot>) {
            snapshots.forEachIndexed { index, snapshot ->
                if (snapshot.state == ResourcePreparationState.PREPARING && !abortSent[index]) {
                    tickets[index].abort()
                    abortSent[index] = true
                }
            }
        }
    }

}
