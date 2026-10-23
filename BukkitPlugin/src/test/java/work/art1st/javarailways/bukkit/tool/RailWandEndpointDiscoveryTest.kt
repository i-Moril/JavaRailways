package work.art1st.javarailways.bukkit.tool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.core.api.util.plugin.PluginRegistry
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld

class RailWandEndpointDiscoveryTest {
    @Test
    fun `undecorated physical rails expose both endpoint selections`() {
        val basic = BasicPlugin.instanceOrNull() ?: BasicPlugin(0).also { BasicPlugin.INSTANCE = it }
        PluginRegistry.register(basic)
        val world = createTestWorld()
        try {
            world.registerExtensionData(basic, BasicWorldData())
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
            // No Bukkit world, visual extension or rail render sidecar exists for this rail.
            assertEquals(listOf(rail to 'A', rail to 'B'), physicalRailEndpointCandidates(world))
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }
}
