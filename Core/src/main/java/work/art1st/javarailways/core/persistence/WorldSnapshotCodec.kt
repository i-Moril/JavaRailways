package work.art1st.javarailways.core.persistence

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import work.art1st.javarailways.core.api.util.plugin.SerializableExtensionData
import work.art1st.javarailways.core.physics.CoreWorld

/**
 * 世界快照 JSON 编解码（不依赖 Koin；扩展数据映射当前按 kotlinx 默认策略序列化）。
 */
object WorldSnapshotCodec {
    @OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
    private val extensionDataModule = SerializersModule {
        polymorphicDefaultSerializer(SerializableExtensionData::class) { value ->
            @Suppress("UNCHECKED_CAST")
            serializer(value::class.java) as SerializationStrategy<SerializableExtensionData>
        }
        polymorphicDefaultDeserializer(SerializableExtensionData::class) { className ->
            @Suppress("UNCHECKED_CAST")
            Class.forName(className).kotlin.serializer() as DeserializationStrategy<SerializableExtensionData>
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        serializersModule = extensionDataModule
    }

    fun encodeInfrastructure(snapshot: CoreWorld.SavedInfrastructureV1): String =
        json.encodeToString(CoreWorld.SavedInfrastructureV1.serializer(), snapshot)

    fun decodeInfrastructure(data: String): CoreWorld.SavedInfrastructureV1 =
        json.decodeFromString(CoreWorld.SavedInfrastructureV1.serializer(), data)

    fun encodeRuntime(snapshot: CoreWorld.SavedRuntimeV1): String =
        json.encodeToString(CoreWorld.SavedRuntimeV1.serializer(), snapshot)

    fun decodeRuntime(data: String): CoreWorld.SavedRuntimeV1 =
        json.decodeFromString(CoreWorld.SavedRuntimeV1.serializer(), data)
}
