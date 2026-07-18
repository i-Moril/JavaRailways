package work.art1st.mineRails.core.physics.train

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import work.art1st.mineRails.core.api.util.tick.Synchronizable

class CoreRenderCommitDecouplingTest {
    @Test
    fun `cars and bogies cannot enter the generic platform commit mailbox`() {
        assertFalse(Synchronizable::class.java.isAssignableFrom(CoreCar::class.java))
        assertFalse(Synchronizable::class.java.isAssignableFrom(CoreBogie::class.java))
    }
}
