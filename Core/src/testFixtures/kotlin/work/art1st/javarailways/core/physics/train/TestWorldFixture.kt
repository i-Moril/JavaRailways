package work.art1st.javarailways.core.physics.train

import work.art1st.javarailways.core.api.physics.WorldExecutionMode
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator

/**
 * Ownership-scoped test world resource.
 *
 * [create] owns the world, its tick manager and (unless supplied by the caller) its physics
 * coordinator. [close] attempts every
 * owned step, keeps the first failure and attaches later failures as suppressed.
 *
 * Tests still own their scenario and assertions; this only centralizes the fixture lifecycle that
 * was previously re-written (and sometimes forgotten) per test class.
 */
class TestWorldFixture private constructor(
    val world: CoreWorld,
    private val ownsCoordinator: Boolean
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        var firstFailure: Throwable? = null
        fun attempt(step: String, action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                val primary = firstFailure
                val wrapped = IllegalStateException("Failed to $step for test world ${world.runtimeId}", failure)
                if (primary == null) {
                    firstFailure = wrapped
                } else {
                    primary.addSuppressed(wrapped)
                }
            }
        }
        if (ownsCoordinator) attempt("close physics") { world.physicsCoordinator.close() }
        attempt("close tick manager") { (world.tickManager as? AutoCloseable)?.close() }
        firstFailure?.let { throw it }
    }

    companion object {
        @JvmOverloads
        fun create(
            physicsCoordinator: PhysicsCoordinator? = null,
            executionMode: WorldExecutionMode = WorldExecutionMode.LIVE
        ): TestWorldFixture = TestWorldFixture(
            world = createTestWorld(physicsCoordinator, executionMode),
            ownsCoordinator = physicsCoordinator == null
        )
    }
}
