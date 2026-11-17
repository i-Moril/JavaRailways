package work.art1st.javarailways.core.physics

import work.art1st.javarailways.core.api.physics.PhysicsMaintenance
import work.art1st.javarailways.core.api.physics.exception.PhysicsException
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.train.PreparedTrainTeleport
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainTeleportInspection
import work.art1st.javarailways.core.physics.train.CoreTrain
import java.util.Collections

/** Core owns safe maintenance publication, while callers own selection and cross-train policy. */
internal class CorePhysicsMaintenance(
    private val issuingWorld: CoreWorld,
    parent: CorePhysicsMaintenance?
) : PhysicsMaintenance {
    private class Transaction {
        val snapshots = linkedMapOf<CoreTrain, CoreTrain.PlacementSnapshot>()
        var failure: Throwable? = null
        var publicationStarted = false
    }

    private val transaction: Transaction = parent?.transaction ?: Transaction()
    private val outermost = parent == null
    private var active = true

    override val world: CoreWorld
        get() {
            ensureActive()
            return issuingWorld
        }

    private fun ensureActive() {
        issuingWorld.physicsCoordinator.ensurePhysicsStalled("PhysicsMaintenance")
        check(active) { "Physics maintenance scope has expired" }
        transaction.failure?.let { throw IllegalStateException("Physics maintenance scope has failed", it) }
    }

    private class Placement(
        val scope: CorePhysicsMaintenance,
        val train: CoreTrain,
        val topology: Any,
        val selectionRevision: Long,
        val geometry: CoreTrain.PreparedTeleportGeometry
    ) : PreparedTrainTeleport {
        var consumed = false
        override val trainId: String get() = checked { train.id }
        override val headLocation: RailPositionSnapshot get() = checked {
            RailPositionSnapshot.capture(geometry.geometry.headLocation, geometry.geometry.headLocation.facing)
        }
        override val tailLocation: RailPositionSnapshot get() = checked {
            RailPositionSnapshot.capture(geometry.geometry.tailLocation, geometry.geometry.tailLocation.facing)
        }
        private val intervals = Collections.unmodifiableList(geometry.geometry.placementIntervals.toList())
        override val occupiedRailIntervals get() = checked { intervals }
        private fun <T> checked(action: () -> T): T {
            scope.ensureActive()
            return action()
        }
    }

    override fun inspectTeleport(train: Train, target: RailPositionSnapshot): TrainTeleportInspection {
        ensureActive()
        if (train !is CoreTrain || train.world !== issuingWorld || issuingWorld.getTrain(train.id) !== train) {
            return TrainTeleportInspection.Rejected("Teleport requires an exact registered train in this world")
        }
        val rail = issuingWorld.getRail(target.railId)
            ?: return TrainTeleportInspection.Rejected("Teleport rail is missing: ${target.railId}")
        return try {
            val ab = rail.viewAB.getRailLocationAt(target.arcLengthInAbMeters)
            val head = if (target.facing == work.art1st.javarailways.core.api.physics.rail.ViewDirection.AB) ab else ab.reversed
            val geometry = train.prepareTeleportGeometry(head)
            TrainTeleportInspection.Ready(Placement(this, train, issuingWorld.topologyState,
                issuingWorld.maintenanceSelectionRevision(), geometry))
        } catch (failure: PhysicsException) {
            TrainTeleportInspection.Rejected("Train ${train.id} cannot fit at the requested position: ${failure.javaClass.simpleName}")
        } catch (failure: IllegalArgumentException) {
            TrainTeleportInspection.Rejected(failure.message ?: "Invalid teleport position")
        } catch (failure: IllegalStateException) {
            TrainTeleportInspection.Rejected(failure.message ?: "Disconnected teleport placement")
        }
    }

    override fun teleport(placement: PreparedTrainTeleport) {
        try {
            ensureActive()
            val prepared = placement as? Placement
                ?: throw IllegalArgumentException("Teleport placement was not prepared by this world")
            require(prepared.scope === this) { "Teleport placement belongs to another maintenance scope" }
            check(!prepared.consumed) { "Teleport placement was already used" }
            check(issuingWorld.topologyState === prepared.topology &&
                issuingWorld.maintenanceSelectionRevision() == prepared.selectionRevision) {
                "Teleport placement topology or selected path changed"
            }
            prepared.train.applyPreparedTeleport(prepared.geometry) {
                transaction.snapshots.putIfAbsent(prepared.train, prepared.geometry.source)
            }
            prepared.consumed = true
        } catch (failure: Throwable) {
            fail(failure)
            throw failure
        }
    }

    fun finish() {
        ensureActive()
        if (outermost && transaction.snapshots.isNotEmpty()) {
            issuingWorld.publishMaintenanceChanges(transaction.snapshots.keys) {
                transaction.publicationStarted = true
            }
        }
    }

    fun fail(failure: Throwable) {
        if (transaction.failure != null || transaction.publicationStarted) return
        transaction.failure = failure
        var restorationFailed = false
        transaction.snapshots.entries.toList().asReversed().forEach { (train, snapshot) ->
            try {
                train.restorePlacementSnapshot(snapshot)
            } catch (restoreFailure: Throwable) {
                restorationFailed = true
                if (restoreFailure !== failure) failure.addSuppressed(restoreFailure)
            }
        }
        transaction.snapshots.clear()
        if (restorationFailed) {
            issuingWorld.physicsCoordinator.stall("Failed to restore physics maintenance placement", failure)
        }
    }

    fun close() {
        active = false
    }
}
