package work.art1st.mineRails.core.api.physics.exception

import work.art1st.mineRails.core.physics.train.CoreTrain

class Collision(
    val trainA: CoreTrain,
    val trainB: CoreTrain
) : PhysicsException() {
}