package work.art1st.javarailways.core.api.event

import work.art1st.javarailways.core.api.physics.World

/** Event originating in one runtime world, including isolated simulation worlds. */
interface WorldEvent : Event {
    /** Exact originating runtime World instance; its execution mode identifies simulation events. */
    val world: World
}
