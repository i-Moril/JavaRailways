package work.art1st.mineRails.core.persistence

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData
import work.art1st.mineRails.core.physics.CoreWorld

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

    fun encodeToString(snapshot: CoreWorld.SavedWorldV1): String =
        json.encodeToString(CoreWorld.SavedWorldV1.serializer(), snapshot)

    fun decodeFromString(data: String): CoreWorld.SavedWorldV1 =
        json.decodeFromString(CoreWorld.SavedWorldV1.serializer(), data)
}
