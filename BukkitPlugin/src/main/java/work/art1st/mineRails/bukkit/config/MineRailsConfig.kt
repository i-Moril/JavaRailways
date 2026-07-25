package work.art1st.mineRails.bukkit.config

import org.bukkit.configuration.file.YamlConfiguration
import work.art1st.mineRails.bukkit.BukkitPlugin
import java.io.File
import java.net.URI

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

enum class TimetableWebUiMode {
    MANAGED,
    EXTERNAL,
    DISABLED;

    companion object {
        fun fromConfig(value: String): TimetableWebUiMode = when (value.trim().lowercase()) {
            "managed" -> MANAGED
            "external" -> EXTERNAL
            "disabled" -> DISABLED
            else -> throw IllegalArgumentException(
                "Unsupported timetable-webui.mode '$value'. Expected managed, external, or disabled"
            )
        }
    }
}

data class TimetableWebUiConfig(
    val mode: TimetableWebUiMode,
    val bindAddress: String,
    val port: Int,
    val publicUrl: String,
    val shutdownTimeoutMillis: Long
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

data class TurnoutSafetyConfig(
    val defaultProtectionDistanceMeters: Double,
    val defaultLockingDistanceMeters: Double,
    val decisionLeadTicks: Int,
    val allocationRetryTicks: Int
)

data class MineRailsConfig(
    val trainModelUpdatePolicy: TrainModelUpdatePolicy,
    val trainKeyBindDistance: Double,
    val trainDebugLog: Boolean,
    val physicsWorkerThreads: Int,
    val overspeedDerailEnabled: Boolean,
    val derailLateralAccelerationMetersPerSecondSquared: Double,
    val overspeedToleranceKmH: Double,
    val overspeedGraceTicks: Int,
    val turnoutSafety: TurnoutSafetyConfig,
    val displayRenderer: DisplayRendererConfig,
    val timetableHttpBridge: TimetableHttpBridgeConfig,
    val timetableWebUi: TimetableWebUiConfig
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
            val turnoutProtection = optionalDouble(
                yaml, "turnout.default-protection-distance-meters", 20.0
            )
            val turnoutLocking = optionalDouble(
                yaml, "turnout.default-locking-distance-meters", 5.0
            )
            val turnoutLeadTicks = optionalInt(yaml, "turnout.decision-lead-ticks", 5)
            val turnoutRetryTicks = optionalInt(yaml, "turnout.allocation-retry-ticks", 5)
            require(turnoutProtection.isFinite() && turnoutProtection >= 0.0) {
                "turnout.default-protection-distance-meters must be finite and >= 0"
            }
            require(turnoutLocking.isFinite() && turnoutLocking >= 0.0 &&
                turnoutLocking <= turnoutProtection
            ) {
                "turnout.default-locking-distance-meters must be finite and between 0 and protection distance"
            }
            require(turnoutLeadTicks > 0) { "turnout.decision-lead-ticks must be > 0" }
            require(turnoutRetryTicks > 0) { "turnout.allocation-retry-ticks must be > 0" }
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
            val bridgePort = optionalInt(yaml, "timetable-http-bridge.port", 8765)
            require(bridgePort in 1..65535) { "timetable-http-bridge.port must be in 1..65535" }
            val bridgeBodyLimit = optionalInt(yaml, "timetable-http-bridge.body-limit-bytes", 1_048_576)
            require(bridgeBodyLimit in 1..10_485_760) { "timetable-http-bridge.body-limit-bytes must be in 1..10485760" }
            val bridgeTimeoutMillis = optionalLong(yaml, "timetable-http-bridge.timeout-millis", 5_000L)
            require(bridgeTimeoutMillis in 1L..60_000L) { "timetable-http-bridge.timeout-millis must be in 1..60000" }
            val bridgeBindAddress = optionalString(yaml, "timetable-http-bridge.bind-address", "127.0.0.1")
            require(bridgeBindAddress == "127.0.0.1" || bridgeBindAddress == "localhost" || bridgeBindAddress == "::1") {
                "timetable-http-bridge.bind-address must be loopback only"
            }
            val tokenEnv = optionalString(
                yaml,
                "timetable-http-bridge.bearer-token-env",
                "MINERAILS_TIMETABLE_HTTP_TOKEN"
            )
            require(tokenEnv.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                "timetable-http-bridge.bearer-token-env must be an environment variable name"
            }
            val webUiMode = TimetableWebUiMode.fromConfig(
                optionalString(yaml, "timetable-webui.mode", "managed")
            )
            val webUiBindAddress = optionalString(yaml, "timetable-webui.bind-address", "127.0.0.1")
            require(webUiBindAddress.isNotBlank() && webUiBindAddress.none(Char::isWhitespace)) {
                "timetable-webui.bind-address must be a non-blank host or IP address"
            }
            val webUiPort = optionalInt(yaml, "timetable-webui.port", 8080)
            require(webUiPort in 1..65535) { "timetable-webui.port must be in 1..65535" }
            val defaultPublicHost = if (webUiBindAddress == "::1") "[::1]" else webUiBindAddress
            val webUiPublicUrl = optionalString(
                yaml,
                "timetable-webui.public-url",
                "http://$defaultPublicHost:$webUiPort"
            ).trimEnd('/')
            val publicUri = runCatching { URI.create(webUiPublicUrl) }.getOrElse {
                throw IllegalArgumentException("timetable-webui.public-url must be a valid URL", it)
            }
            require(publicUri.isAbsolute && publicUri.host != null && publicUri.scheme in setOf("http", "https") &&
                publicUri.rawQuery == null && publicUri.rawFragment == null
            ) { "timetable-webui.public-url must be an absolute http(s) URL without query or fragment" }
            val webUiShutdownTimeout = optionalLong(
                yaml,
                "timetable-webui.shutdown-timeout-millis",
                5_000L
            )
            require(webUiShutdownTimeout in 1L..60_000L) {
                "timetable-webui.shutdown-timeout-millis must be in 1..60000"
            }
            val bridgeEnabled = optionalBoolean(yaml, "timetable-http-bridge.enabled", true)
            require(webUiMode == TimetableWebUiMode.DISABLED || bridgeEnabled) {
                "timetable-http-bridge.enabled must be true when timetable-webui is managed or external"
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
                turnoutSafety = TurnoutSafetyConfig(
                    defaultProtectionDistanceMeters = turnoutProtection,
                    defaultLockingDistanceMeters = turnoutLocking,
                    decisionLeadTicks = turnoutLeadTicks,
                    allocationRetryTicks = turnoutRetryTicks
                ),
                displayRenderer = DisplayRendererConfig(
                    backend = displayRendererBackend,
                    playerDistance = PlayerDisplayDistanceConfig(
                        default = playerDistanceDefault,
                        maxBlocks = maxDisplayDistance
                    )
                ),
                timetableHttpBridge = TimetableHttpBridgeConfig(
                    enabled = bridgeEnabled,
                    bindAddress = bridgeBindAddress,
                    port = bridgePort,
                    bearerTokenEnvironmentVariable = tokenEnv,
                    bodyLimitBytes = bridgeBodyLimit,
                    timeoutMillis = bridgeTimeoutMillis
                ),
                timetableWebUi = TimetableWebUiConfig(
                    mode = webUiMode,
                    bindAddress = webUiBindAddress,
                    port = webUiPort,
                    publicUrl = webUiPublicUrl,
                    shutdownTimeoutMillis = webUiShutdownTimeout
                )
            )
        }

        private fun optionalString(yaml: YamlConfiguration, path: String, fallback: String): String =
            if (yaml.contains(path, true)) requireString(yaml, path) else fallback

        private fun optionalInt(yaml: YamlConfiguration, path: String, fallback: Int): Int =
            if (yaml.contains(path, true)) requireInt(yaml, path) else fallback

        private fun optionalDouble(yaml: YamlConfiguration, path: String, fallback: Double): Double =
            if (yaml.contains(path, true)) requireDouble(yaml, path) else fallback

        private fun optionalLong(yaml: YamlConfiguration, path: String, fallback: Long): Long =
            if (yaml.contains(path, true)) requireLong(yaml, path) else fallback

        private fun optionalBoolean(yaml: YamlConfiguration, path: String, fallback: Boolean): Boolean =
            if (yaml.contains(path, true)) requireBoolean(yaml, path) else fallback

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
