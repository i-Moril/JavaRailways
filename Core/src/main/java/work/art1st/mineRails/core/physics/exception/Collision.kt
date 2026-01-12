package work.art1st.mineRails.core.physics.exception

import work.art1st.mineRails.core.physics.train.Train

class Collision(
    val trainA: Train,
    val trainB: Train
) : PhysicsException() {
}