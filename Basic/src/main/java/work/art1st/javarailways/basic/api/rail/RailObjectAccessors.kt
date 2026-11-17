package work.art1st.javarailways.basic.api.rail

import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.core.api.physics.World

/**
 * Returns the existing world-local operational rail-object registry, with its original lifecycle
 * contract. It neither creates a store nor captures a detached snapshot; callers retain their usual
 * world/read-phase and object-lifecycle checks.
 * @throws IllegalStateException if Basic data has not been explicitly attached to this world.
 */
val World.railObjects: RailObjectStore
    get() = basicWorldData.railObjects
