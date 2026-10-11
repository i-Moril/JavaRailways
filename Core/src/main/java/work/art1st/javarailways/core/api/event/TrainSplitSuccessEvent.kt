package work.art1st.javarailways.core.api.event

import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.util.annotation.TrainStructureApi

/**
 * Main-thread notification after both split results, indices, ownership and frames are committed.
 *
 * [train] is the retained stable prefix; [separatedTrain] is the new stable suffix. Both are live
 * main-thread objects, not cross-thread snapshots. Original IDs are an immutable value copy.
 * Plugins may now update operating records. This notification cannot cancel the completed split;
 * listener exceptions are logged and isolated and do not fail the successful operation's future.
 * Register through MainRailEventBus and close the registration when the owning plugin stops.
 */
@TrainStructureApi
class TrainSplitSuccessEvent internal constructor(
    override val train: Train,
    val separatedTrain: Train,
    val carId: String,
    originalCarIds: Collection<String>,
    val originalReversed: Boolean
) : MainThreadTrainEvent {
    val originalCarIds: List<String> = java.util.List.copyOf(originalCarIds)
}
