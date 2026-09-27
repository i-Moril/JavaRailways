package work.art1st.javarailways.simpleato

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry

class SimpleATOWorldDataTest {
    @Test
    fun `world data rejects a coordinator with a different Basic safety interlock`() {
        val world = createTestWorld()
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
