package work.art1st.mineRails.bukkit.config.model

/**
 * A reference to a part in the "Standard part list" format.
 * Used within part files (sub-parts), car body-parts, and bogie parts/wheel-parts.
 */
data class PartRefEntry(
    val name: String,                       // Qualified part name: "[namespace/]part-name"
    val offset: Vec3Config = Vec3Config(),  // Local offset override
    val rotation: Vec3Config = Vec3Config() // Local rotation override (degrees)
)
