package work.art1st.javarailways.basic

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.testing.TestBasicWorldDataRegistry
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import org.junit.jupiter.api.AfterEach

class BasicWorldDataAccessorsTest {
    private val fixtures = mutableListOf<TestWorldFixture>()

    private fun newTestWorld() = TestWorldFixture.create().also { fixtures += it }.world

    @AfterEach
    fun closeTestWorlds() {
        var firstFailure: Throwable? = null
        fixtures.asReversed().forEach { fixture ->
            try {
                fixture.close()
            } catch (failure: Throwable) {
                val primary = firstFailure
                if (primary == null) firstFailure = failure else primary.addSuppressed(failure)
            }
        }
        fixtures.clear()
        firstFailure?.let { throw it }
    }

    @Test
    fun `missing data stays absent and required access fails`() {
        val world = newTestWorld()

        assertNull(world.basicWorldDataOrNull)
        assertThrows(IllegalStateException::class.java) { world.basicWorldData }
        assertNull(world.basicWorldDataOrNull)
    }

    @Test
    fun `accessors return exactly the explicitly attached world container`() {
        val world = newTestWorld()
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
        val first = newTestWorld()
        val second = newTestWorld()
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
