package work.art1st.javarailways.bukkit.extension.train

import kotlinx.serialization.Serializable
import work.art1st.javarailways.core.api.util.plugin.SerializableExtensionData

@Serializable
data class SavedCarRenderState(
    val carModelName: String,
    val heldScenes: Map<String, String> = emptyMap(),
    val modelSnapshotHash: String? = null,
    val packageSnapshotHash: String? = null,
    val resourceType: String? = null
) : SerializableExtensionData()

@Serializable
data class SavedTrainRenderState(
    val version: Int = 1,
    val packageName: String? = null,
    val packageSnapshotHash: String? = null
) : SerializableExtensionData()
