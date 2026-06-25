package work.art1st.mineRails.bukkit.extension.train

import work.art1st.mineRails.core.api.physics.train.Bogie
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData

/**
 * Bogie rendering extension.
 *
 * In the new model system, bogie parts are rendered as children of the
 * car model tree (via CarExtension). This extension is kept as a minimal
 * placeholder to satisfy the extension registration system.
 */
class BogieExtension : TrainComponentExtension<Bogie>() {
    override fun save(): Pair<String, SerializableExtensionData>? = null

    override fun buildModel() {
        // Bogie rendering is handled by CarExtension's model tree.
        // No entities are spawned here.
    }

    override fun updateModel() {
        // Handled by CarExtension.
    }

    override fun destroyModel() {
        // Entities are owned by CarExtension's model tree.
    }
}
