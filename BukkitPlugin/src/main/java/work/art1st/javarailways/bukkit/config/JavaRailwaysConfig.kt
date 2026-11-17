package work.art1st.javarailways.bukkit.config

import org.bukkit.configuration.file.YamlConfiguration
import work.art1st.javarailways.bukkit.BukkitPlugin
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

data class JavaRailwaysConfig(
    val trainModelUpdatePolicy: TrainModelUpdatePolicy,
    val trainKeyBindDistance: Double,
    val trainDebugLog: Boolean,
    val displayRenderer: DisplayRendererConfig,
    val timetableHttpBridge: TimetableHttpBridgeConfig,
    val timetableWebUi: TimetableWebUiConfig,
    val atoSafetyDistanceMeters: Double = 5.0,
    val atoAdmissionRetryTicks: Int = 5,
    val passengerMovementEnabled: Boolean = true,
    val couplingMaximumEntryGapMeters: Double = 5.0,
    val couplingMaximumApproachSpeedMetersPerSecond: Double = 0.5
) {
    companion object {
        fun load(plugin: BukkitPlugin): JavaRailwaysConfig {
            val file = File(plugin.dataFolder, "config.yml")
            if (!file.exists()) {
                plugin.saveResource("config.yml", false)
            }

            val yaml = YamlConfiguration.loadConfiguration(file)
            return fromYaml(yaml)
        }

        fun fromYaml(yaml: YamlConfiguration): JavaRailwaysConfig {
            val retiredPhysicsKey = RETIRED_PHYSICS_KEYS.firstOrNull { yaml.contains(it, true) }
            require(retiredPhysicsKey == null) {
                "Config key '$retiredPhysicsKey' moved to physics.yml; run scripts/migrate_physics_config.py in dry-run mode first"
            }
            val policy = TrainModelUpdatePolicy.fromConfig(requireString(yaml, "train-model-update-policy"))
            val bindDistance = requireDouble(yaml, "train-key-bind-distance")
            require(bindDistance >= 0.0) { "train-key-bind-distance must be >= 0.0" }
            require(!yaml.contains("ato", true) || yaml.isConfigurationSection("ato")) {
                "Config key 'ato' must be a section"
            }
            // Bukkit removes YAML null values; they follow the same default as an absent key.
            val atoSafetyDistance = if (yaml.contains("ato.safety-distance-meters", true)) {
                requireDouble(yaml, "ato.safety-distance-meters")
            } else {
                5.0
            }
            require(atoSafetyDistance.isFinite() && atoSafetyDistance >= 0.0) {
                "ato.safety-distance-meters must be finite and >= 0.0"
            }
            val atoAdmissionRetryTicks = if (yaml.contains("ato.admission-retry-ticks", true)) {
                requireInt(yaml, "ato.admission-retry-ticks")
            } else {
                5
            }
            require(atoAdmissionRetryTicks > 0) { "ato.admission-retry-ticks must be > 0" }
            require(!yaml.contains("coupling", true) || yaml.isConfigurationSection("coupling")) {
                "Config key 'coupling' must be a section"
            }
            val couplingMaximumEntryGap = if (yaml.contains("coupling.maximum-entry-gap-meters", true)) {
                requireDouble(yaml, "coupling.maximum-entry-gap-meters")
            } else {
                5.0
            }
            require(couplingMaximumEntryGap.isFinite() && couplingMaximumEntryGap >= 0.0) {
                "coupling.maximum-entry-gap-meters must be finite and >= 0.0"
            }
            val couplingMaximumApproachSpeed = if (yaml.contains("coupling.maximum-approach-speed-meters-per-second", true)) {
                requireDouble(yaml, "coupling.maximum-approach-speed-meters-per-second")
            } else {
                0.5
            }
            require(couplingMaximumApproachSpeed.isFinite() && couplingMaximumApproachSpeed > 0.0) {
                "coupling.maximum-approach-speed-meters-per-second must be finite and > 0.0"
            }
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
            val webUiMode = TimetableWebUiMode.fromConfig(
                requireString(yaml, "timetable-webui.mode")
            )
            val webUiBindAddress = requireString(yaml, "timetable-webui.bind-address")
            require(webUiBindAddress.isNotBlank() && webUiBindAddress.none(Char::isWhitespace)) {
                "timetable-webui.bind-address must be a non-blank host or IP address"
            }
            val webUiPort = requireInt(yaml, "timetable-webui.port")
            require(webUiPort in 1..65535) { "timetable-webui.port must be in 1..65535" }
            val webUiPublicUrl = requireString(yaml, "timetable-webui.public-url").trimEnd('/')
            val publicUri = runCatching { URI.create(webUiPublicUrl) }.getOrElse {
                throw IllegalArgumentException("timetable-webui.public-url must be a valid URL", it)
            }
            require(publicUri.isAbsolute && publicUri.host != null && publicUri.scheme in setOf("http", "https") &&
                publicUri.rawQuery == null && publicUri.rawFragment == null
            ) { "timetable-webui.public-url must be an absolute http(s) URL without query or fragment" }
            val webUiShutdownTimeout = requireLong(
                yaml, "timetable-webui.shutdown-timeout-millis"
            )
            require(webUiShutdownTimeout in 1L..60_000L) {
                "timetable-webui.shutdown-timeout-millis must be in 1..60000"
            }
            val bridgeEnabled = requireBoolean(yaml, "timetable-http-bridge.enabled")
            require(webUiMode == TimetableWebUiMode.DISABLED || bridgeEnabled) {
                "timetable-http-bridge.enabled must be true when timetable-webui is managed or external"
            }
            return JavaRailwaysConfig(
                trainModelUpdatePolicy = policy,
                trainKeyBindDistance = bindDistance,
                trainDebugLog = requireBoolean(yaml, "train-debug-log"),
                passengerMovementEnabled = if (yaml.contains("passenger-movement-enabled", true)) {
                    requireBoolean(yaml, "passenger-movement-enabled")
                } else {
                    true
                },
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
                atoSafetyDistanceMeters = atoSafetyDistance,
                atoAdmissionRetryTicks = atoAdmissionRetryTicks,
                couplingMaximumEntryGapMeters = couplingMaximumEntryGap,
                couplingMaximumApproachSpeedMetersPerSecond = couplingMaximumApproachSpeed,
                timetableWebUi = TimetableWebUiConfig(
                    mode = webUiMode,
                    bindAddress = webUiBindAddress,
                    port = webUiPort,
                    publicUrl = webUiPublicUrl,
                    shutdownTimeoutMillis = webUiShutdownTimeout
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

        private val RETIRED_PHYSICS_KEYS = setOf(
            "physics-worker-threads",
            "overspeed-derail-enabled",
            "derail-lateral-acceleration-mps2",
            "overspeed-tolerance-kmh",
            "overspeed-grace-ticks",
            "turnout"
        )
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
