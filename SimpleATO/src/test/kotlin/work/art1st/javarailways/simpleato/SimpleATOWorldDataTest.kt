package work.art1st.javarailways.simpleato

import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.AfterEach
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry

class SimpleATOWorldDataTest {
    private val worldFixtures = mutableListOf<TestWorldFixture>()

    @AfterEach
    fun closeOwnedWorldFixtures() {
        val fixtures = worldFixtures.toList()
        worldFixtures.clear()
        assertAll(fixtures.flatMap { fixture ->
            listOf(
                Executable { TestSimpleATOWorldDataRegistry.unregister(fixture.world) },
                Executable { fixture.close() }
            )
        })
    }

    @Test
    fun `world data rejects a coordinator with a different Basic safety interlock`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val basicData = BasicWorldData()
        val mismatchedCoordinator = AtoWorldCoordinator(reportSink = {})

        try {
            assertThrows(IllegalArgumentException::class.java) {
                TestSimpleATOWorldDataRegistry.register(world, basicData, mismatchedCoordinator)
            }
        } finally {
            TestSimpleATOWorldDataRegistry.unregister(world)
        }
    }
}
