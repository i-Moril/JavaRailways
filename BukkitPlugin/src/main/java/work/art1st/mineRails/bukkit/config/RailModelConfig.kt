package work.art1st.mineRails.bukkit.config

import org.joml.Vector3d
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
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

enum class RailDisplayType {
    ITEM,
    BLOCK
}

data class RailItemConfig(
    val material: String,
    val customModelData: Int? = null
) {
    fun toItemStack(): ItemStack {
        val item = ItemStack(runCatching { Material.valueOf(material.uppercase()) }.getOrElse { Material.STONE })
        if (customModelData != null) {
            val meta = item.itemMeta
            meta.setCustomModelData(customModelData)
            item.itemMeta = meta
        }
        return item
    }
}

/**
 * 轨道显示元素配置。
 * 描述在轨道曲线某一点放置的一个 ItemDisplay 实体。
 */
data class RailElementConfig(
    /** 显示实体类型 */
    val type: RailDisplayType = RailDisplayType.ITEM,
    /** ItemDisplay 物品配置 */
    val item: RailItemConfig? = null,
    /** BlockDisplay 方块材质 */
    val blockMaterial: String? = null,
    /** 相对曲线点的局部偏移 */
    val offset: OffsetConfig,
    /** 局部欧拉角旋转（度） */
    val rotation: RotationConfig = RotationConfig(),
    /** 模型缩放 (x=沿曲线方向长度, y=高度, z=宽度) */
    val scale: ScaleConfig
)

data class RotationConfig(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0
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
    val type = (map["type"] as? String)?.uppercase()?.let {
        runCatching { RailDisplayType.valueOf(it) }.getOrElse { RailDisplayType.ITEM }
    } ?: RailDisplayType.ITEM
    val material = map["material"] as? String
    val itemMap = map["item"] as? Map<String, Any>
    val offsetMap = map["offset"] as? Map<String, Any> ?: emptyMap()
    val rotationMap = map["rotation"] as? Map<String, Any> ?: emptyMap()
    val scaleMap = map["scale"] as? Map<String, Any> ?: error("Missing scale in rail element")

    val item = if (type == RailDisplayType.ITEM) {
        parseRailItem(itemMap, material)
    } else null
    val blockMaterial = if (type == RailDisplayType.BLOCK) {
        material ?: "STONE"
    } else null

    return RailElementConfig(
        type = type,
        item = item,
        blockMaterial = blockMaterial,
        offset = parseOffset(offsetMap),
        rotation = parseRotation(rotationMap),
        scale = parseScale(scaleMap)
    )
}

private fun parseRailItem(map: Map<String, Any>?, fallbackMaterial: String?): RailItemConfig {
    val material = (map?.get("material") as? String) ?: fallbackMaterial ?: "RAIL"
    val customModelData = (map?.get("custom_model_data") as? Number)?.toInt()
    return RailItemConfig(material = material, customModelData = customModelData)
}

private fun parseRotation(map: Map<String, Any>): RotationConfig {
    return RotationConfig(
        x = (map["x"] as? Number)?.toDouble() ?: 0.0,
        y = (map["y"] as? Number)?.toDouble() ?: 0.0,
        z = (map["z"] as? Number)?.toDouble() ?: 0.0
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
                type = RailDisplayType.BLOCK,
                blockMaterial = "OAK_PLANKS",
                offset = OffsetConfig(x = 0.0, y = -0.18, z = 0.0),
                rotation = RotationConfig(),
                scale = ScaleConfig(x = 1.6, y = 0.08, z = 1.4)
            ),
            // 两条铁轨
            RailElementConfig(
                type = RailDisplayType.ITEM,
                item = RailItemConfig(material = "RAIL"),
                offset = OffsetConfig(x = 0.0, y = -0.1, z = 0.3),
                rotation = RotationConfig(x = -90.0),
                scale = ScaleConfig(x = 1.0, y = 0.05, z = 0.1)
            ),
            RailElementConfig(
                type = RailDisplayType.ITEM,
                item = RailItemConfig(material = "RAIL"),
                offset = OffsetConfig(x = 0.0, y = -0.1, z = -0.3),
                rotation = RotationConfig(x = -90.0),
                scale = ScaleConfig(x = 1.0, y = 0.05, z = 0.1)
            )
        )
    )
}
