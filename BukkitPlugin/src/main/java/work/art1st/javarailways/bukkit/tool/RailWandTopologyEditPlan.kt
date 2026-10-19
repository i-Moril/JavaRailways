package work.art1st.javarailways.bukkit.tool

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditDecision
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditRequest
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditStatus
import work.art1st.javarailways.basic.api.infrastructure.TopologyEditImpact
import work.art1st.javarailways.basic.infrastructure.PhysicalTopologyReplacementOrRemovalImpactResolver
import work.art1st.javarailways.basic.infrastructure.PhysicalTopologyExplicitConnectionImpactResolver
import work.art1st.javarailways.basic.infrastructure.PhysicalTopologySnapshotRestoreImpactResolver
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureEditPermit
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.PreparedRailConnectionPlan
import work.art1st.javarailways.core.physics.rail.PreparedRailCreationBatch
import work.art1st.javarailways.core.physics.rail.PreparedRailTopologyUpdate
import work.art1st.javarailways.core.physics.rail.PreparedRailWorldSnapshotPlan
import java.util.UUID

/** Keeps the entity-owned edit callback serialized and excludes physics through its cleanup. */
internal fun <T> CoreWorld.processRailWandEdit(action: () -> T): T =
    BukkitThreadGuardBridge.runInSimulationContext(this) {
        physicsCoordinator.processBetweenFrames { action() }
    }

