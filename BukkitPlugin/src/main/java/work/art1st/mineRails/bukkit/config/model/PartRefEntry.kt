package work.art1st.mineRails.bukkit.config.model

/**
 * A reference to a part in the "Standard part list" format.
 *
 * Supports two formats:
 * - Import: "import: namespace/part-name" references an external part file
 * - Inline: "name: part-name, type: ..., ..." defines the part inline
 * - Legacy: "name: part-name" (resolved through PartRegistry)
 */
data class PartRefEntry(
    val name: String,                             // Part reference name (import target or inline name)
    val offset: Vec3Config = Vec3Config(),
    val rotation: Vec3Config = Vec3Config(),
    val inline: PartDefinition? = null,            // Non-null if inline definition
    val isImport: Boolean = false                  // True if this is an import (vs inline or legacy ref)
) {
    /** Shorthand for inline references where name is the resolved part name */
    companion object {
        fun ref(name: String, offset: Vec3Config = Vec3Config(), rotation: Vec3Config = Vec3Config()) =
            PartRefEntry(name = name, offset = offset, rotation = rotation)

        fun import(name: String, offset: Vec3Config = Vec3Config(), rotation: Vec3Config = Vec3Config()) =
            PartRefEntry(name = name, offset = offset, rotation = rotation, isImport = true)
    }
}
