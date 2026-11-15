package work.art1st.javarailways.bukkit.config

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.curve.StandardTurnout
import java.io.InputStreamReader

class PhysicsConfigTest {
    @Test
    fun `bundled physics config exposes engineering defaults`() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/physics.yml"))
        val yaml = stream.use { YamlConfiguration.loadConfiguration(InputStreamReader(it)) }

        val config = PhysicsConfig.fromYaml(yaml)

        assertEquals(2, config.schemaVersion)
        assertEquals(1.0, config.railSpeedPhysics().curveSpeedLimitMultiplier)
        assertEquals(1.435, config.track.gaugeMeters)
        assertEquals(9.80665, config.track.gravityMetersPerSecondSquared)
        assertEquals(1.0 / 300.0, config.track.maximumCantGradient, 1.0e-12)
        assertEquals(10.0, config.track.cantBalanceSpeedOffsetKmH)
        assertEquals(4, config.workerThreads)
        assertEquals(3.0, config.overspeed.derailLateralAccelerationMetersPerSecondSquared)
        assertEquals(0.2, config.geometrySampling.defaultStepMeters)
        assertEquals(listOf(40.0, 30.0, 20.0, 10.0), config.autoRadius.fallbackRadiiMeters)
        assertEquals(180.0, config.turnoutGeometry(StandardTurnout.N9).radiusMeters)
        assertEquals(30.0, config.turnoutGeometry(StandardTurnout.N9).divergingSpeedKmH)
    }

    @Test
    fun `curve speed multiplier defaults to one and reaches calculator physics`() {
        assertEquals(1.0, PhysicsConfig.fromYaml(validYaml()).railSpeedPhysics().curveSpeedLimitMultiplier)
        val yaml = validYaml().apply { set("track.curve-speed-limit-multiplier", 1.5) }
        assertEquals(1.5, PhysicsConfig.fromYaml(yaml).railSpeedPhysics().curveSpeedLimitMultiplier)
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, "fast").forEach { invalid ->
            yaml.set("track.curve-speed-limit-multiplier", invalid)
            assertThrows(IllegalArgumentException::class.java) { PhysicsConfig.fromYaml(yaml) }
        }
    }

    @Test
    fun `geometry capture detaches configuration radii and updates by replacement`() {
        val radii = mutableListOf(40.0, 30.0, 20.0, 10.0)
        val loaded = PhysicsConfig.fromYaml(validYaml())
        val config = loaded.copy(autoRadius = loaded.autoRadius.copy(fallbackRadiiMeters = radii))
        val geometry = config.railGeometryParameters()

        radii[0] = 50.0
        val replacement = config.railGeometryParameters()

        assertEquals(listOf(40.0, 30.0, 20.0, 10.0), geometry.autoRadiusFallbackRadiiMeters)
        assertEquals(listOf(50.0, 30.0, 20.0, 10.0), replacement.autoRadiusFallbackRadiiMeters)
        assertThrows(IllegalArgumentException::class.java) {
            geometry.copy(autoRadiusFallbackRadiiMeters = listOf(10.0, 20.0))
        }
    }

    @Test
    fun `physics config rejects unsupported schema and invalid gradient`() {
        val unsupported = validYaml().apply { set("schema-version", 1) }
        val invalidGradient = validYaml().apply { set("track.maximum-cant-gradient", 0.0) }

        assertThrows(IllegalArgumentException::class.java) { PhysicsConfig.fromYaml(unsupported) }
        assertThrows(IllegalArgumentException::class.java) { PhysicsConfig.fromYaml(invalidGradient) }
    }

    @Test
    fun `physics config requires strictly increasing adaptive radii`() {
        val yaml = validYaml().apply {
            set("geometry-sampling.adaptive.medium-radius-upper-bound-meters", 20.0)
        }

        assertThrows(IllegalArgumentException::class.java) { PhysicsConfig.fromYaml(yaml) }
    }

    @Test
    fun `physics config rejects the removed gravity-mps2 key`() {
        val yaml = validYaml().apply {
            set("track.gravity-mps2", 9.80665)
        }

        assertThrows(IllegalArgumentException::class.java) { PhysicsConfig.fromYaml(yaml) }
    }

    private fun validYaml(): YamlConfiguration = YamlConfiguration().apply {
        set("schema-version", 2)
        set("worker-threads", 4)
        set("track.gauge-meters", 1.435)
        set("track.gravity", 9.80665)
        set("track.maximum-cant-gradient", 1.0 / 300.0)
        set("track.cant-balance-speed-offset-kmh", 10.0)
        set("geometry-sampling.default-step-meters", 0.2)
        set("geometry-sampling.adaptive.tight-radius-upper-bound-meters", 30.0)
        set("geometry-sampling.adaptive.tight-step-meters", 0.02)
        set("geometry-sampling.adaptive.medium-radius-upper-bound-meters", 100.0)
        set("geometry-sampling.adaptive.medium-step-meters", 0.05)
        set("geometry-sampling.adaptive.wide-radius-upper-bound-meters", 300.0)
        set("geometry-sampling.adaptive.wide-step-meters", 0.1)
        set("geometry-sampling.adaptive.very-wide-step-meters", 0.2)
        set("geometry-sampling.s-curve-target-step-meters", 0.4)
        set("geometry-sampling.s-curve-minimum-samples", 24)
        set("geometry-sampling.s-curve-minimum-handle-factor", 0.6)
        set("geometry-sampling.s-curve-maximum-handle-factor", 1.5)
        set("geometry-sampling.s-curve-curvature-probe-count", 100)
        set("geometry-sampling.turnout-step-meters", 0.2)
        set("auto-radius.distance-factor", 5.0)
        set("auto-radius.increment-meters", 50.0)
        set("auto-radius.fallback-radii-meters", listOf(40.0, 30.0, 20.0, 10.0))
        TURNOUT_DEFAULTS.forEach { (number, radius, speed) ->
            set("standard-turnouts.$number.radius-meters", radius)
            set("standard-turnouts.$number.diverging-speed-kmh", speed)
        }
        set("overspeed.derail-enabled", false)
        set("overspeed.derail-lateral-acceleration-mps2", 3.0)
        set("overspeed.tolerance-kmh", 5.0)
        set("overspeed.grace-ticks", 5)
    }

    companion object {
        private val TURNOUT_DEFAULTS = listOf(
            Triple(4, 30.0, 10.0), Triple(5, 50.0, 15.0), Triple(6, 80.0, 20.0),
            Triple(7, 100.0, 20.0), Triple(8, 145.0, 25.0), Triple(9, 180.0, 30.0),
            Triple(10, 220.0, 35.0), Triple(12, 330.0, 45.0), Triple(14, 500.0, 55.0),
            Triple(16, 700.0, 65.0), Triple(18, 1100.0, 80.0), Triple(20, 1400.0, 90.0),
            Triple(24, 1600.0, 100.0), Triple(30, 2700.0, 140.0), Triple(38, 4000.0, 150.0),
            Triple(42, 5000.0, 160.0), Triple(60, 10000.0, 200.0)
        )
    }
}
