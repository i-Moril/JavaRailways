package work.art1st.javarailways.bukkit.config.model

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import work.art1st.javarailways.basic.control.TrainDynamics
import org.junit.jupiter.api.Test

class ControlNumericValidationTest {
    @Test
    fun `train parser rejects negative and nonfinite values without changing valid zero semantics`() {
        val root = trainRoot()
        val zero = TrainControlConfig.parse(root)
        assertEquals(0.0, zero.maxSpeed)
        assertEquals(0.0, zero.brakeApplyTime)
        assertEquals(0.0, zero.brakeReleaseTime)
        for (invalid in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { TrainControlConfig.parse(root + ("max-speed" to invalid)) }
            for (key in listOf("apply-time", "release-time")) {
                assertThrows(IllegalArgumentException::class.java) {
                    TrainControlConfig.parse(root + ("brake" to mapOf(
                        "notch-scales" to mapOf("B1" to 1.0), "apply-time" to 0.0, "release-time" to 0.0,
                        key to invalid)))
                }
            }
            for (key in listOf("traction", "brake")) {
                assertThrows(IllegalArgumentException::class.java) {
                    TrainControlConfig.parse(root + ("jerk" to mapOf("traction" to 0.0, "brake" to 0.0, key to invalid)))
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                TrainControlConfig.parse(root + ("adhesion" to mapOf("coefficient" to invalid)))
            }
            assertThrows(IllegalArgumentException::class.java) {
                TrainControlConfig.parse(root + ("traction" to mapOf("notch-scales" to mapOf("P1" to invalid))))
            }
        }
        // A signed curve offset remains legal.
        assertEquals(-3.0, TrainControlConfig.parse(root + ("curve-speed-limit-offset" to -3.0)).curveSpeedLimitOffset)
    }

    @Test
    fun `car parser rejects invalid masses forces powers and coefficients`() {
        val root = carRoot()
        assertEquals(0.5, CarControlConfig.parse(root).effectiveMass)
        for (key in listOf("mass", "rotating-mass-factor")) {
            for (invalid in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
                assertThrows(IllegalArgumentException::class.java) { CarControlConfig.parse(root + (key to invalid)) }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CarControlConfig.parse(root + mapOf("mass" to Double.MAX_VALUE, "rotating-mass-factor" to 2.0))
        }
        for ((section, fields) in mapOf(
            "motor" to listOf("max-force", "max-power", "adhesion-mass-ratio"),
            "brake" to listOf("max-force", "regenerative-force", "adhesion-mass-ratio"))) {
            val values = root.requireMap(section, "test car")
            for (key in fields) for (invalid in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
                assertThrows(IllegalArgumentException::class.java) {
                    CarControlConfig.parse(root + (section to (values + (key to invalid))))
                }
            }
        }
        for (key in listOf("speed", "force")) {
            assertThrows(IllegalArgumentException::class.java) {
                MotorControlConfig.parse(mapOf("enabled" to true, "max-force" to 0.0, "max-power" to 0.0,
                    "adhesion-mass-ratio" to 0.0, "force-curve" to listOf(mapOf("speed" to 0.0, "force" to 0.0, key to -1.0))))
            }
        }
    }

    @Test
    fun `constructor invariants also reject invalid snapshot values`() {
        assertThrows(IllegalArgumentException::class.java) {
            Json.decodeFromString<TrainControlConfig>("""{"maxSpeed":-1} """)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Json.decodeFromString<CarControlConfig>("""{"mass":0} """)
        }
        assertThrows(IllegalArgumentException::class.java) { PartPlatformConfig(rows = 0) }
        assertThrows(IllegalArgumentException::class.java) { PartPlatformConfig(columns = -1) }
    }

    @Test
    fun `platform parser rejects nonpositive grid and keeps exact positive grid`() {
        for (key in listOf("rows", "columns")) for (invalid in listOf(0, -1)) {
            assertThrows(IllegalArgumentException::class.java) { PartRegistry.parsePlatformConfig(mapOf(key to invalid)) }
        }
        val platform = PartRegistry.parsePlatformConfig(mapOf("rows" to 2, "columns" to 3, "spacing-x" to 0.5))
        assertEquals(6, platform.cellOffsets().size)
        assertEquals(-0.25, platform.cellOffsets().first().x)
    }

    @Test
    fun `zero power remains constant force mode and zero coefficients remain legal`() {
        assertEquals(100.0, MotorControlConfig(enabled = true, maxForce = 100.0, maxPower = 0.0).forceAt(10.0))
        assertEquals(0.0, CarControlConfig.parse(carRoot()).motor.forceAt(10.0))
        assertEquals(0.0, DavisResistanceConfig(0.0, 0.0, 0.0).forceAt(20.0))
    }

    @Test
    fun `equipment optional defaults and per-car overrides reach the shared dynamics contract`() {
        val base = carRoot()
        assertEquals(TrainDynamics.Equipment(), CarControlConfig.parse(base).equipment)
        val equipment = mapOf("nominal-cylinder-pressure-kpa" to 420.0, "command-delay-seconds" to 0.25,
            "electric-brake-max-power-watts" to 650000.0, "electric-brake-fade-speed-meters-per-second" to 4.0,
            "electric-brake-apply-rate-newtons-per-second" to 85000.0, "electric-brake-release-rate-newtons-per-second" to 110000.0,
            "traction-apply-rate-newtons-per-second" to 95000.0, "traction-release-rate-newtons-per-second" to 190000.0,
            "traction-release-pressure-kpa" to 8.0, "electric-release-force-threshold-newtons" to 2.0)
        val control = CarControlConfig.parse(base + mapOf("equipment" to equipment,
            "brake" to (base.requireMap("brake", "test") + ("regenerative-force" to 30000.0))))
        assertEquals(TrainDynamics.Equipment(420.0, 0.25, 650000.0, 4.0, 85000.0, 110000.0, 95000.0, 190000.0, 8.0, 2.0), control.equipment)
        assertEquals(30000.0, control.electricBrakeMaxForce)
        assertEquals(1.2, TrainControlConfig().brakeApplyTime)
        assertEquals(1.8, TrainControlConfig().brakeReleaseTime)
    }

    @Test
    fun `equipment parser validates numeric bounds unknown keys and partial overrides`() {
        val keys = listOf("nominal-cylinder-pressure-kpa", "command-delay-seconds", "electric-brake-max-power-watts",
            "electric-brake-fade-speed-meters-per-second", "electric-brake-apply-rate-newtons-per-second",
            "electric-brake-release-rate-newtons-per-second", "traction-apply-rate-newtons-per-second",
            "traction-release-rate-newtons-per-second", "traction-release-pressure-kpa", "electric-release-force-threshold-newtons")
        for (key in keys) for (invalid in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { CarControlConfig.parse(carRoot() + ("equipment" to mapOf(key to invalid))) }
        }
        for (key in listOf("nominal-cylinder-pressure-kpa", "electric-brake-apply-rate-newtons-per-second",
            "electric-brake-release-rate-newtons-per-second", "traction-apply-rate-newtons-per-second", "traction-release-rate-newtons-per-second")) {
            assertThrows(IllegalArgumentException::class.java) { CarControlConfig.parse(carRoot() + ("equipment" to mapOf(key to 0.0))) }
        }
        assertThrows(IllegalArgumentException::class.java) { CarControlConfig.parse(carRoot() + ("equipment" to mapOf("traction-release-pressure-kpa" to 351.0))) }
        assertThrows(IllegalArgumentException::class.java) { CarControlConfig.parse(carRoot() + ("equipment" to mapOf("command-delai-seconds" to 0.1))) }
        assertThrows(IllegalArgumentException::class.java) { CarControlConfig.parse(carRoot() + ("equipment" to mapOf("command-delay-seconds" to "0.1"))) }
        val zeroDelay = CarControlConfig.parse(carRoot() + ("equipment" to mapOf("command-delay-seconds" to 0.0)))
        assertEquals(TrainDynamics.Equipment(commandDelaySeconds = 0.0), zeroDelay.equipment)
        assertThrows(IllegalArgumentException::class.java) { Json.decodeFromString<CarControlConfig>("""{"equipment":{"nominalCylinderPressureKpa":0}}""") }
    }

    private fun trainRoot(): Map<String, Any> = mapOf(
        "config-version" to 1, "max-speed" to 0.0,
        "controller" to mapOf("power-notches" to listOf("P1"), "brake-notches" to listOf("B1")),
        "traction" to mapOf("notch-scales" to mapOf("P1" to 0.0)),
        "brake" to mapOf("notch-scales" to mapOf("B1" to 0.0), "apply-time" to 0.0, "release-time" to 0.0),
        "resistance" to mapOf("a" to 0.0, "b" to 0.0, "c" to 0.0),
        "adhesion" to mapOf("coefficient" to 0.0), "jerk" to mapOf("traction" to 0.0, "brake" to 0.0))

    private fun carRoot(): Map<String, Any> = mapOf(
        "mass" to 0.5, "rotating-mass-factor" to 1.0,
        "motor" to mapOf("enabled" to true, "max-force" to 0.0, "max-power" to 0.0, "adhesion-mass-ratio" to 0.0),
        "brake" to mapOf("max-force" to 0.0, "regenerative-force" to 0.0, "adhesion-mass-ratio" to 0.0))
}
