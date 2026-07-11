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

        val config = MineRailsConfig.fromYaml(yaml)

        assertEquals(TrainModelUpdatePolicy.SNAPSHOT, config.trainModelUpdatePolicy)
        assertEquals(24.5, config.trainKeyBindDistance)
        assertEquals(true, config.trainDebugLog)
    }

    @Test
    fun `missing required keys now fails explicitly`() {
        assertThrows(IllegalArgumentException::class.java) {
            MineRailsConfig.fromYaml(YamlConfiguration())
        }
    }
}
