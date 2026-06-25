package work.art1st.mineRails.bukkit.config.model

/**
 * Seat configuration within a car definition.
 */
data class SeatConfig(
    val heightOffset: Double = 0.8,       // Y offset from car floor
    val baseOffsetRatio: Double = -0.25,  // X base offset as ratio of car length
    val spacing: Double = 0.6,            // Distance between consecutive seats
    val interaction: SeatInteractionConfig = SeatInteractionConfig()
)

data class SeatInteractionConfig(
    val width: Double = 0.5,
    val height: Double = 1.0
)
