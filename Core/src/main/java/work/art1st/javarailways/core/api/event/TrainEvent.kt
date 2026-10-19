package work.art1st.javarailways.core.api.event

import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.train.Train

/** Event whose World source is the participating train's runtime world. */
interface TrainEvent : WorldEvent {
    /** Participating live train; access remains subject to the event's thread contract. */
    val train: Train
    /** Exact runtime world containing [train]. */
    override val world: World get() = train.world
}

/** Train event published on the platform main thread. */
interface MainThreadTrainEvent : TrainEvent, MainThreadRailEvent

/** Train event published synchronously in a physics coordinator or worker context. */
interface PhysicsTrainEvent : TrainEvent, PhysicsRailEvent
