package work.art1st.mineRails.bukkit.config

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MineRailsConfigTest {
    @Test
    fun `parses train key bind distance`() {
        val yaml = YamlConfiguration()
        yaml.set("train-model-update-policy", "snapshot")
        yaml.set("train-key-bind-distance", 24.5)

        val config = MineRailsConfig.fromYaml(yaml)

        assertEquals(TrainModelUpdatePolicy.SNAPSHOT, config.trainModelUpdatePolicy)
        assertEquals(24.5, config.trainKeyBindDistance)
    }

    @Test
    fun `defaults train key bind distance to ten meters`() {
        val config = MineRailsConfig.fromYaml(YamlConfiguration())

        assertEquals(TrainModelUpdatePolicy.FOLLOW_PACKAGE, config.trainModelUpdatePolicy)
        assertEquals(10.0, config.trainKeyBindDistance)
    }
}
