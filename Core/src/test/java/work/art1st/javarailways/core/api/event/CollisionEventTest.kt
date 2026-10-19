package work.art1st.javarailways.core.api.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.util.annotation.AsyncEventApi
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.train.createTestWorld

@OptIn(AsyncEventApi::class)
class CollisionEventTest {
    @Test
    fun `defaults to derail and listener may request link`() {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        val world = try { createTestWorld(coordinator) } catch (failure: Throwable) {
            coordinator.close()
            throw failure
        }
        (world.tickManager as AutoCloseable).use {
            run {
                coordinator.use {
                    val event = CollisionEvent(
                        epoch = 7L,
                        world = world,
                        trainAId = "A",
                        trainBId = "B",
                        relativeSpeed = 0.5
                    )
                    assertEquals(CollisionResolution.DERAIL, event.resolution)
                    event.requestLink()
                    assertEquals(CollisionResolution.LINK, event.resolution)
                }
            }
        }
    }
}
