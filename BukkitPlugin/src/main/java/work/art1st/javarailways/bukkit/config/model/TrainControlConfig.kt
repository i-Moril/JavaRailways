package work.art1st.javarailways.bukkit.config.model

import kotlinx.serialization.Serializable

@Serializable
data class TrainControlConfig(
    val maxSpeed: Double = 20.0,
    val powerNotches: List<String> = listOf("P1", "P2", "P3", "P4", "P5"),
    val brakeNotches: List<String> = listOf("B1", "B2", "B3", "B4", "B5", "B6", "B7", "EB"),
    val tractionNotchScales: Map<String, Double> = linkedMapOf(
        "P1" to 0.20,
        "P2" to 0.40,
        "P3" to 0.60,
        "P4" to 0.80,
        "P5" to 1.00
    ),
    val brakeNotchScales: Map<String, Double> = linkedMapOf(
        "B1" to 0.15,
        "B2" to 0.30,
        "B3" to 0.45,
        "B4" to 0.60,
        "B5" to 0.75,
        "B6" to 0.90,
        "B7" to 1.00,
        "EB" to 1.25
    ),
    val brakeApplyTime: Double = 1.2,
    val brakeReleaseTime: Double = 1.8,
    val resistance: DavisResistanceConfig = DavisResistanceConfig(),
    val adhesion: AdhesionConfig = AdhesionConfig(),
    val jerk: JerkLimitConfig = JerkLimitConfig()
) {
    fun normalizedPowerScale(handle: String): Double = tractionNotchScales[handle.uppercase()] ?: 0.0

    fun normalizedBrakeScale(handle: String): Double = brakeNotchScales[handle.uppercase()] ?: 0.0

    companion object {
        fun parse(root: Map<String, Any>?): TrainControlConfig {
            val source = root ?: throw IllegalArgumentException("train control config is required")
            source.requireOnlyKeys(
                setOf("config-version", "max-speed", "controller", "traction", "brake", "resistance", "adhesion", "jerk"),
                "train control"
            )
            val version = source.requireInt("config-version", "train control")
            require(version == PartRegistry.CONFIG_VERSION) {
                "Unsupported train control config-version: $version"
            }
            val controller = source.requireMap("controller", "train control")
            val traction = source.requireMap("traction", "train control")
            val brake = source.requireMap("brake", "train control")
            controller.requireOnlyKeys(setOf("power-notches", "brake-notches"), "train control.controller")
            traction.requireOnlyKeys(setOf("notch-scales"), "train control.traction")
            brake.requireOnlyKeys(setOf("notch-scales", "apply-time", "release-time"), "train control.brake")
            val powerNotches = controller.requireStringList("power-notches", "train control.controller")
            val brakeNotches = controller.requireStringList("brake-notches", "train control.controller")
            val tractionScales = traction.requireDoubleMap("notch-scales", "train control.traction")
            val brakeScales = brake.requireDoubleMap("notch-scales", "train control.brake")
            validateNotchScales("power-notches", powerNotches, tractionScales, "train control.traction.notch-scales")
            validateNotchScales("brake-notches", brakeNotches, brakeScales, "train control.brake.notch-scales")
            return TrainControlConfig(
                maxSpeed = source.requireDouble("max-speed", "train control"),
                powerNotches = powerNotches,
                brakeNotches = brakeNotches,
                tractionNotchScales = tractionScales,
                brakeNotchScales = brakeScales,
                brakeApplyTime = brake.requireDouble("apply-time", "train control.brake"),
                brakeReleaseTime = brake.requireDouble("release-time", "train control.brake"),
                resistance = DavisResistanceConfig.parse(source.requireMap("resistance", "train control")),
                adhesion = AdhesionConfig.parse(source.requireMap("adhesion", "train control")),
                jerk = JerkLimitConfig.parse(source.requireMap("jerk", "train control"))
            )
        }

        private fun validateNotchScales(
            label: String,
            notches: List<String>,
            scales: Map<String, Double>,
            subject: String
        ) {
            require(notches.isNotEmpty()) { "$label must not be empty" }
            val normalizedNotches = notches.map { it.uppercase() }
            require(normalizedNotches.toSet().size == normalizedNotches.size) {
                "$label contains duplicates: ${normalizedNotches.joinToString()}"
            }
            val missing = normalizedNotches.filter { it !in scales }
            require(missing.isEmpty()) {
                "$subject is missing entries for: ${missing.joinToString()}"
            }
            val extras = scales.keys - normalizedNotches.toSet()
            require(extras.isEmpty()) {
                "$subject contains unexpected entries: ${extras.joinToString()}"
            }
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
            val source = root ?: throw IllegalArgumentException("car control config is required")
            source.requireOnlyKeys(
                setOf("mass", "rotating-mass-factor", "motor", "brake", "resistance"),
                "car control"
            )
            return CarControlConfig(
                mass = source.requireDouble("mass", "car control"),
                rotatingMassFactor = source.requireDouble("rotating-mass-factor", "car control"),
                motor = MotorControlConfig.parse(source.requireMap("motor", "car control")),
                brake = CarBrakeControlConfig.parse(source.requireMap("brake", "car control")),
                resistance = source.optionalMap("resistance", "car control")?.let { DavisResistanceConfig.parse(it) }
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
            val source = root ?: throw IllegalArgumentException("motor control config is required")
            source.requireOnlyKeys(
                setOf("enabled", "max-force", "max-power", "adhesion-mass-ratio", "force-curve"),
                "car control.motor"
            )
            return MotorControlConfig(
                enabled = source.requireBoolean("enabled", "car control.motor"),
                maxForce = source.requireDouble("max-force", "car control.motor"),
                maxPower = source.requireDouble("max-power", "car control.motor"),
                adhesionMassRatio = source.requireDouble("adhesion-mass-ratio", "car control.motor"),
                forceCurve = source.optionalList("force-curve", "car control.motor")?.mapIndexed { index, entry ->
                    val map = (entry as? Map<*, *>)?.stringKeyMap("force-curve[$index]")
                        ?: throw IllegalArgumentException("force-curve[$index] must be a map")
                    SpeedForcePoint.parse(map)
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
            val source = root ?: throw IllegalArgumentException("brake control config is required")
            source.requireOnlyKeys(
                setOf("max-force", "regenerative-force", "adhesion-mass-ratio"),
                "car control.brake"
            )
            return CarBrakeControlConfig(
                maxForce = source.requireDouble("max-force", "car control.brake"),
                regenerativeForce = source.requireDouble("regenerative-force", "car control.brake"),
                adhesionMassRatio = source.requireDouble("adhesion-mass-ratio", "car control.brake")
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
            val source = root ?: throw IllegalArgumentException("resistance config is required")
            source.requireOnlyKeys(setOf("a", "b", "c"), "resistance")
            return DavisResistanceConfig(
                a = source.requireDouble("a", "resistance"),
                b = source.requireDouble("b", "resistance"),
                c = source.requireDouble("c", "resistance")
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
            val source = root ?: throw IllegalArgumentException("adhesion config is required")
            source.requireOnlyKeys(setOf("coefficient"), "adhesion")
            return AdhesionConfig(
                coefficient = source.requireDouble("coefficient", "adhesion")
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
            val source = root ?: throw IllegalArgumentException("jerk config is required")
            source.requireOnlyKeys(setOf("traction", "brake"), "jerk")
            return JerkLimitConfig(
                traction = source.requireDouble("traction", "jerk"),
                brake = source.requireDouble("brake", "jerk")
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
        fun parse(root: Map<String, Any>): SpeedForcePoint {
            root.requireOnlyKeys(setOf("speed", "force"), "force-curve point")
            val speed = root.requireDouble("speed", "force-curve point")
            val force = root.requireDouble("force", "force-curve point")
            return SpeedForcePoint(speed, force)
        }
    }
}

internal fun Map<String, Any>?.double(key: String): Double? {
    if (this == null || !containsKey(key)) return null
    val value = this[key] ?: throw IllegalArgumentException("'$key' cannot be null")
    return (value as? Number)?.toDouble()
        ?: throw IllegalArgumentException("'$key' must be a number")
}

internal fun Map<String, Any>?.boolean(key: String): Boolean? {
    if (this == null || !containsKey(key)) return null
    val value = this[key] ?: throw IllegalArgumentException("'$key' cannot be null")
    return value as? Boolean
        ?: throw IllegalArgumentException("'$key' must be a boolean")
}

internal fun Map<String, Any>?.map(key: String): Map<String, Any>? {
    if (this == null || !containsKey(key)) return null
    val value = this[key] ?: throw IllegalArgumentException("'$key' cannot be null")
    return (value as? Map<*, *>)?.stringKeyMap(key)
        ?: throw IllegalArgumentException("'$key' must be a map")
}

internal fun Map<String, Any>?.list(key: String): List<*>? {
    if (this == null || !containsKey(key)) return null
    val value = this[key] ?: throw IllegalArgumentException("'$key' cannot be null")
    return value as? List<*>
        ?: throw IllegalArgumentException("'$key' must be a list")
}

internal fun Map<String, Any>?.stringList(key: String): List<String>? = list(key)?.mapIndexed { index, value ->
    value as? String ?: throw IllegalArgumentException("'$key[$index]' must be a string")
}

internal fun Map<String, Any>?.doubleMap(key: String): Map<String, Double>? {
    val source = map(key) ?: return null
    return source.entries.associate { (k, v) ->
        val value = (v as? Number)?.toDouble()
            ?: throw IllegalArgumentException("'$key.$k' must be a number")
        k.uppercase() to value
    }
}

internal fun Map<*, *>.stringKeyMap(subject: String = "map"): Map<String, Any> = buildMap {
    for ((key, value) in this@stringKeyMap) {
        val stringKey = key as? String
            ?: throw IllegalArgumentException("$subject contains non-string key: $key")
        val typedValue = value
            ?: throw IllegalArgumentException("$subject contains null value for key '$stringKey'")
        put(stringKey, typedValue)
    }
}

internal fun Map<String, Any>.requireDouble(key: String, subject: String): Double {
    val value = this[key] ?: throw IllegalArgumentException("$subject is missing required key '$key'")
    val number = value as? Number ?: throw IllegalArgumentException("$subject.$key must be a number")
    return number.toDouble().also {
        require(it.isFinite()) { "$subject.$key must be finite" }
    }
}

internal fun Map<String, Any>.requireBoolean(key: String, subject: String): Boolean {
    val value = this[key] ?: throw IllegalArgumentException("$subject is missing required key '$key'")
    return value as? Boolean
        ?: throw IllegalArgumentException("$subject.$key must be a boolean")
}

internal fun Map<String, Any>.requireMap(key: String, subject: String): Map<String, Any> {
    val value = this[key] ?: throw IllegalArgumentException("$subject is missing required key '$key'")
    return (value as? Map<*, *>)?.stringKeyMap("$subject.$key")
        ?: throw IllegalArgumentException("$subject.$key must be a map")
}

internal fun Map<String, Any>.requireStringList(key: String, subject: String): List<String> {
    val value = this[key] ?: throw IllegalArgumentException("$subject is missing required key '$key'")
    val list = value as? List<*>
        ?: throw IllegalArgumentException("$subject.$key must be a list")
    return list.mapIndexed { index, entry ->
        val value = entry as? String
            ?: throw IllegalArgumentException("$subject.$key[$index] must be a string")
        require(value.isNotBlank()) { "$subject.$key[$index] must not be blank" }
        value
    }
}

internal fun Map<String, Any>.requireDoubleMap(key: String, subject: String): Map<String, Double> {
    val value = this[key] ?: throw IllegalArgumentException("$subject is missing required key '$key'")
    val map = (value as? Map<*, *>)?.stringKeyMap("$subject.$key")
        ?: throw IllegalArgumentException("$subject.$key must be a map")
    return map.entries.associate { (entryKey, entryValue) ->
        val number = entryValue as? Number
            ?: throw IllegalArgumentException("$subject.$key.$entryKey must be a number")
        val doubleValue = number.toDouble()
        require(doubleValue.isFinite()) { "$subject.$key.$entryKey must be finite" }
        entryKey.uppercase() to doubleValue
    }
}

internal fun Map<String, Any>.requireList(key: String, subject: String): List<*> {
    val value = this[key] ?: throw IllegalArgumentException("$subject is missing required key '$key'")
    return value as? List<*>
        ?: throw IllegalArgumentException("$subject.$key must be a list")
}

