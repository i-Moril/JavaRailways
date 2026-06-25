package work.art1st.mineRails.bukkit.config.model

import org.joml.Vector3d

/**
 * A part definition loaded from a part YAML file.
 *
 * Parts define the building blocks of car models.
 * A part can be of type ITEM_DISPLAY (renders as ItemDisplay entity),
 * INTERACT (Interaction entity for seats), or DUMMY (pure transform group).
 *
 * Parts can reference sub-parts via [parts], forming a hierarchical model tree.
 */
data class PartDefinition(
    val name: String,                             // Fully qualified name e.g. "s100/body"
    val type: PartType,
    val item: PartItemConfig? = null,             // Present for ITEM_DISPLAY type
    val offset: Vec3Config = Vec3Config(),        // Local offset
    val rotation: Vec3Config = Vec3Config(),      // Local rotation (degrees)
    val parts: List<PartRefEntry> = emptyList(),  // Sub-part references
    val scale: PartScaleConfig? = null            // Legacy scale for non-item-display
) {
    /**
     * Resolve this part's sub-part references into a tree of PartNode.
     */
    fun resolveTree(registry: PartRegistry): PartNode {
        val children = parts.map { entry ->
            val resolved = registry.resolve(entry.name, this.namespace)
            val childDef = resolved.resolved
            // Merge the reference's offset/rotation on top of the child's own offset/rotation
            val mergedDef = PartDefinition(
                name = childDef.name,
                type = childDef.type,
                item = childDef.item,
                offset = Vec3Config(
                    x = childDef.offset.x + entry.offset.x,
                    y = childDef.offset.y + entry.offset.y,
                    z = childDef.offset.z + entry.offset.z
                ),
                rotation = Vec3Config(
                    x = childDef.rotation.x + entry.rotation.x,
                    y = childDef.rotation.y + entry.rotation.y,
                    z = childDef.rotation.z + entry.rotation.z
                ),
                parts = childDef.parts,
                scale = childDef.scale
            )
            mergedDef.resolveTree(registry)
        }
        return PartNode(this, children)
    }

    /** Extract namespace from the fully qualified name. */
    val namespace: String
        get() {
            val idx = name.lastIndexOf('/')
            return if (idx >= 0) name.substring(0, idx) else name
        }

    /** Extract the simple name (last segment). */
    val simpleName: String
        get() {
            val idx = name.lastIndexOf('/')
            return if (idx >= 0) name.substring(idx + 1) else name
        }
}

/**
 * Resolved part tree node for building the model.
 */
data class PartNode(
    val definition: PartDefinition,
    val children: List<PartNode> = emptyList()
)
