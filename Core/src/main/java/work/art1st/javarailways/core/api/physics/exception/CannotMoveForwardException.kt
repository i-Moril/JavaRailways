package work.art1st.javarailways.core.api.physics.exception

class CannotMoveForwardException(
    component: Any?
) : PhysicsException() {
    override val message: String? = component?.toString()
}
