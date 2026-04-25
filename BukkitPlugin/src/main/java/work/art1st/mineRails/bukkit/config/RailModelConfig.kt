package work.art1st.mineRails.bukkit.config

import org.joml.Vector3d
import org.yaml.snakeyaml.Yaml
import java.io.InputStream

/**
 * 轨道可视化配置。
 * 从 YAML 文件加载。
 *
 * 定义沿轨道曲线重复的显示元素模式。
 * 每个元素以 [repeatLength] 米为周期沿曲线放置。
 */
data class RailModelConfig(
    /** 重复周期长度（米）。每个元素沿曲线每隔此距离放置一次。 */
    val repeatLength: Double,
    /** 周期内的显示元素列表。每个元素会沿曲线每个 [repeatLength] 放置一次。 */
    val elements: List<RailElementConfig>
)

/**
 * 轨道显示元素配置。
 * 描述在轨道曲线某一点放置的一个 ItemDisplay 实体。
 */
data class RailElementConfig(
    /** 方块/物品材质名 */
    val material: String,
    /** 相对曲线点的局部偏移 */
    val offset: OffsetConfig,
    /** 模型缩放 (x=沿曲线方向长度, y=高度, z=宽度) */
    val scale: ScaleConfig
)

/**
 * 从 InputStream 加载轨道可视化配置。
 */
fun loadRailModelConfig(input: InputStream): RailModelConfig {
    val yaml = Yaml()
    val map = yaml.load<Map<String, Any>>(input)
    return parseRailModelConfig(map)
}

private fun parseRailModelConfig(map: Map<String, Any>): RailModelConfig {
    val repeatLength = (map["repeat_length"] as? Number)?.toDouble() ?: 2.0
    val elementsList = (map["elements"] as? List<*>)?.mapNotNull { parseRailElement(it as? Map<String, Any>) } ?: emptyList()

    return RailModelConfig(
        repeatLength = repeatLength,
        elements = elementsList
    )
}

private fun parseRailElement(map: Map<String, Any>?): RailElementConfig? {
    if (map == null) return null
    val material = map["material"] as? String ?: return null
    val offsetMap = map["offset"] as? Map<String, Any> ?: emptyMap()
    val scaleMap = map["scale"] as? Map<String, Any> ?: error("Missing scale in rail element")

    return RailElementConfig(
        material = material,
        offset = parseOffset(offsetMap),
        scale = parseScale(scaleMap)
    )
}

/** 获取默认轨道可视化配置 */
fun getDefaultRailModelConfig(): RailModelConfig {
    // 默认：每2米放置一组枕木+铁轨
    return RailModelConfig(
        repeatLength = 2.0,
        elements = listOf(
            // 枕木（垂直于轨道的木板）
            RailElementConfig(
                material = "OAK_PLANKS",
                OffsetConfig(x = 0.0, y = -0.18, z = 0.0),
                ScaleConfig(x = 1.6, y = 0.08, z = 1.4)
            ),
            // 两条铁轨
            RailElementConfig(
                material = "RAIL",
                OffsetConfig(x = 0.0, y = -0.1, z = 0.3),
                ScaleConfig(x = 1.0, y = 0.05, z = 0.1)
            ),
            RailElementConfig(
                material = "RAIL",
                OffsetConfig(x = 0.0, y = -0.1, z = -0.3),
                ScaleConfig(x = 1.0, y = 0.05, z = 0.1)
            )
        )
    )
}
