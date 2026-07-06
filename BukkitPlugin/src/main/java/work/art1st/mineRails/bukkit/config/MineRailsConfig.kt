package work.art1st.mineRails.bukkit.config

import org.bukkit.configuration.file.YamlConfiguration
import work.art1st.mineRails.bukkit.BukkitPlugin
import java.io.File

data class MineRailsConfig(
    val trainModelUpdatePolicy: TrainModelUpdatePolicy = TrainModelUpdatePolicy.FOLLOW_PACKAGE
) {
    companion object {
        fun load(plugin: BukkitPlugin): MineRailsConfig {
            val file = File(plugin.dataFolder, "config.yml")
            if (!file.exists()) {
                file.parentFile.mkdirs()
                file.writeText(DEFAULT_CONFIG)
            }

            val yaml = YamlConfiguration.loadConfiguration(file)
            val policy = TrainModelUpdatePolicy.fromConfig(
                yaml.getString("train-model-update-policy")
            )
            return MineRailsConfig(trainModelUpdatePolicy = policy)
        }

        private val DEFAULT_CONFIG = """
            # How saved trains react to train package/model YAML changes.
            # follow-package: saved trains keep their car model names, but rebuild from latest package files.
            # snapshot: saved trains store a resolved model snapshot and do not follow package changes.
            train-model-update-policy: follow-package
        """.trimIndent() + "\n"
    }
}

enum class TrainModelUpdatePolicy {
    FOLLOW_PACKAGE,
    SNAPSHOT;

    companion object {
        fun fromConfig(value: String?): TrainModelUpdatePolicy {
            return when (value?.trim()?.lowercase()) {
                null, "", "follow-package", "follow_package", "follow" -> FOLLOW_PACKAGE
                "snapshot", "freeze", "frozen" -> SNAPSHOT
                else -> FOLLOW_PACKAGE
            }
        }
    }
}
