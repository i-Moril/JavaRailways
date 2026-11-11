package work.art1st.javarailways.bukkit.config

import org.bukkit.configuration.file.YamlConfiguration
import work.art1st.javarailways.basic.curve.RailGeometryParameters
import work.art1st.javarailways.basic.curve.StandardTurnout
import work.art1st.javarailways.basic.curve.StandardTurnoutGeometry
import work.art1st.javarailways.basic.safety.RailSpeedPhysics
import work.art1st.javarailways.bukkit.BukkitPlugin
import java.io.File

data class TrackPhysicsConfig(
    val gaugeMeters: Double,
    val gravityMetersPerSecondSquared: Double,
    val maximumCantGradient: Double,
    val cantBalanceSpeedOffsetKmH: Double
)

data class GeometrySamplingConfig(
    val defaultStepMeters: Double,
    val tightRadiusUpperBoundMeters: Double,
    val tightStepMeters: Double,
    val mediumRadiusUpperBoundMeters: Double,
    val mediumStepMeters: Double,
    val wideRadiusUpperBoundMeters: Double,
    val wideStepMeters: Double,
    val veryWideStepMeters: Double,
    val sCurveTargetStepMeters: Double,
    val sCurveMinimumSamples: Int,
    val sCurveMinimumHandleFactor: Double,
    val sCurveMaximumHandleFactor: Double,
    val sCurveCurvatureProbeCount: Int,
    val turnoutStepMeters: Double
)

data class AutoRadiusConfig(
    val distanceFactor: Double,
    val incrementMeters: Double,
    val fallbackRadiiMeters: List<Double>
)

data class OverspeedPhysicsConfig(
    val derailEnabled: Boolean,
    val derailLateralAccelerationMetersPerSecondSquared: Double,
    val toleranceKmH: Double,
    val graceTicks: Int
)

