package work.art1st.javarailways.core.api.physics.exception

import work.art1st.javarailways.core.api.physics.train.Train

class Collision(
    val trainA: Train,
    val trainB: Train
) : PhysicsException() {
}