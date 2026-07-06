package work.art1st.mineRails.bukkit.config.model

import kotlinx.serialization.Serializable

@Serializable
data class TrainControlConfig(
    val maxSpeed: Double = 20.0,
    val powerNotches: List<String> = listOf("P1", "P2", "P3", "P4", "P5"),
    val brakeNotches: List<String> = listOf("B1", "B2", "B3", "B4", "B5", "B6", "B7", "EB"),
    val tractionNotchScales: Map<String, Double> = defaultPowerScales(),
    val brakeNotchScales: Map<String, Double> = defaultBrakeScales(),
    val brakeApplyTime: Double = 1.2,
    val brakeReleaseTime: Double = 1.8,
    val resistance: DavisResistanceConfig = DavisResistanceConfig(),
    val adhesion: AdhesionConfig = AdhesionConfig(),
    val jerk: JerkLimitConfig = JerkLimitConfig()
) {
    fun normalizedPowerScale(handle: String): Double = tractionNotchScales[handle.uppercase()] ?: 0.0

    fun normalizedBrakeScale(handle: String): Double = brakeNotchScales[handle.uppercase()] ?: 0.0

    companion object {
        fun defaultPowerScales(): Map<String, Double> = linkedMapOf(
            "P1" to 0.20,
            "P2" to 0.40,
            "P3" to 0.60,
            "P4" to 0.80,
            "P5" to 1.00
        )

        fun defaultBrakeScales(): Map<String, Double> = linkedMapOf(
            "B1" to 0.15,
            "B2" to 0.30,
            "B3" to 0.45,
            "B4" to 0.60,
            "B5" to 0.75,
            "B6" to 0.90,
            "B7" to 1.00,
            "EB" to 1.25
        )

        fun parse(root: Map<String, Any>?): TrainControlConfig {
            if (root == null) return TrainControlConfig()
            val controller = root.map("controller")
            val traction = root.map("traction")
            val brake = root.map("brake")
            return TrainControlConfig(
                maxSpeed = root.double("max-speed") ?: 20.0,
                powerNotches = controller.stringList("power-notches") ?: TrainControlConfig().powerNotches,
                brakeNotches = controller.stringList("brake-notches") ?: TrainControlConfig().brakeNotches,
                tractionNotchScales = traction.doubleMap("notch-scales") ?: defaultPowerScales(),
                brakeNotchScales = brake.doubleMap("notch-scales") ?: defaultBrakeScales(),
                brakeApplyTime = brake.double("apply-time") ?: 1.2,
                brakeReleaseTime = brake.double("release-time") ?: 1.8,
                resistance = DavisResistanceConfig.parse(root.map("resistance")),
                adhesion = AdhesionConfig.parse(root.map("adhesion")),
                jerk = JerkLimitConfig.parse(root.map("jerk"))
            )
        }
    }
}

@Serializable
data class CarControlConfig(
    val mass: Double = 32_000.0,
    val rotatingMassFactor: Double = 1.08,
    val motor: MotorControlConfig = MotorControlConfig(),
    val brake: CarBrakeControlConfig = CarBrakeControlConfig(),
    val resistance: DavisResistanceConfig? = null
) {
    val effectiveMass: Double get() = mass * rotatingMassFactor

    companion object {
        fun parse(root: Map<String, Any>?): CarControlConfig {
            if (root == null) return CarControlConfig()
            return CarControlConfig(
                mass = root.double("mass") ?: 32_000.0,
                rotatingMassFactor = root.double("rotating-mass-factor") ?: 1.08,
                motor = MotorControlConfig.parse(root.map("motor")),
                brake = CarBrakeControlConfig.parse(root.map("brake")),
                resistance = root.map("resistance")?.let { DavisResistanceConfig.parse(it) }
            )
        }
    }
}

