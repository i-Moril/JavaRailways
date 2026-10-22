package work.art1st.javarailways.core.api.event

import work.art1st.javarailways.core.api.physics.World
import org.jetbrains.annotations.ApiStatus
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainEndSide
import work.art1st.javarailways.core.api.threading.ThreadGuard

/**
 * Synchronous main-owner decision for a physically valid collision coupling attempt.
 * Both trains are live and unchanged. Listeners may edit this draft, not source trains,
 * extensions or operating records. Any veto, conflicting choice or listener failure prevents
 * submission. Dispatch freezes the draft; register on MainRailEventBus and close its token.
 * Choices affect stable consist order, not physical model orientation. This experimental API
 * does not promise future source or binary compatibility. It does not perform platform work.
 *
 * @property firstTrain first collision participant, in event order
 * @property secondTrain second collision participant, in event order
 * @property world exact runtime world of [firstTrain], shared by both collision participants
 * @property firstEnd stable consist end of [firstTrain] at contact
 * @property secondEnd stable consist end of [secondTrain] at contact
 * @property firstSpeedBeforeCollision first participant's nonnegative speed magnitude in m/s before collision handling
 * @property secondSpeedBeforeCollision second participant's nonnegative speed magnitude in m/s before collision handling
 * @property firstCarIds immutable stable car IDs in the first participant's pre-link consist order
 * @property secondCarIds immutable stable car IDs in the second participant's pre-link consist order
 * @property firstReversed first participant's operation-direction flag before linking
 * @property secondReversed second participant's operation-direction flag before linking
 * @property masterTrain selected surviving source instance; defaults to the stationary participant
 * @property resultReversed operation-direction flag to apply to the resulting consist
 * @property rejectionReason first veto/conflicting choice, if any
 * @property isRejected whether any listener has vetoed this attempt
 */
@ApiStatus.Experimental
class TrainLinkEvent internal constructor(
    val firstTrain: Train,
    val secondTrain: Train,
    val firstEnd: TrainEndSide,
    val secondEnd: TrainEndSide,
    val firstSpeedBeforeCollision: Double,
    val secondSpeedBeforeCollision: Double,
    /** Host-solved head placement at the exact recorded contact, before any structure mutation. */
    val firstContactHead: work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot,
    val secondContactHead: work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot,
    private val defaultMaster: Train
) : MainThreadRailEvent, WorldEvent {
    override val world: World get() = firstTrain.world
    val firstCarIds: List<String> = java.util.List.copyOf(firstTrain.cars.map { it.id })
    val secondCarIds: List<String> = java.util.List.copyOf(secondTrain.cars.map { it.id })
    val firstReversed: Boolean = firstTrain.isReversed
    val secondReversed: Boolean = secondTrain.isReversed
    val masterTrain: Train get() = masterRequest ?: defaultMaster
    val resultReversed: Boolean get() = directionRequest ?: masterTrain.isReversed
    var rejectionReason: String? = null
        private set
    val isRejected: Boolean get() = rejectionReason != null
    /** Whether the resulting survivor must remain fenced until operational reconciliation. */
    var holdResultMovement: Boolean = false
        private set

    private var masterRequest: Train? = null
    private var directionRequest: Boolean? = null
    private var frozen = false

    /** Irrevocably veto this attempt; subsequent listeners cannot clear the first reason. */
    fun reject(reason: String) {
        checkEditable()
        require(reason.isNotBlank()) { "link rejection reason must not be blank" }
        if (rejectionReason == null) rejectionReason = reason
    }

    /** Select one of the two exact source instances to retain identity and controller. */
    fun requestMasterTrain(train: Train) {
        checkEditable()
        require(train === firstTrain || train === secondTrain) { "master must participate in this collision" }
        if (masterRequest != null && masterRequest !== train) reject("Conflicting link master trains")
        else masterRequest = train
    }

    /** Select operation direction relative to the result's stable order, rooted in its master. */
    fun requestDirection(reversed: Boolean) {
        checkEditable()
        if (directionRequest != null && directionRequest != reversed) reject("Conflicting link directions")
        else directionRequest = reversed
    }

    /** Requests a separate Core output fence; it cannot be cleared by another decision listener. */
    fun requestMovementHold() {
        checkEditable()
        holdResultMovement = true
    }

    internal fun freeze() { frozen = true }

    private fun checkEditable() {
        ThreadGuard.ensureMainThread("TrainLinkEvent decision")
        check(!frozen) { "link decision has already been dispatched" }
    }
}
