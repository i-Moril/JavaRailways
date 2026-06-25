package work.art1st.mineRails.bukkit.config.model

/**
 * Animation configuration for parts.
 */
data class PartAnimationConfig(
    val name: String,
    val commands: List<String> = emptyList()
)
