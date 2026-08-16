package work.art1st.javarailways.basic.rail

import kotlinx.serialization.Serializable

@Serializable
enum class SpeedLimitType {
    CURVE,
    SUBGRADE
}

@Serializable
data class SpeedLimitEntry(
    val valueMetersPerSecond: Double,
    val type: SpeedLimitType
) {
    init {
        require(valueMetersPerSecond.isFinite() && valueMetersPerSecond > 0.0)
    }
}

@Serializable
data class SpeedLimitMetadata(
    val entries: List<SpeedLimitEntry> = emptyList()
) {
    init {
        require(entries.distinctBy(SpeedLimitEntry::type).size == entries.size) {
            "SpeedLimitMetadata contains duplicate limit types"
        }
    }

    fun value(type: SpeedLimitType): Double? = entries.firstOrNull { it.type == type }?.valueMetersPerSecond

    fun effectiveLimitMetersPerSecond(): Double? = entries.minOfOrNull(SpeedLimitEntry::valueMetersPerSecond)
}

@Serializable
data class CurveSafetyMetadata(
    val baseCurveDerailSpeedMetersPerSecond: Double? = null
) {
    init {
        require(
            baseCurveDerailSpeedMetersPerSecond == null ||
                baseCurveDerailSpeedMetersPerSecond.isFinite() && baseCurveDerailSpeedMetersPerSecond > 0.0
        )
    }
}
