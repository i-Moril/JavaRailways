package work.art1st.mineRails.bukkit.config

import org.bukkit.configuration.file.YamlConfiguration
import work.art1st.mineRails.bukkit.BukkitPlugin
import java.io.File

data class MineRailsConfig(
    val trainModelUpdatePolicy: TrainModelUpdatePolicy,
    val trainKeyBindDistance: Double,
    val trainDebugLog: Boolean,
    val physicsWorkerThreads: Int,
    val overspeedDerailEnabled: Boolean,
    val overspeedToleranceKmH: Double,
    val overspeedGraceTicks: Int
) {
    companion object {
        fun load(plugin: BukkitPlugin): MineRailsConfig {
            val file = File(plugin.dataFolder, "config.yml")
            if (!file.exists()) {
                plugin.saveResource("config.yml", false)
            }

            val yaml = YamlConfiguration.loadConfiguration(file)
            return fromYaml(yaml)
        }

        fun fromYaml(yaml: YamlConfiguration): MineRailsConfig {
            val policy = TrainModelUpdatePolicy.fromConfig(requireString(yaml, "train-model-update-policy"))
            val bindDistance = requireDouble(yaml, "train-key-bind-distance")
            require(bindDistance >= 0.0) { "train-key-bind-distance must be >= 0.0" }
            val physicsWorkerThreads = requireInt(yaml, "physics-worker-threads")
            require(physicsWorkerThreads > 0) { "physics-worker-threads must be > 0" }
            val overspeedToleranceKmH = requireDouble(yaml, "overspeed-tolerance-kmh")
            require(overspeedToleranceKmH >= 0.0) { "overspeed-tolerance-kmh must be >= 0.0" }
            val overspeedGraceTicks = requireInt(yaml, "overspeed-grace-ticks")
            require(overspeedGraceTicks > 0) { "overspeed-grace-ticks must be > 0" }
            return MineRailsConfig(
                trainModelUpdatePolicy = policy,
                trainKeyBindDistance = bindDistance,
                trainDebugLog = requireBoolean(yaml, "train-debug-log"),
                physicsWorkerThreads = physicsWorkerThreads,
                overspeedDerailEnabled = requireBoolean(yaml, "overspeed-derail-enabled"),
                overspeedToleranceKmH = overspeedToleranceKmH,
                overspeedGraceTicks = overspeedGraceTicks
            )
        }

        private fun requireString(yaml: YamlConfiguration, path: String): String {
            require(yaml.contains(path, true)) { "Missing required config key '$path'" }
            return yaml.getString(path)
                ?: throw IllegalArgumentException("Config key '$path' must be a string")
        }

        private fun requireDouble(yaml: YamlConfiguration, path: String): Double {
            require(yaml.contains(path, true)) { "Missing required config key '$path'" }
            val raw = yaml.get(path)
            return (raw as? Number)?.toDouble()
                ?: throw IllegalArgumentException("Config key '$path' must be a number")
        }

        private fun requireInt(yaml: YamlConfiguration, path: String): Int {
            require(yaml.contains(path, true)) { "Missing required config key '$path'" }
            val raw = yaml.get(path)
            val number = raw as? Number
                ?: throw IllegalArgumentException("Config key '$path' must be an integer")
            val value = number.toInt()
            require(number.toDouble() == value.toDouble()) { "Config key '$path' must be an integer" }
            return value
        }

        private fun requireBoolean(yaml: YamlConfiguration, path: String): Boolean {
            require(yaml.contains(path, true)) { "Missing required config key '$path'" }
            val raw = yaml.get(path)
            return raw as? Boolean
                ?: throw IllegalArgumentException("Config key '$path' must be a boolean")
        }
    }
}

enum class TrainModelUpdatePolicy {
    FOLLOW_PACKAGE,
    SNAPSHOT;

    companion object {
        fun fromConfig(value: String): TrainModelUpdatePolicy {
            return when (value.trim().lowercase()) {
                "follow-package", "follow_package", "follow" -> FOLLOW_PACKAGE
                "snapshot", "freeze", "frozen" -> SNAPSHOT
                else -> throw IllegalArgumentException(
                    "Unsupported train-model-update-policy '$value'. Expected one of: follow-package, snapshot"
                )
            }
        }
    }
}
