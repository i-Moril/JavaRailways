package work.art1st.mineRails.core.api.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.mineRails.core.api.util.annotation.AsyncEventApi

@OptIn(AsyncEventApi::class)
class CollisionEventTest {
    @Test
    fun `defaults to derail and listener may request link`() {
        val event = CollisionEvent(
            epoch = 7L,
            worldId = "world",
            trainAId = "A",
            trainBId = "B",
            relativeSpeed = 0.5
        )

        assertEquals(CollisionResolution.DERAIL, event.resolution)
        event.requestLink()
        assertEquals(CollisionResolution.LINK, event.resolution)
    }
}
