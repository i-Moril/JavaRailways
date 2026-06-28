package work.art1st.mineRails.core.api.physics.exception

import work.art1st.mineRails.core.api.physics.train.Train

class Collision(
    val trainA: Train,
    val trainB: Train
) : PhysicsException() {
}