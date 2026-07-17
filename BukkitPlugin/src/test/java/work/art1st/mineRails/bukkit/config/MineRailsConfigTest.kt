package work.art1st.mineRails.bukkit.config

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MineRailsConfigTest {
    @Test
    fun `parses train key bind distance and native display renderer`() {
        val yaml = validYaml()
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
        assertEquals(DisplayRendererBackend.NATIVE, config.displayRenderer.backend)
        assertEquals(PlayerDisplayDistanceDefault.PLAYER_VIEW_DISTANCE, config.displayRenderer.playerDistance.default)
        assertEquals(512.0, config.displayRenderer.playerDistance.maxBlocks)
    }

    @Test
    fun `parses entitylib display renderer`() {
        val yaml = validYaml()
        yaml.set("display-renderer.backend", "entitylib")
        yaml.set("display-renderer.player-distance.max-blocks", 768.0)

        val config = MineRailsConfig.fromYaml(yaml)

        assertEquals(DisplayRendererBackend.ENTITYLIB, config.displayRenderer.backend)
        assertEquals(768.0, config.displayRenderer.playerDistance.maxBlocks)
    }

    @Test
    fun `unknown display renderer fails explicitly`() {
        val yaml = validYaml()
        yaml.set("display-renderer.backend", "automatic")

        assertThrows(IllegalArgumentException::class.java) {
            MineRailsConfig.fromYaml(yaml)
        }
    }

    @Test
    fun `display renderer max distance must be within supported range`() {
        val zero = validYaml().apply {
            set("display-renderer.player-distance.max-blocks", 0.0)
        }
        val excessive = validYaml().apply {
            set("display-renderer.player-distance.max-blocks", 4097.0)
        }

        assertThrows(IllegalArgumentException::class.java) { MineRailsConfig.fromYaml(zero) }
        assertThrows(IllegalArgumentException::class.java) { MineRailsConfig.fromYaml(excessive) }
    }

    @Test
    fun `physics worker threads must be positive`() {
        val yaml = validYaml()
        yaml.set("physics-worker-threads", 0)

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

    private fun validYaml(): YamlConfiguration = YamlConfiguration().apply {
        set("train-model-update-policy", "follow-package")
        set("train-key-bind-distance", 20.0)
        set("train-debug-log", false)
        set("physics-worker-threads", 4)
        set("overspeed-derail-enabled", false)
        set("derail-lateral-acceleration-mps2", 3.0)
        set("overspeed-tolerance-kmh", 5.0)
        set("overspeed-grace-ticks", 5)
        set("display-renderer.backend", "native")
        set("display-renderer.player-distance.default", "player-view-distance")
        set("display-renderer.player-distance.max-blocks", 512.0)
    }
}
