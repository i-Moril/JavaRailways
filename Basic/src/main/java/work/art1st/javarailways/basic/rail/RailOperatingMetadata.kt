package work.art1st.javarailways.basic.rail

import kotlinx.serialization.Serializable

@Serializable
data class TurnoutEndpointMetadata(
    val protectionDistanceMeters: Double,
    val lockingDistanceMeters: Double
) {
    init {
        require(protectionDistanceMeters.isFinite() && protectionDistanceMeters >= 0.0)
        require(lockingDistanceMeters.isFinite() && lockingDistanceMeters >= 0.0)
        require(lockingDistanceMeters <= protectionDistanceMeters)
    }
}

@Serializable
data class RailOperatingMetadata(
    val turnoutAtA: TurnoutEndpointMetadata? = null,
    val turnoutAtB: TurnoutEndpointMetadata? = null
)
