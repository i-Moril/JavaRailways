package work.art1st.javarailways.basic.rail

import kotlinx.serialization.Serializable
import work.art1st.javarailways.core.api.physics.rail.ViewDirection

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
    val allowedDirection: ViewDirection? = null,
    val turnoutAtA: TurnoutEndpointMetadata? = null,
    val turnoutAtB: TurnoutEndpointMetadata? = null
)
