package work.art1st.mineRails.bukkit.config

import org.bukkit.configuration.file.YamlConfiguration
import work.art1st.mineRails.bukkit.BukkitPlugin
import java.io.File

data class DisplayRendererConfig(
    val backend: DisplayRendererBackend,
    val playerDistance: PlayerDisplayDistanceConfig
)

data class TimetableHttpBridgeConfig(
    val enabled: Boolean,
    val bindAddress: String,
    val port: Int,
    val bearerTokenEnvironmentVariable: String,
    val bodyLimitBytes: Int,
    val timeoutMillis: Long
)

data class PlayerDisplayDistanceConfig(
    val default: PlayerDisplayDistanceDefault,
    val maxBlocks: Double
)

enum class DisplayRendererBackend {
    NATIVE,
    ENTITYLIB;

    companion object {
        fun fromConfig(value: String): DisplayRendererBackend = when (value.trim().lowercase()) {
            "native" -> NATIVE
            "entitylib", "packet", "packet-entity" -> ENTITYLIB
            else -> throw IllegalArgumentException(
                "Unsupported display-renderer.backend '$value'. Expected one of: native, entitylib"
            )
        }
    }
}

enum class PlayerDisplayDistanceDefault {
    PLAYER_VIEW_DISTANCE;

    companion object {
        fun fromConfig(value: String): PlayerDisplayDistanceDefault = when (value.trim().lowercase()) {
            "player-view-distance", "player_view_distance" -> PLAYER_VIEW_DISTANCE
            else -> throw IllegalArgumentException(
                "Unsupported display-renderer.player-distance.default '$value'. " +
                    "Expected: player-view-distance"
            )
        }
    }
}

data class MineRailsConfig(
    val trainModelUpdatePolicy: TrainModelUpdatePolicy,
    val trainKeyBindDistance: Double,
    val trainDebugLog: Boolean,
    val physicsWorkerThreads: Int,
    val overspeedDerailEnabled: Boolean,
    val derailLateralAccelerationMetersPerSecondSquared: Double,
    val overspeedToleranceKmH: Double,
    val overspeedGraceTicks: Int,
    val displayRenderer: DisplayRendererConfig,
    val timetableHttpBridge: TimetableHttpBridgeConfig
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
            val derailLateralAcceleration = requireDouble(yaml, "derail-lateral-acceleration-mps2")
            require(derailLateralAcceleration > 0.0) { "derail-lateral-acceleration-mps2 must be > 0.0" }
            val overspeedToleranceKmH = requireDouble(yaml, "overspeed-tolerance-kmh")
            require(overspeedToleranceKmH >= 0.0) { "overspeed-tolerance-kmh must be >= 0.0" }
            val overspeedGraceTicks = requireInt(yaml, "overspeed-grace-ticks")
            require(overspeedGraceTicks > 0) { "overspeed-grace-ticks must be > 0" }
            val displayRendererBackend = DisplayRendererBackend.fromConfig(
                requireString(yaml, "display-renderer.backend")
            )
            val playerDistanceDefault = PlayerDisplayDistanceDefault.fromConfig(
                requireString(yaml, "display-renderer.player-distance.default")
            )
            val maxDisplayDistance = requireDouble(yaml, "display-renderer.player-distance.max-blocks")
            require(maxDisplayDistance.isFinite() && maxDisplayDistance > 0.0) {
                "display-renderer.player-distance.max-blocks must be finite and > 0"
            }
            require(maxDisplayDistance <= MAX_DISPLAY_DISTANCE_BLOCKS) {
                "display-renderer.player-distance.max-blocks must be <= $MAX_DISPLAY_DISTANCE_BLOCKS"
            }
            val bridgePort = requireInt(yaml, "timetable-http-bridge.port")
            require(bridgePort in 1..65535) { "timetable-http-bridge.port must be in 1..65535" }
            val bridgeBodyLimit = requireInt(yaml, "timetable-http-bridge.body-limit-bytes")
            require(bridgeBodyLimit in 1..10_485_760) { "timetable-http-bridge.body-limit-bytes must be in 1..10485760" }
            val bridgeTimeoutMillis = requireLong(yaml, "timetable-http-bridge.timeout-millis")
            require(bridgeTimeoutMillis in 1L..60_000L) { "timetable-http-bridge.timeout-millis must be in 1..60000" }
            val bridgeBindAddress = requireString(yaml, "timetable-http-bridge.bind-address")
            require(bridgeBindAddress == "127.0.0.1" || bridgeBindAddress == "localhost" || bridgeBindAddress == "::1") {
                "timetable-http-bridge.bind-address must be loopback only"
            }
            val tokenEnv = requireString(yaml, "timetable-http-bridge.bearer-token-env")
            require(tokenEnv.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                "timetable-http-bridge.bearer-token-env must be an environment variable name"
            }
            return MineRailsConfig(
                trainModelUpdatePolicy = policy,
                trainKeyBindDistance = bindDistance,
                trainDebugLog = requireBoolean(yaml, "train-debug-log"),
                physicsWorkerThreads = physicsWorkerThreads,
                overspeedDerailEnabled = requireBoolean(yaml, "overspeed-derail-enabled"),
                derailLateralAccelerationMetersPerSecondSquared = derailLateralAcceleration,
                overspeedToleranceKmH = overspeedToleranceKmH,
                overspeedGraceTicks = overspeedGraceTicks,
                displayRenderer = DisplayRendererConfig(
                    backend = displayRendererBackend,
                    playerDistance = PlayerDisplayDistanceConfig(
                        default = playerDistanceDefault,
                        maxBlocks = maxDisplayDistance
                    )
                ),
                timetableHttpBridge = TimetableHttpBridgeConfig(
                    enabled = requireBoolean(yaml, "timetable-http-bridge.enabled"),
                    bindAddress = bridgeBindAddress,
                    port = bridgePort,
                    bearerTokenEnvironmentVariable = tokenEnv,
                    bodyLimitBytes = bridgeBodyLimit,
                    timeoutMillis = bridgeTimeoutMillis
                )
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

        private fun requireLong(yaml: YamlConfiguration, path: String): Long {
            require(yaml.contains(path, true)) { "Missing required config key '$path'" }
            val raw = yaml.get(path)
            val number = raw as? Number
                ?: throw IllegalArgumentException("Config key '$path' must be an integer")
            val value = number.toLong()
            require(number.toDouble() == value.toDouble()) { "Config key '$path' must be an integer" }
            return value
        }

        private const val MAX_DISPLAY_DISTANCE_BLOCKS = 4096.0
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
