package work.art1st.javarailways.bukkit.config.model

import kotlinx.serialization.Serializable
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionResources
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionResourcesMapSerializer
import work.art1st.javarailways.bukkit.api.model.extension.immutableExtensionResources
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.TreeMap

/** Immutable operational resources used by a train created under the snapshot policy. */
@Serializable(with = SavedTrainPackageSnapshot.Serializer::class)
class SavedTrainPackageSnapshot(
    val schemaVersion: Int = SCHEMA_VERSION,
    val hash: String,
    val packageName: String,
    val actions: Map<String, TrainActionConfig>,
    val behavior: TrainBehaviorScript? = null,
    val control: TrainControlConfig,
    val carControls: Map<String, CarControlConfig>,
    extensions: Map<String, PartExtensionResources> = emptyMap()
) {
    val extensions: Map<String, PartExtensionResources> = immutableExtensionResources(extensions)

    fun copy(
        schemaVersion: Int = this.schemaVersion,
        hash: String = this.hash,
        packageName: String = this.packageName,
        actions: Map<String, TrainActionConfig> = this.actions,
        behavior: TrainBehaviorScript? = this.behavior,
        control: TrainControlConfig = this.control,
        carControls: Map<String, CarControlConfig> = this.carControls,
        extensions: Map<String, PartExtensionResources> = this.extensions
    ) = SavedTrainPackageSnapshot(schemaVersion, hash, packageName, actions, behavior, control, carControls, extensions)

    override fun equals(other: Any?): Boolean = other is SavedTrainPackageSnapshot &&
        schemaVersion == other.schemaVersion && hash == other.hash && packageName == other.packageName &&
        actions == other.actions && behavior == other.behavior && control == other.control &&
        carControls == other.carControls && extensions == other.extensions
    override fun hashCode(): Int = listOf(schemaVersion, hash, packageName, actions, behavior, control, carControls, extensions).hashCode()
    override fun toString(): String = "SavedTrainPackageSnapshot(packageName=$packageName, hash=$hash)"

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private data class Payload(
        val schemaVersion: Int = SCHEMA_VERSION,
        val hash: String,
        val packageName: String,
        val actions: Map<String, TrainActionConfig>,
        val behavior: TrainBehaviorScript? = null,
        val control: TrainControlConfig,
        val carControls: Map<String, CarControlConfig>,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        @Serializable(with = PartExtensionResourcesMapSerializer::class)
        val extensions: Map<String, PartExtensionResources> = emptyMap()
    )
    object Serializer : kotlinx.serialization.KSerializer<SavedTrainPackageSnapshot> {
        override val descriptor = Payload.serializer().descriptor
        override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: SavedTrainPackageSnapshot) =
            encoder.encodeSerializableValue(Payload.serializer(), Payload(value.schemaVersion, value.hash, value.packageName,
                value.actions, value.behavior, value.control, value.carControls, value.extensions))
        override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): SavedTrainPackageSnapshot =
            decoder.decodeSerializableValue(Payload.serializer()).let {
                SavedTrainPackageSnapshot(it.schemaVersion, it.hash, it.packageName, it.actions, it.behavior, it.control, it.carControls, it.extensions)
            }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        private val json = Json { encodeDefaults = true; explicitNulls = false }

        fun build(
            packageName: String,
            actions: Map<String, TrainActionConfig>,
            behavior: TrainBehaviorScript?,
            control: TrainControlConfig,
            carControls: Map<String, CarControlConfig>,
            extensions: Map<String, PartExtensionResources> = emptyMap()
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
                extensions = immutableExtensionResources(extensions)
            )
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(json.encodeToString(unhashed).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return unhashed.copy(hash = digest)
        }
    }
}