data class PhysicsConfig(
    val schemaVersion: Int,
    val workerThreads: Int,
    val track: TrackPhysicsConfig,
    val geometrySampling: GeometrySamplingConfig,
    val autoRadius: AutoRadiusConfig,
    val standardTurnouts: Map<StandardTurnout, StandardTurnoutGeometry>,
    val overspeed: OverspeedPhysicsConfig
) {
    /** Capture configuration radii once; published geometry inputs are read-only and updated by replacement. */
    fun railGeometryParameters(): RailGeometryParameters = RailGeometryParameters(
        gaugeMeters = track.gaugeMeters,
        gravityMetersPerSecondSquared = track.gravityMetersPerSecondSquared,
        defaultStepMeters = geometrySampling.defaultStepMeters,
        tightRadiusUpperBoundMeters = geometrySampling.tightRadiusUpperBoundMeters,
        tightStepMeters = geometrySampling.tightStepMeters,
        mediumRadiusUpperBoundMeters = geometrySampling.mediumRadiusUpperBoundMeters,
        mediumStepMeters = geometrySampling.mediumStepMeters,
        wideRadiusUpperBoundMeters = geometrySampling.wideRadiusUpperBoundMeters,
        wideStepMeters = geometrySampling.wideStepMeters,
        veryWideStepMeters = geometrySampling.veryWideStepMeters,
        sCurveTargetStepMeters = geometrySampling.sCurveTargetStepMeters,
        sCurveMinimumSamples = geometrySampling.sCurveMinimumSamples,
        sCurveMinimumHandleFactor = geometrySampling.sCurveMinimumHandleFactor,
        sCurveMaximumHandleFactor = geometrySampling.sCurveMaximumHandleFactor,
        sCurveCurvatureProbeCount = geometrySampling.sCurveCurvatureProbeCount,
        turnoutStepMeters = geometrySampling.turnoutStepMeters,
        autoRadiusDistanceFactor = autoRadius.distanceFactor,
        autoRadiusIncrementMeters = autoRadius.incrementMeters,
        autoRadiusFallbackRadiiMeters = autoRadius.fallbackRadiiMeters.toList()
    )

    fun railSpeedPhysics(): RailSpeedPhysics = RailSpeedPhysics(
        gaugeMeters = track.gaugeMeters,
        gravityMetersPerSecondSquared = track.gravityMetersPerSecondSquared
    )

    fun turnoutGeometry(standard: StandardTurnout): StandardTurnoutGeometry =
        standardTurnouts.getValue(standard)

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2

        fun load(plugin: BukkitPlugin): PhysicsConfig {
            val file = File(plugin.dataFolder, "physics.yml")
            if (!file.exists()) plugin.saveResource("physics.yml", false)
            return fromYaml(YamlConfiguration.loadConfiguration(file))
        }

        fun fromYaml(yaml: YamlConfiguration): PhysicsConfig {
            val schemaVersion = requireInt(yaml, "schema-version")
            require(schemaVersion == CURRENT_SCHEMA_VERSION) {
                "Unsupported physics.yml schema-version $schemaVersion; expected $CURRENT_SCHEMA_VERSION"
            }
            require(!yaml.contains("track.gravity-mps2", true)) {
                "Removed physics config key 'track.gravity-mps2'; use 'track.gravity'"
            }
            val workerThreads = requireInt(yaml, "worker-threads")
            require(workerThreads > 0) { "worker-threads must be > 0" }

            val gauge = requirePositiveDouble(yaml, "track.gauge-meters")
            val gravity = requirePositiveDouble(yaml, "track.gravity")
            val maximumCantGradient = requirePositiveDouble(yaml, "track.maximum-cant-gradient")
            require(maximumCantGradient <= 1.0) { "track.maximum-cant-gradient must be <= 1.0" }
            val cantBalanceOffset = requireNonNegativeDouble(yaml, "track.cant-balance-speed-offset-kmh")

            val defaultStep = requirePositiveDouble(yaml, "geometry-sampling.default-step-meters")
            val tightRadius = requirePositiveDouble(yaml, "geometry-sampling.adaptive.tight-radius-upper-bound-meters")
            val mediumRadius = requirePositiveDouble(yaml, "geometry-sampling.adaptive.medium-radius-upper-bound-meters")
            val wideRadius = requirePositiveDouble(yaml, "geometry-sampling.adaptive.wide-radius-upper-bound-meters")
            require(tightRadius < mediumRadius && mediumRadius < wideRadius) {
                "adaptive radius upper bounds must be strictly increasing"
            }
            val sampling = GeometrySamplingConfig(
                defaultStepMeters = defaultStep,
                tightRadiusUpperBoundMeters = tightRadius,
                tightStepMeters = requirePositiveDouble(yaml, "geometry-sampling.adaptive.tight-step-meters"),
                mediumRadiusUpperBoundMeters = mediumRadius,
                mediumStepMeters = requirePositiveDouble(yaml, "geometry-sampling.adaptive.medium-step-meters"),
                wideRadiusUpperBoundMeters = wideRadius,
                wideStepMeters = requirePositiveDouble(yaml, "geometry-sampling.adaptive.wide-step-meters"),
                veryWideStepMeters = requirePositiveDouble(yaml, "geometry-sampling.adaptive.very-wide-step-meters"),
                sCurveTargetStepMeters = requirePositiveDouble(yaml, "geometry-sampling.s-curve-target-step-meters"),
                sCurveMinimumSamples = requireInt(yaml, "geometry-sampling.s-curve-minimum-samples").also {
                    require(it >= 2) { "geometry-sampling.s-curve-minimum-samples must be >= 2" }
                },
                sCurveMinimumHandleFactor = requirePositiveDouble(
                    yaml, "geometry-sampling.s-curve-minimum-handle-factor"
                ),
                sCurveMaximumHandleFactor = requirePositiveDouble(
                    yaml, "geometry-sampling.s-curve-maximum-handle-factor"
                ),
                sCurveCurvatureProbeCount = requireInt(
                    yaml, "geometry-sampling.s-curve-curvature-probe-count"
                ).also { require(it >= 2) { "s-curve-curvature-probe-count must be >= 2" } },
                turnoutStepMeters = requirePositiveDouble(yaml, "geometry-sampling.turnout-step-meters")
            ).also {
                require(it.sCurveMaximumHandleFactor >= it.sCurveMinimumHandleFactor) {
                    "s-curve maximum handle factor must be >= minimum handle factor"
                }
            }


            val fallbackRadii = requireDoubleList(yaml, "auto-radius.fallback-radii-meters")
            require(fallbackRadii.isNotEmpty() && fallbackRadii.all { it > 0.0 } &&
                fallbackRadii.zipWithNext().all { (a, b) -> a > b }
            ) { "auto-radius.fallback-radii-meters must be a non-empty descending list of positive numbers" }
            val autoRadius = AutoRadiusConfig(
                distanceFactor = requirePositiveDouble(yaml, "auto-radius.distance-factor"),
                incrementMeters = requirePositiveDouble(yaml, "auto-radius.increment-meters"),
                fallbackRadiiMeters = fallbackRadii
            )

            val standardTurnouts = StandardTurnout.entries.associateWith { standard ->
                val base = "standard-turnouts.${standard.number}"
                StandardTurnoutGeometry(
                    number = standard.number,
                    radiusMeters = requirePositiveDouble(yaml, "$base.radius-meters"),
                    divergingSpeedKmH = requirePositiveDouble(yaml, "$base.diverging-speed-kmh")
                )
            }

            val derailAcceleration = requirePositiveDouble(
                yaml, "overspeed.derail-lateral-acceleration-mps2"
            )
            val overspeed = OverspeedPhysicsConfig(
                derailEnabled = requireBoolean(yaml, "overspeed.derail-enabled"),
                derailLateralAccelerationMetersPerSecondSquared = derailAcceleration,
                toleranceKmH = requireNonNegativeDouble(yaml, "overspeed.tolerance-kmh"),
                graceTicks = requireInt(yaml, "overspeed.grace-ticks").also {
                    require(it > 0) { "overspeed.grace-ticks must be > 0" }
                }
            )

            return PhysicsConfig(
                schemaVersion = schemaVersion,
                workerThreads = workerThreads,
                track = TrackPhysicsConfig(gauge, gravity, maximumCantGradient, cantBalanceOffset),
                geometrySampling = sampling,
                autoRadius = autoRadius,
                standardTurnouts = standardTurnouts,
                overspeed = overspeed
            )
        }

        private fun requireValue(yaml: YamlConfiguration, path: String): Any {
            require(yaml.contains(path, true)) { "Missing required physics config key '$path'" }
            return yaml.get(path) ?: throw IllegalArgumentException("Physics config key '$path' must not be null")
        }

        private fun requireInt(yaml: YamlConfiguration, path: String): Int {
            val number = requireValue(yaml, path) as? Number
                ?: throw IllegalArgumentException("Physics config key '$path' must be an integer")
            val value = number.toInt()
            require(number.toDouble() == value.toDouble()) { "Physics config key '$path' must be an integer" }
            return value
        }

        private fun requireDouble(yaml: YamlConfiguration, path: String): Double {
            val value = (requireValue(yaml, path) as? Number)?.toDouble()
                ?: throw IllegalArgumentException("Physics config key '$path' must be a number")
            require(value.isFinite()) { "Physics config key '$path' must be finite" }
            return value
        }

        private fun requirePositiveDouble(yaml: YamlConfiguration, path: String): Double =
            requireDouble(yaml, path).also { require(it > 0.0) { "$path must be > 0" } }

        private fun requireNonNegativeDouble(yaml: YamlConfiguration, path: String): Double =
            requireDouble(yaml, path).also { require(it >= 0.0) { "$path must be >= 0" } }

        private fun requireBoolean(yaml: YamlConfiguration, path: String): Boolean =
            requireValue(yaml, path) as? Boolean
                ?: throw IllegalArgumentException("Physics config key '$path' must be a boolean")

        private fun requireDoubleList(yaml: YamlConfiguration, path: String): List<Double> {
            val raw = requireValue(yaml, path) as? List<*>
                ?: throw IllegalArgumentException("Physics config key '$path' must be a list")
            return raw.mapIndexed { index, value ->
                ((value as? Number)?.toDouble()
                    ?: throw IllegalArgumentException("Physics config key '$path[$index]' must be a number"))
                    .also { require(it.isFinite()) { "Physics config key '$path[$index]' must be finite" } }
            }
        }
    }
}
