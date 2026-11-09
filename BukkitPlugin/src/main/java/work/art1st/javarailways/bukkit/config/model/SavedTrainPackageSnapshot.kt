package work.art1st.javarailways.bukkit.config.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.TreeMap

/** Immutable operational resources used by a train created under the snapshot policy. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SavedTrainPackageSnapshot(
    val schemaVersion: Int = SCHEMA_VERSION,
    val hash: String,
    val packageName: String,
    val actions: Map<String, TrainActionConfig>,
    val behavior: TrainBehaviorScript? = null,
    val control: TrainControlConfig,
    val carControls: Map<String, CarControlConfig>,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val displays: TrainDisplayResources = TrainDisplayResources()
) {
    companion object {
        const val SCHEMA_VERSION = 1
        private val json = Json { encodeDefaults = true; explicitNulls = false }

        fun build(
            packageName: String,
            actions: Map<String, TrainActionConfig>,
            behavior: TrainBehaviorScript?,
            control: TrainControlConfig,
            carControls: Map<String, CarControlConfig>,
            displays: TrainDisplayResources = TrainDisplayResources()
        ): SavedTrainPackageSnapshot {
            val canonicalActions = TreeMap(actions).toMap()
            val canonicalCarControls = TreeMap(carControls).toMap()
            val unhashed = SavedTrainPackageSnapshot(
                hash = "",
                packageName = packageName,
                actions = canonicalActions,
                behavior = behavior,
                control = control,
                carControls = canonicalCarControls,
                displays = TrainDisplayResources(displays.templates, displays.defaults)
            )
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(json.encodeToString(unhashed).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return unhashed.copy(hash = digest)
        }
    }
}
