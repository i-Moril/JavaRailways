package work.art1st.mineRails.bukkit.extension.train

import kotlinx.serialization.Serializable
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData

@Serializable
data class SavedCarRenderState(
    val carModelName: String,
    val heldScenes: Map<String, String> = emptyMap(),
    val modelSnapshotHash: String? = null
) : SerializableExtensionData()

@Serializable
data class SavedTrainRenderState(
    val version: Int = 1
) : SerializableExtensionData()
