package work.art1st.javarailways.core.api.physics.exception

/**
 * Train creation failed, optionally with captured diagnostic text rather than live objects.
 * The no-argument constructor retains a null message; message text is for people, not a protocol.
 */
class CannotCreateTrainException @JvmOverloads constructor(
    override val message: String? = null
) : PhysicsException()
