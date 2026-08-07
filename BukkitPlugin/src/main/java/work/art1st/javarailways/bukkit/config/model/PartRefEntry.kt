package work.art1st.javarailways.bukkit.config.model

import kotlinx.serialization.Serializable

/**
 * A reference to a part in the "Standard part list" format.
 *
 * Supports two formats:
 * - Import: "import: namespace/part-name" references a registered part file
 * - Inline: "name: part-name, type: ..., ..." defines the part inline
 */
@Serializable
data class PartRefEntry(
    val name: String,                             // Part reference name (import target or inline name)
    val offset: Vec3Config = Vec3Config(),
    val rotation: Vec3Config = Vec3Config(),
    val inline: PartDefinition? = null
) {
    companion object {
        fun import(name: String, offset: Vec3Config = Vec3Config(), rotation: Vec3Config = Vec3Config()) =
            PartRefEntry(name = name, offset = offset, rotation = rotation)
    }
}
