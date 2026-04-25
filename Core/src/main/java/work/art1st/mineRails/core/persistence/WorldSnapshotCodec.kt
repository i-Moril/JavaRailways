package work.art1st.mineRails.core.persistence

import kotlinx.serialization.json.Json
import work.art1st.mineRails.core.physics.CoreWorld

/**
 * 世界快照 JSON 编解码（不依赖 Koin；扩展数据映射当前按 kotlinx 默认策略序列化）。
 */
object WorldSnapshotCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeToString(snapshot: CoreWorld.SavedWorldV1): String =
        json.encodeToString(CoreWorld.SavedWorldV1.serializer(), snapshot)

    fun decodeFromString(data: String): CoreWorld.SavedWorldV1 =
        json.decodeFromString(CoreWorld.SavedWorldV1.serializer(), data)
}