@Serializable
data class MotorControlConfig(
    val enabled: Boolean = false,
    val maxForce: Double = 0.0,
    val maxPower: Double = 0.0,
    val adhesionMassRatio: Double = 1.0,
    val forceCurve: List<SpeedForcePoint> = emptyList()
) {
    fun forceAt(speed: Double): Double {
        if (!enabled) return 0.0
        if (forceCurve.isNotEmpty()) return interpolateForceCurve(speed)
        if (maxPower <= 0.0) return maxForce.coerceAtLeast(0.0)
        val powerLimitedForce = maxPower / speed.coerceAtLeast(0.5)
        return minOf(maxForce.coerceAtLeast(0.0), powerLimitedForce)
    }

    private fun interpolateForceCurve(speed: Double): Double {
        val points = forceCurve.sortedBy { it.speed }
        if (speed <= points.first().speed) return points.first().force
        if (speed >= points.last().speed) return points.last().force
        val upperIndex = points.indexOfFirst { it.speed >= speed }
        val lower = points[upperIndex - 1]
        val upper = points[upperIndex]
        val ratio = (speed - lower.speed) / (upper.speed - lower.speed).coerceAtLeast(1.0e-9)
        return lower.force + (upper.force - lower.force) * ratio
    }

    companion object {
        fun parse(root: Map<String, Any>?): MotorControlConfig {
            if (root == null) return MotorControlConfig()
            return MotorControlConfig(
                enabled = root.boolean("enabled") ?: false,
                maxForce = root.double("max-force") ?: 0.0,
                maxPower = root.double("max-power") ?: 0.0,
                adhesionMassRatio = root.double("adhesion-mass-ratio") ?: 1.0,
                forceCurve = root.list("force-curve")?.mapNotNull { entry ->
                    (entry as? Map<*, *>)?.stringKeyMap()?.let { SpeedForcePoint.parse(it) }
                } ?: emptyList()
            )
        }
    }
}

@Serializable
data class CarBrakeControlConfig(
    val maxForce: Double = 45_000.0,
    val regenerativeForce: Double = 0.0,
    val adhesionMassRatio: Double = 1.0
) {
    companion object {
        fun parse(root: Map<String, Any>?): CarBrakeControlConfig {
            if (root == null) return CarBrakeControlConfig()
            return CarBrakeControlConfig(
                maxForce = root.double("max-force") ?: 45_000.0,
                regenerativeForce = root.double("regenerative-force") ?: 0.0,
                adhesionMassRatio = root.double("adhesion-mass-ratio") ?: 1.0
            )
        }
    }
}

@Serializable
data class DavisResistanceConfig(
    val a: Double = 1_200.0,
    val b: Double = 20.0,
    val c: Double = 4.0
) {
    fun forceAt(speed: Double): Double = a + b * speed + c * speed * speed

    companion object {
        fun parse(root: Map<String, Any>?): DavisResistanceConfig {
            if (root == null) return DavisResistanceConfig()
            return DavisResistanceConfig(
                a = root.double("a") ?: 1_200.0,
                b = root.double("b") ?: 20.0,
                c = root.double("c") ?: 4.0
            )
        }
    }
}

@Serializable
data class AdhesionConfig(
    val coefficient: Double = 0.22
) {
    companion object {
        fun parse(root: Map<String, Any>?): AdhesionConfig {
            if (root == null) return AdhesionConfig()
            return AdhesionConfig(
                coefficient = root.double("coefficient") ?: 0.22
            )
        }
    }
}

@Serializable
data class JerkLimitConfig(
    val traction: Double = 0.7,
    val brake: Double = 1.0
) {
    companion object {
        fun parse(root: Map<String, Any>?): JerkLimitConfig {
            if (root == null) return JerkLimitConfig()
            return JerkLimitConfig(
                traction = root.double("traction") ?: 0.7,
                brake = root.double("brake") ?: 1.0
            )
        }
    }
}

@Serializable
data class SpeedForcePoint(
    val speed: Double,
    val force: Double
) {
    companion object {
        fun parse(root: Map<String, Any>): SpeedForcePoint? {
            val speed = root.double("speed") ?: return null
            val force = root.double("force") ?: return null
            return SpeedForcePoint(speed, force)
        }
    }
}

internal fun Map<String, Any>?.double(key: String): Double? = (this?.get(key) as? Number)?.toDouble()
internal fun Map<String, Any>?.boolean(key: String): Boolean? = this?.get(key) as? Boolean
internal fun Map<String, Any>?.map(key: String): Map<String, Any>? = (this?.get(key) as? Map<*, *>)?.stringKeyMap()
internal fun Map<String, Any>?.list(key: String): List<*>? = this?.get(key) as? List<*>
internal fun Map<String, Any>?.stringList(key: String): List<String>? = list(key)?.mapNotNull { it as? String }
internal fun Map<String, Any>?.doubleMap(key: String): Map<String, Double>? {
    val source = map(key) ?: return null
    return source.mapNotNull { (k, v) ->
        val value = (v as? Number)?.toDouble() ?: return@mapNotNull null
        k.uppercase() to value
    }.toMap()
}

internal fun Map<*, *>.stringKeyMap(): Map<String, Any> = entries.mapNotNull { (key, value) ->
    val stringKey = key as? String ?: return@mapNotNull null
    @Suppress("UNCHECKED_CAST")
    stringKey to value as Any
}.toMap()
