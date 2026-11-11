package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.basic.api.route.*
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.train.FormationMovementHold
import work.art1st.javarailways.core.api.physics.train.FormationMovementHoldRestoration
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.simpleato.api.depot.*

/** Serialized output ownership handoff. Shared source protection may require staged departures. */
internal class FixedFormationOutputHandoff private constructor(
    private val world: World,
    private val depots: DepotStore,
    val change: FixedFormationChange,
    private val handle: FormationChangeHandle,
    private val admission: RouteResourceAdmissionService,
    private val failClosed: (String) -> Unit
) {
    private val resumed = linkedSetOf<String>()
    private val currentOwners = change.outputs.associate { it.owner.trainId to it.owner }.toMutableMap()
    private val reversing = linkedMapOf<String, FixedFormationHeldOutput>()
    private val recordedReversals = linkedSetOf<String>()
    private val recoveryHolds = arrayListOf<FormationMovementHold>()
    private var completed = false
    private var failure: String? = null

    sealed interface Progress {
        data class Waiting(val trainIds: Set<String>) : Progress
        data object Completed : Progress
        data class RecoveryRequired(val detail: String) : Progress
    }

    fun beginOutputReversal(trainId: String) {
        (world as? CoreWorld)?.physicsCoordinator?.ensureBetweenFrames("formation output reversal begin")
        check(!completed && failure == null && trainId !in resumed && trainId !in reversing)
        val owner = checkNotNull(currentOwners[trainId])
        reversing[trainId] = depots.validatePendingFormationOutput(owner, physical(), directions())
    }

    /** Persist the new exact owner in the same callback as Core.reverse(), even while rebase waits. */
    fun recordOutputReversal(trainId: String) {
        (world as? CoreWorld)?.physicsCoordinator?.ensureBetweenFrames("formation output reversal publication")
        check(trainId !in recordedReversals) { "output reversal already recorded" }
        val train = checkNotNull(world.getTrain(trainId)) as work.art1st.javarailways.core.physics.train.CoreTrain
        check(train.hasFormationMovementHold && train.publishedFrame.speedMetersPerSecond == 0.0)
        val token = checkNotNull(reversing[trainId])
        val updated = depots.recordPendingFormationReversal(token, physical(), directions())
        currentOwners[trainId] = updated.owner
        reversing[trainId] = updated
        recordedReversals += trainId
    }

    fun finishOutputReversal(trainId: String) {
        (world as? CoreWorld)?.physicsCoordinator?.ensureBetweenFrames("formation output reversal finish")
        val token = checkNotNull(reversing[trainId])
        check(trainId in recordedReversals) { "output reversal has not been published" }
        check(depots.validatePendingFormationOutput(token.owner, physical(), directions()) === token)
        reversing.remove(trainId)
        recordedReversals.remove(trainId)
    }

    /** Caller advances ordinary output admission first; a held peer is a wait, never a rollback. */
    fun progress(): Progress {
        (world as? CoreWorld)?.physicsCoordinator?.ensureBetweenFrames("formation output handoff")
        failure?.let { return Progress.RecoveryRequired(it) }
        if (completed) return Progress.Completed
        try {
            val state = admission.formationChangeState(handle)
            check(state is FormationChangeResult.Accepted) { "output handoff state unavailable: $state" }
            check(state.snapshot.phase in setOf(FormationChangePhase.OUTPUTS_BOUND,
                FormationChangePhase.OUTPUTS_ACTIVE, FormationChangePhase.COMPLETED)) {
                "output handoff entered ${state.snapshot.phase}"
            }
            for (output in change.outputs) {
                if (output.disposition == FixedFormationDisposition.HOLD || output.owner.trainId in resumed || output.owner.trainId in reversing) continue
                val pendingOwner = depots.validatePendingFormationOutput(currentOwners.getValue(output.owner.trainId), physical(), directions())
                val grant = state.snapshot.outputGrants.single { it.trainId == output.owner.trainId }
                when (val result = admission.resumeFormationOutput(handle, grant.grantId)) {
                    is FormationChangeResult.Accepted -> {
                        // Record the released fence before ledger completion, so an unexpected
                        // completion failure immediately re-fences this still-stopped output.
                        resumed += output.owner.trainId
                        depots.releasePendingFormationOutput(pendingOwner, physical(), directions())
                    }
                    is FormationChangeResult.Rejected -> check(result.failure.code == RouteAdmissionFailureCode.REQUEST_BUSY) {
                        "output handoff release rejected: ${result.failure.detail}"
                    }
                }
            }
            val waiting = change.outputs.filter { it.disposition == FixedFormationDisposition.CONTINUE_DUTY &&
                it.owner.trainId !in resumed }.mapTo(linkedSetOf()) { it.owner.trainId }
            if (waiting.isNotEmpty()) return Progress.Waiting(java.util.Set.copyOf(waiting))
            completed = true
            return Progress.Completed
        } catch (exception: Exception) {
            val detail = "formation output handoff requires recovery: ${exception.message}"
            // No frame can advance inside this owner callback. Re-fence exact stopped outputs;
            // a departed output is stopped by the world failure path, never teleported or reset.
            val fenceFailures = arrayListOf<String>()
            change.outputs.filter { it.owner.trainId in resumed }.forEach { output ->
                try {
                    when (val hold = world.restoreFormationMovementHold(output.owner.trainId, output.owner.carIds)) {
                        is FormationMovementHoldRestoration.Accepted -> recoveryHolds += hold.hold
                        is FormationMovementHoldRestoration.Rejected -> fenceFailures += "${output.owner.trainId}: ${hold.reason}"
                    }
                } catch (holdFailure: Exception) {
                    fenceFailures += "${output.owner.trainId}: ${holdFailure.message}"
                }
            }
            val reported = if (fenceFailures.isEmpty()) detail else
                "$detail; immediate recovery fences unavailable: ${fenceFailures.joinToString("; ")}"
            failure = reported
            failClosed(reported)
            return Progress.RecoveryRequired(reported)
        }
    }

    private fun physical() = world.allTrains.associate { it.id to it.cars.map { car -> car.id } }
    private fun directions() = world.allTrains.associate { it.id to it.isReversed }

    companion object {
        /** Install every disposition while all Core output fences remain held, then commit owners. */
        fun begin(world: World, depots: DepotStore, change: FixedFormationChange,
            handle: FormationChangeHandle, admission: RouteResourceAdmissionService,
            install: (FixedFormationOutput, RouteGrantSnapshot) -> Unit,
            failClosed: (String) -> Unit
        ): FixedFormationOutputHandoff {
            (world as? CoreWorld)?.physicsCoordinator?.ensureBetweenFrames("formation output handoff begin")
            try {
                val state = admission.formationChangeState(handle)
                require(state is FormationChangeResult.Accepted && state.snapshot.phase == FormationChangePhase.OUTPUTS_BOUND) {
                    "all formation outputs must be bound and held before installing dispositions"
                }
                require(state.snapshot.eventId == change.eventId)
                require(state.snapshot.outputs.map { FixedFormationOwner(it.trainId,
                    change.outputs.single { output -> output.owner.trainId == it.trainId }.owner.assignmentId,
                    it.carIds, it.reversed) }.toSet() == change.outputs.map { it.owner }.toSet()) {
                    "bound physical outputs differ from prepared ownership"
                }
                change.outputs.forEach { output ->
                    install(output, state.snapshot.outputGrants.single { it.trainId == output.owner.trainId })
                }
                val committed = depots.commitFixedFormationChange(change,
                    world.allTrains.associate { it.id to it.cars.map { car -> car.id } },
                    world.allTrains.associate { it.id to it.isReversed })
                depots.finishFixedFormationChange(committed,
                    world.allTrains.associate { it.id to it.cars.map { car -> car.id } },
                    world.allTrains.associate { it.id to it.isReversed }, committed.outputs.map { it.owner },
                    committed.outputs.filter { it.disposition == FixedFormationDisposition.CONTINUE_DUTY }.map { it.owner })
                return FixedFormationOutputHandoff(world, depots, committed, handle, admission, failClosed)
            } catch (exception: Exception) {
                failClosed("formation output installation failed; output fences and recovery record retained: ${exception.message}")
                throw exception
            }
        }
    }
}
