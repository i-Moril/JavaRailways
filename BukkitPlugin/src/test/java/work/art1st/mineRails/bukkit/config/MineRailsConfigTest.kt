package work.art1st.mineRails.bukkit.config

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MineRailsConfigTest {
    @Test
    fun `parses train key bind distance`() {
        val yaml = YamlConfiguration()
        yaml.set("train-model-update-policy", "snapshot")
        yaml.set("train-key-bind-distance", 24.5)
        yaml.set("train-debug-log", true)
        yaml.set("physics-worker-threads", 3)
        yaml.set("overspeed-derail-enabled", true)
        yaml.set("derail-lateral-acceleration-mps2", 3.5)
        yaml.set("overspeed-tolerance-kmh", 6.0)
        yaml.set("overspeed-grace-ticks", 4)

        val config = MineRailsConfig.fromYaml(yaml)

        assertEquals(TrainModelUpdatePolicy.SNAPSHOT, config.trainModelUpdatePolicy)
        assertEquals(24.5, config.trainKeyBindDistance)
        assertEquals(true, config.trainDebugLog)
        assertEquals(3, config.physicsWorkerThreads)
        assertEquals(true, config.overspeedDerailEnabled)
        assertEquals(3.5, config.derailLateralAccelerationMetersPerSecondSquared)
        assertEquals(6.0, config.overspeedToleranceKmH)
        assertEquals(4, config.overspeedGraceTicks)
    }

    @Test
    fun `physics worker threads must be positive`() {
        val yaml = YamlConfiguration()
        yaml.set("train-model-update-policy", "snapshot")
        yaml.set("train-key-bind-distance", 24.5)
        yaml.set("train-debug-log", false)
        yaml.set("physics-worker-threads", 0)
        yaml.set("overspeed-derail-enabled", false)
        yaml.set("derail-lateral-acceleration-mps2", 3.0)
        yaml.set("overspeed-tolerance-kmh", 5.0)
        yaml.set("overspeed-grace-ticks", 5)

        assertThrows(IllegalArgumentException::class.java) {
            MineRailsConfig.fromYaml(yaml)
        }
    }

    @Test
    fun `missing required keys now fails explicitly`() {
        assertThrows(IllegalArgumentException::class.java) {
            MineRailsConfig.fromYaml(YamlConfiguration())
        }
    }
}
