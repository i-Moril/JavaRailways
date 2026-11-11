package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.basic.api.route.*
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.simpleato.api.depot.DepotStore
import work.art1st.javarailways.simpleato.api.depot.FixedFormationChange
import java.util.concurrent.Future

/** Owner-thread transaction: one fixed publication, polled until Basic holds observed outputs. */
internal class FixedFormationOperation private constructor(
    val ledgerChange: FixedFormationChange,
    private val expectedPublishedTrainId: String?,
    val handle: FormationChangeHandle,
    private val admission: RouteResourceAdmissionService,
    private val publication: Future<Train>?,
    private val failClosed: (String) -> Unit
) {
    private var failure: String? = null
    private var publicationChecked = false

    sealed interface Progress {
        data object Pending : Progress
        data class Held(val snapshot: FormationChangeSnapshot) : Progress
        data class RecoveryRequired(val detail: String) : Progress
    }

    /** A pending Future is never waited on or resubmitted. Failure preserves ledger and claims. */
    fun progress(): Progress {
        failure?.let { return Progress.RecoveryRequired(it) }
        if (publication != null && !publication.isDone) return Progress.Pending
        try {
            if (publication != null && !publicationChecked) {
                check(publication.get().id == expectedPublishedTrainId) { "split returned a different physical output" }
                publicationChecked = true
            }
            return when (val state = admission.formationChangeState(handle)) {
                is FormationChangeResult.Rejected -> fail(state.failure.detail)
                is FormationChangeResult.Accepted -> when (state.snapshot.phase) {
                    FormationChangePhase.PUBLICATION_SEALED,
                    FormationChangePhase.PHYSICAL_OBSERVED -> Progress.Pending
                    FormationChangePhase.OUTPUTS_HELD -> Progress.Held(state.snapshot)
                    else -> fail("unexpected formation publication phase ${state.snapshot.phase}")
                }
            }
        } catch (exception: Exception) {
            return fail("formation publication failed: ${exception.cause?.message ?: exception.message}")
        }
    }

    private fun fail(detail: String): Progress.RecoveryRequired {
        failure = detail
        failClosed(detail)
        return Progress.RecoveryRequired(detail)
    }

    companion object {
        /** All runtime route/output preflight must precede this call. No output may depart here. */
        fun startSplit(world: World, depots: DepotStore, plan: FixedUnitSplitPlan,
            admission: RouteResourceAdmissionService, failClosed: (String) -> Unit): FixedFormationOperation {
            val source = requireNotNull(world.getTrain(plan.intent.source.trainId)) { "split source disappeared" }
            return start(world, depots, plan.intent, plan.ledgerChange, plan.intent.separated.trainId,
                admission, failClosed) { source.splitBefore(plan.intent.beforeCarId, plan.intent.separated.trainId) }
        }

        /** Physical publication happens only through the separately admitted real contact approach. */
        fun startCouple(world: World, depots: DepotStore, plan: FixedUnitCouplePlan,
            admission: RouteResourceAdmissionService, failClosed: (String) -> Unit): FixedFormationOperation =
            start(world, depots, plan.intent, plan.ledgerChange, null, admission, failClosed) { null }

        private fun start(world: World, depots: DepotStore, intent: FixedFormationChangeIntent,
            change: FixedFormationChange, expectedPublishedTrainId: String?,
            admission: RouteResourceAdmissionService, failClosed: (String) -> Unit,
            publish: () -> Future<Train>?
        ): FixedFormationOperation {
            val sources = world.allTrains.associate { it.id to it.cars.map { car -> car.id } }
            val directions = world.allTrains.associate { it.id to it.isReversed }
            val prepared = admission.prepareFormationChange(intent)
            require(prepared is FormationChangeResult.Accepted) { "formation preparation rejected: $prepared" }
            val handle = prepared.snapshot.handle
            var staged = false
            var sealed = false
            try {
                depots.prepareFixedFormationChange(change, sources, directions)
                staged = true
                val seal = admission.sealFormationPublication(handle)
                check(seal is FormationChangeResult.Accepted && seal.snapshot.phase == FormationChangePhase.PUBLICATION_SEALED) {
                    "formation publication seal rejected: $seal"
                }
                sealed = true
                return FixedFormationOperation(change, expectedPublishedTrainId, handle, admission, publish(), failClosed)
            } catch (exception: Exception) {
                if (!sealed) {
                    try {
                        val abort = admission.abortFormationChange(handle)
                        if (abort is FormationChangeResult.Accepted && abort.snapshot.phase == FormationChangePhase.ABORTED) {
                            if (staged) depots.abortFixedFormationChange(change,
                                world.allTrains.associate { it.id to it.cars.map { car -> car.id } },
                                world.allTrains.associate { it.id to it.isReversed })
                        } else failClosed("formation preparation abort failed; claims retained: $abort")
                    } catch (abortFailure: Exception) {
                        exception.addSuppressed(abortFailure)
                        failClosed("formation preparation abort threw; recovery required: ${abortFailure.message}")
                    }
                } else failClosed("sealed formation publication failed; recovery required: ${exception.message}")
                throw exception
            }
        }
    }
}
