package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.core.api.physics.infrastructure.TurnoutResourceIdentity
import work.art1st.javarailways.core.physics.infrastructure.InfrastructureClaimRequest
import work.art1st.javarailways.core.physics.infrastructure.InfrastructureCoordinationRegistry
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionRequest
import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionResult
import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionStatus
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.api.util.annotation.MainThread

/** Guarded manual turnout mutation; it never creates a route grant or movement authority. */
class ManualTurnoutSelectionRuntime(
    private val world: World,
    private val registry: InfrastructureCoordinationRegistry,
    private val ensureBetweenFrames: () -> Unit,
    private val publishSelection: () -> Unit,
    private val findBlockingTrainIds: (List<RailInterval>) -> Set<String> = { intervals ->
        world.allTrains.asSequence()
            .filter { train ->
                train.occupiedRailIntervals.any { occupied -> intervals.any(occupied::overlaps) }
            }
            .mapTo(linkedSetOf()) { it.id }
    }
) : AutoCloseable {
    private val turnoutSelection = LiveTurnoutSelection(world)
    private var operationSequence = 0L
    private var closed = false

    @MainThread
    fun select(requester: Any, request: ManualTurnoutSelectionRequest): ManualTurnoutSelectionResult {
        ensureBetweenFrames()
        if (closed) return result(
            ManualTurnoutSelectionStatus.WORLD_UNAVAILABLE,
            "manual turnout runtime is closed"
        )
        val topology = world.topologyState
        val turnout = topology.structuralNodeAt(request.fromPort) as? TopologyNode.Turnout
            ?: return invalid("source port is not part of a turnout: ${request.fromPort}")
        if (topology.structuralNodeAt(request.toPort) !== turnout ||
            request.toPort !in turnout.legalContinuations.getValue(request.fromPort)
        ) {
            return invalid("ports do not form a legal turnout continuation")
        }
        val resource = TurnoutResourceIdentity.fromPorts(turnout.ports)
        val prepared = try {
            turnoutSelection.resolve(resource, request.fromPort, request.toPort)
        } catch (failure: IllegalArgumentException) {
            return invalid(failure.message ?: "turnout selection could not be resolved")
        } catch (failure: IllegalStateException) {
            return invalid(failure.message ?: "turnout selection could not be resolved")
        }
        if (prepared.alreadySelected) {
            return result(ManualTurnoutSelectionStatus.UNCHANGED)
        }

        val owner = InfrastructureOwner(
            "javarailways:manual-turnout/${operationSequence++}",
            request.topologyRevision
        )
        val acquired = registry.acquireClaim(
            requester,
            InfrastructureClaimRequest(
                owner,
                InfrastructureFootprint(listOf(resource))
            )
        )
        if (!acquired.successful) {
            return when (acquired.status) {
                InfrastructureMutationStatus.RESOURCE_CONFLICT -> ManualTurnoutSelectionResult(
                    ManualTurnoutSelectionStatus.RESOURCE_CONFLICT,
                    blockers = acquired.blockers,
                    detail = acquired.detail
                )
                InfrastructureMutationStatus.SERVICE_CLOSED -> result(
                    ManualTurnoutSelectionStatus.WORLD_UNAVAILABLE,
                    acquired.detail
                )
                else -> invalid(acquired.detail)
            }
        }

        try {
            val currentSelection = try {
                turnoutSelection.resolve(resource, request.fromPort, request.toPort)
            } catch (failure: IllegalArgumentException) {
                return invalid(failure.message ?: "turnout continuation changed")
            } catch (failure: IllegalStateException) {
                return invalid(failure.message ?: "turnout continuation changed")
            }
            val portIntervals = turnout.ports.map(::portInterval)
            val blockingTrainIds = findBlockingTrainIds(portIntervals)
            if (blockingTrainIds.isNotEmpty()) {
                return ManualTurnoutSelectionResult(
                    ManualTurnoutSelectionStatus.PHYSICALLY_OCCUPIED,
                    blockingTrainIds = blockingTrainIds,
                    detail = "turnout port is physically occupied"
                )
            }
            registry.withClaim(requester, owner) { currentSelection.apply() }
            publishSelection()
            return result(ManualTurnoutSelectionStatus.SELECTED)
        } finally {
            check(registry.releaseClaim(requester, owner).successful) {
                "manual turnout operation could not release its temporary resource claim"
            }
        }
    }

    @MainThread
    override fun close() {
        closed = true
    }

    // A body crossing the junction occupies an endpoint on at least one turnout leg.
    // Closed AB intervals also cover a head/tail exactly at a port and either facing.
    private fun portInterval(port: RailEndpointRef): RailInterval {
        val length = requireNotNull(world.getRail(port.railId)) {
            "turnout port rail no longer exists: ${port.railId}"
        }.getView(work.art1st.javarailways.core.api.physics.rail.ViewDirection.AB).curve.totalArcLength
        return when (port.endpoint) {
            RailEndpoint.A -> RailInterval(port.railId, 0.0, 0.0)
            RailEndpoint.B -> RailInterval(
                port.railId,
                length,
                length
            )
        }
    }

    private fun invalid(detail: String) =
        result(ManualTurnoutSelectionStatus.INVALID_SELECTION, detail)

    private fun result(status: ManualTurnoutSelectionStatus, detail: String = "") =
        ManualTurnoutSelectionResult(status, detail = detail)
}
