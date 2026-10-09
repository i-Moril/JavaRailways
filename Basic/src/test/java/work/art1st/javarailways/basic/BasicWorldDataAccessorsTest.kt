package work.art1st.javarailways.basic

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.testing.TestBasicWorldDataRegistry
import work.art1st.javarailways.core.physics.train.createTestWorld

class BasicWorldDataAccessorsTest {
    @Test
    fun `missing data stays absent and required access fails`() {
        val world = createTestWorld()

        assertNull(world.basicWorldDataOrNull)
        assertThrows(IllegalStateException::class.java) { world.basicWorldData }
        assertNull(world.basicWorldDataOrNull)
    }

    @Test
    fun `accessors return exactly the explicitly attached world container`() {
        val world = createTestWorld()
        val data = BasicWorldData()
        TestBasicWorldDataRegistry.register(world, data)
        try {
            assertSame(data, world.basicWorldDataOrNull)
            assertSame(data, world.basicWorldData)
        } finally {
            TestBasicWorldDataRegistry.unregister(world)
        }
    }

    @Test
    fun `registration in one world does not initialize another`() {
        val first = createTestWorld()
        val second = createTestWorld()
        val data = BasicWorldData()
        TestBasicWorldDataRegistry.register(first, data)
        try {
            assertSame(data, first.basicWorldData)
            assertNull(second.basicWorldDataOrNull)
            assertThrows(IllegalStateException::class.java) { second.basicWorldData }
        } finally {
            TestBasicWorldDataRegistry.unregister(first)
        }
    }
}
