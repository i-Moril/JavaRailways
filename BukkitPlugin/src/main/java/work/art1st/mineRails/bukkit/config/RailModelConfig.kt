package work.art1st.mineRails.bukkit.config

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
        val item = ItemStack(Material.valueOf(material.uppercase()))
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
    val loaded = input.use { yaml.load<Any?>(it) }
        ?: throw IllegalArgumentException("rail_model.yml is empty")
    val map = loaded as? Map<*, *>
        ?: throw IllegalArgumentException("rail_model.yml root must be a YAML mapping")
    return parseRailModelConfig(map.toStringKeyMap("rail_model.yml"))
}

private fun parseRailModelConfig(map: Map<String, Any>): RailModelConfig {
    val repeatLength = map.requireDouble("repeat_length", "rail_model.yml")
    require(repeatLength > 0.0) { "rail_model.yml.repeat_length must be > 0" }
    val elementsList = map.requireList("elements", "rail_model.yml")
        .mapIndexed { index, entry ->
            parseRailElement(
                (entry as? Map<*, *>)?.toStringKeyMap("rail_model.yml.elements[$index]")
                    ?: throw IllegalArgumentException("rail_model.yml.elements[$index] must be a map")
            )
        }
    require(elementsList.isNotEmpty()) { "rail_model.yml.elements must not be empty" }

    return RailModelConfig(
        repeatLength = repeatLength,
        elements = elementsList
    )
}

private fun parseRailElement(map: Map<String, Any>): RailElementConfig {
    val type = parseRailDisplayType(map.requireString("type", "rail element"))
    val material = map.requireString("material", "rail element")
    val offsetMap = map.requireMap("offset", "rail element")
    val rotationMap = map.requireMap("rotation", "rail element")
    val scaleMap = map.requireMap("scale", "rail element")
    val itemMap = map.optionalMap("item", "rail element")

    val item = if (type == RailDisplayType.ITEM) {
        parseRailItem(itemMap, material)
    } else null
    val blockMaterial = if (type == RailDisplayType.BLOCK) {
        validateBlockMaterial(material, "rail element.material")
        material
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

private fun parseRailDisplayType(raw: String): RailDisplayType = try {
    RailDisplayType.valueOf(raw.trim().uppercase())
} catch (e: IllegalArgumentException) {
    throw IllegalArgumentException("Unsupported rail element type '$raw'. Expected ITEM or BLOCK", e)
}

private fun parseRailItem(map: Map<String, Any>?, fallbackMaterial: String): RailItemConfig {
    val material = map?.requireString("material", "rail element.item") ?: fallbackMaterial
    validateMaterial(material, "rail element item material")
    val customModelData = map?.let {
        if (!it.containsKey("custom_model_data")) {
            null
        } else {
            (it["custom_model_data"] as? Number)?.toInt()
                ?: throw IllegalArgumentException("rail element.item.custom_model_data must be a number")
        }
    }
    return RailItemConfig(material = material, customModelData = customModelData)
}

private fun parseRotation(map: Map<String, Any>): RotationConfig {
    return RotationConfig(
        x = map.doubleOrDefault("x", "rotation", 0.0),
        y = map.doubleOrDefault("y", "rotation", 0.0),
        z = map.doubleOrDefault("z", "rotation", 0.0)
    )
}

private fun validateMaterial(material: String, subject: String) {
    try {
        Material.valueOf(material.uppercase())
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Unsupported $subject '$material'", e)
    }
}

private fun validateBlockMaterial(material: String, subject: String) {
    val parsed = try {
        Material.valueOf(material.uppercase())
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Unsupported $subject '$material'", e)
    }
    require(parsed.isBlock) { "$subject '$material' must be a block material" }
}

private fun Map<*, *>.toStringKeyMap(subject: String): Map<String, Any> = buildMap {
    for ((key, value) in this@toStringKeyMap) {
        val stringKey = key as? String
            ?: throw IllegalArgumentException("$subject contains non-string key: $key")
        val typedValue = value
            ?: throw IllegalArgumentException("$subject contains null value for key '$stringKey'")
        put(stringKey, typedValue)
    }
}

private fun Map<String, Any>.requireString(key: String, subject: String): String {
    val value = this[key] ?: throw IllegalArgumentException("$subject is missing required key '$key'")
    return value as? String ?: throw IllegalArgumentException("$subject.$key must be a string")
}

private fun Map<String, Any>.requireDouble(key: String, subject: String): Double {
    val value = this[key] ?: throw IllegalArgumentException("$subject is missing required key '$key'")
    return (value as? Number)?.toDouble()
        ?: throw IllegalArgumentException("$subject.$key must be a number")
}

private fun Map<String, Any>.doubleOrDefault(key: String, subject: String, defaultValue: Double): Double {
    if (!containsKey(key)) return defaultValue
    val value = this[key] ?: throw IllegalArgumentException("$subject.$key cannot be null")
    return (value as? Number)?.toDouble()
        ?: throw IllegalArgumentException("$subject.$key must be a number")
}

private fun Map<String, Any>.requireMap(key: String, subject: String): Map<String, Any> {
    val value = this[key] ?: throw IllegalArgumentException("$subject is missing required key '$key'")
    return (value as? Map<*, *>)?.toStringKeyMap("$subject.$key")
        ?: throw IllegalArgumentException("$subject.$key must be a map")
}

private fun Map<String, Any>.optionalMap(key: String, subject: String): Map<String, Any>? {
    if (!containsKey(key)) return null
    return requireMap(key, subject)
}

private fun Map<String, Any>.requireList(key: String, subject: String): List<*> {
    val value = this[key] ?: throw IllegalArgumentException("$subject is missing required key '$key'")
    return value as? List<*> ?: throw IllegalArgumentException("$subject.$key must be a list")
}
