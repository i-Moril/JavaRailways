package work.art1st.javarailways.core.physics.tick

import work.art1st.javarailways.core.physics.train.CoreTrain

/** Physics-owned extension point evaluated after immutable train frames are published for an epoch. */
interface PublishedTrainFrameProcessor {
    /** Called concurrently for distinct trains; one train is processed at most once per epoch. */
    fun process(train: CoreTrain, physicsEpoch: Long)

    /** Called once on the physics coordinator after every train has been processed. */
    fun completeEpoch(liveTrainIds: Set<String>, physicsEpoch: Long)
}