/** Exact pre-edit state retained by the RailWand between permit check and structural commit. */
internal class RailWandTopologyEditPlan private constructor(
    private val world: CoreWorld,
    private val topologyRoot: TopologyState,
    private val affectedRails: Map<UUID, CoreRail>,
    val impact: TopologyEditImpact?,
    private val explicitConnectionPlan: PreparedRailConnectionPlan? = null,
    private val snapshotRestorePlan: PreparedRailWorldSnapshotPlan? = null,
    private val preparedTopologyUpdate: PreparedRailTopologyUpdate? = null
) {
    val topologyRevision: Long = topologyRoot.revision
    val baseRailSnapshot: List<CoreRail.SavedRailV1> =
        preparedTopologyUpdate?.baseRailSnapshot.orEmpty()
    val targetRailSnapshot: List<CoreRail.SavedRailV1> =
        preparedTopologyUpdate?.targetRailSnapshot.orEmpty()
    val explicitConnectionAffectedExistingRailIds: Set<UUID> =
        explicitConnectionPlan?.affectedExistingRailIds.orEmpty()
    val snapshotGeometryChangedRailIds: Set<UUID> = snapshotRestorePlan?.let { plan ->
        plan.removedOrReplacedRailIds + plan.createdOrReplacedRailIds
    }.orEmpty()

    fun isCurrent(candidateWorld: CoreWorld): Boolean =
        candidateWorld === world && candidateWorld.topologyState === topologyRoot &&
            affectedRails.all { (railId, rail) -> candidateWorld.getRail(railId) === rail } &&
            (explicitConnectionPlan == null || explicitConnectionPlan.isCurrent(candidateWorld)) &&
            (snapshotRestorePlan == null || snapshotRestorePlan.isCurrent(candidateWorld))

    fun requireCurrent(candidateWorld: CoreWorld) {
        check(isCurrent(candidateWorld)) {
            "RailWand topology edit plan is stale; rebuild the edit from the current topology"
        }
    }

    fun requireReadyToApply(candidateWorld: CoreWorld) {
        requireCurrent(candidateWorld)
        explicitConnectionPlan?.requireReadyToApply(candidateWorld)
        snapshotRestorePlan?.requireReadyToApply(candidateWorld)
    }

    fun acquire(plugin: BukkitPlugin, worldId: UUID): RailWandEditPermitAcquisition {
        if (!isCurrent(world)) return stale("rail topology changed before edit permit acquisition")
        if (impact == null) return RailWandEditPermitAcquisition.Granted(null)
        val owner = InfrastructureOwner("javarailways:railwand/${UUID.randomUUID()}", topologyRevision)
        val result = plugin.infrastructureResourceService.acquireEditPermit(
            plugin,
            worldId,
            TopologyEditRequest(topologyRevision, owner, impact)
        )
        val permit = result.permit().orElse(null)
            ?: return RailWandEditPermitAcquisition.Denied(result.decision())
        if (!isCurrent(world)) {
            permit.close()
            return stale("rail topology changed after edit permit acquisition")
        }
        return RailWandEditPermitAcquisition.Granted(permit)
    }

    fun applyExplicitConnections(candidateWorld: CoreWorld, installedRails: Collection<CoreRail>) {
        val plan = requireNotNull(explicitConnectionPlan) { "RailWand edit plan has no explicit connection actions" }
        plan.apply(candidateWorld, installedRails)
    }

    fun applySnapshotRestore(candidateWorld: CoreWorld) {
        requireNotNull(snapshotRestorePlan) { "RailWand edit plan has no snapshot restore actions" }
            .apply(candidateWorld)
    }

    fun <T> commitTopology(candidateWorld: CoreWorld, action: () -> T): T {
        candidateWorld.physicsCoordinator.ensureBetweenFrames("RailWandTopologyEditPlan.commitTopology")
        requireCurrent(candidateWorld)
        return candidateWorld.railTopologyTransaction(
            requireNotNull(preparedTopologyUpdate) { "RailWand structural edit has no prepared target topology" },
            action
        )
    }

    private fun stale(detail: String) = RailWandEditPermitAcquisition.Denied(
        TopologyEditDecision(TopologyEditStatus.STALE_TOPOLOGY, detail = detail)
    )

    companion object {
        /**
         * Captures an exact edit which changes only properties or operational objects attached to
         * existing rails. Unlike a structural replacement, this deliberately does not include
         * connection peers, topology nodes, turnouts, or diamonds because none of those identities
         * can change during the edit.
         */
        fun captureRailScopedMutation(
            world: CoreWorld,
            railIds: Collection<UUID>
        ): RailWandTopologyEditPlan {
            val exactRailIds = LinkedHashSet(railIds)
            require(exactRailIds.isNotEmpty()) { "Rail-scoped edit must reference at least one existing rail" }
            val topology = world.topologyState
            require(exactRailIds.all(topology.railProjection::containsKey)) {
                "Rail-scoped edit references a rail outside the captured topology"
            }
            val rails = exactRailIds.associateWith { railId ->
                requireNotNull(world.getRail(railId)) { "Rail-scoped edit references missing rail $railId" }
            }
            return RailWandTopologyEditPlan(
                world = world,
                topologyRoot = topology,
                affectedRails = rails,
                impact = TopologyEditImpact(exactRailIds, emptySet(), emptySet())
            ).also { it.requireCurrent(world) }
        }

        fun prepareReplacementOrRemoval(
            world: CoreWorld,
            replacedOrRemovedRailIds: Collection<UUID>
        ): RailWandTopologyEditPlan {
            require(replacedOrRemovedRailIds.isNotEmpty()) {
                "RailWand topology edit plan must replace or remove an existing rail"
            }
            val topology = world.topologyState
            val impact = PhysicalTopologyReplacementOrRemovalImpactResolver.resolve(
                topology,
                replacedOrRemovedRailIds
            )
            val rails = impact.railIds.associateWith { railId ->
                requireNotNull(world.getRail(railId)) { "RailWand edit references missing rail $railId" }
            }
            val topologyUpdate = world.prepareRailRemovalTopologyUpdate(
                topology,
                LinkedHashSet(replacedOrRemovedRailIds)
            )
            val plan = RailWandTopologyEditPlan(
                world,
                topology,
                rails,
                impact,
                preparedTopologyUpdate = topologyUpdate
            )
            plan.requireCurrent(world)
            return plan
        }

        fun capturePreparedExplicitConnections(
            world: CoreWorld,
            preparedBatch: PreparedRailCreationBatch,
            explicitConnections: PreparedRailConnectionPlan
        ): RailWandTopologyEditPlan {
            explicitConnections.requireCurrent(world)
            val additionImpact = PhysicalTopologyExplicitConnectionImpactResolver.resolve(
                explicitConnections.topologyRoot,
                explicitConnections.affectedExistingEndpoints,
                explicitConnections.prospectiveCrossingExistingRailIds
            )
            val replacementImpact = preparedBatch.excludedExistingRailIds.takeIf(Set<UUID>::isNotEmpty)?.let {
                PhysicalTopologyReplacementOrRemovalImpactResolver.resolve(explicitConnections.topologyRoot, it)
            }
            val impact = mergeImpacts(replacementImpact, additionImpact)
            val rails = impact?.railIds.orEmpty().associateWith { railId ->
                requireNotNull(world.getRail(railId)) { "RailWand edit references missing rail $railId" }
            }
            return RailWandTopologyEditPlan(
                world,
                explicitConnections.topologyRoot,
                rails,
                impact,
                explicitConnections,
                preparedTopologyUpdate = explicitConnections.prepareTopologyUpdate()
            ).also { it.requireCurrent(world) }
        }

        fun capturePreparedSnapshotRestore(
            world: CoreWorld,
            snapshotPlan: PreparedRailWorldSnapshotPlan
        ): RailWandTopologyEditPlan {
            snapshotPlan.requireCurrent(world)
            val impact = PhysicalTopologySnapshotRestoreImpactResolver.resolve(
                current = snapshotPlan.topologyRoot,
                removedOrReplacedRailIds = snapshotPlan.removedOrReplacedRailIds,
                changedExistingEndpoints = snapshotPlan.changedExistingEndpoints,
                affectedExistingRailIds = snapshotPlan.affectedExistingRailIds,
                removedCrossings = snapshotPlan.removedCrossings
            )
            val rails = snapshotPlan.affectedExistingRailIds.associateWith { railId ->
                requireNotNull(world.getRail(railId)) { "Snapshot edit references missing rail $railId" }
            }
            return RailWandTopologyEditPlan(
                world = world,
                topologyRoot = snapshotPlan.topologyRoot,
                affectedRails = rails,
                impact = impact,
                snapshotRestorePlan = snapshotPlan,
                preparedTopologyUpdate = snapshotPlan.topologyUpdate
            ).also { it.requireCurrent(world) }
        }

        private fun mergeImpacts(
            first: TopologyEditImpact?,
            second: TopologyEditImpact?
        ): TopologyEditImpact? {
            if (first == null) return second
            if (second == null) return first
            return TopologyEditImpact(
                first.railIds + second.railIds,
                first.topologyNodeKeys + second.topologyNodeKeys,
                first.resourceIds + second.resourceIds
            )
        }
    }
}

internal sealed interface RailWandEditPermitAcquisition {
    class Granted(
        private val permit: InfrastructureEditPermit?
    ) : RailWandEditPermitAcquisition, AutoCloseable {
        override fun close() = permit?.close() ?: Unit
    }

    data class Denied(val decision: TopologyEditDecision) : RailWandEditPermitAcquisition
}
