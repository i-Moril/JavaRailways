package work.art1st.javarailways.basic.safety

import java.util.concurrent.ConcurrentHashMap

data class TrainSpeedProfile(
    val curveSpeedLimitOffsetMetersPerSecond: Double = 0.0
) {
    init {
        require(curveSpeedLimitOffsetMetersPerSecond.isFinite())
    }
}

class TrainSpeedProfileStore {
    private val profiles = ConcurrentHashMap<String, TrainSpeedProfile>()

    operator fun get(trainId: String): TrainSpeedProfile? = profiles[trainId]

    fun getOrDefault(trainId: String): TrainSpeedProfile = profiles[trainId] ?: TrainSpeedProfile()

    fun put(trainId: String, profile: TrainSpeedProfile) {
        require(trainId.isNotBlank())
        profiles[trainId] = profile
    }

    fun remove(trainId: String): TrainSpeedProfile? = profiles.remove(trainId)
}
