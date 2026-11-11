package work.art1st.javarailways.core.api.event

import org.jetbrains.annotations.ApiStatus
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainEndSide

/**
 * Main-owner notification after coupling structure, indices, ownership and complete frame commit.
 * [train] is the live survivor; [removedTrain] is retired and must not receive operations.
 * Original car identities are immutable copies for operating metadata mapping. Notification
 * cannot cancel the commit; listener failures are isolated without failing the successful result.
 * Register on MainRailEventBus and close its token. Experimental source/binary API; no I/O or
 * cross-thread live-state access is granted by this notification.
 *
 * @property train live surviving consist after the complete link commit
 * @property removedTrain retired source consist; it must no longer be operated
 * @property firstTrain first source instance from the decision event
 * @property secondTrain second source instance from the decision event
 * @property firstEnd stable consist end of the first source at contact
 * @property secondEnd stable consist end of the second source at contact
 * @property firstCarIds immutable pre-link car IDs in first source order
 * @property secondCarIds immutable pre-link car IDs in second source order
 * @property firstReversed first source's operation-direction flag before linking
 * @property secondReversed second source's operation-direction flag before linking
 */
@ApiStatus.Experimental
class TrainLinkSuccessEvent internal constructor(
    override val train: Train,
    val removedTrain: Train,
    val firstTrain: Train,
    val secondTrain: Train,
    val firstEnd: TrainEndSide,
    val secondEnd: TrainEndSide,
    firstCarIds: Collection<String>,
    secondCarIds: Collection<String>,
    val firstReversed: Boolean,
    val secondReversed: Boolean,
    /** Present only when the decision requested an atomic output movement fence. */
    val movementHold: work.art1st.javarailways.core.api.physics.train.FormationMovementHold? = null
) : MainThreadTrainEvent {
    val firstCarIds: List<String> = java.util.List.copyOf(firstCarIds)
    val secondCarIds: List<String> = java.util.List.copyOf(secondCarIds)
}
