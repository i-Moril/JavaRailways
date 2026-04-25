package work.art1st.mineRails.bukkit.config

import org.bukkit.Material
import org.joml.Vector3d
import org.yaml.snakeyaml.Yaml
import java.io.InputStream

/**
 * 车厢模型配置。
 * 从 YAML 文件加载。
 */
data class CarModelConfig(
    val car: CarPartsConfig,
    val bogie: BogiePartsConfig,
    val seat: SeatConfig
)

data class CarPartsConfig(
    val floor: PartConfig,
    val walls: List<PartConfig> = emptyList(),
    val roof: PartConfig? = null
)

data class BogiePartsConfig(
    val frame: PartConfig,
    val rail: PartConfig? = null
)

data class PartConfig(
    val material: String,
    val offset: OffsetConfig,
    val scale: ScaleConfig
)

data class OffsetConfig(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0
) {
    fun toVector3d(): Vector3d = Vector3d(x, y, z)
}

data class ScaleConfig(
    val x: Double,
    val y: Double,
    val z: Double
)

data class SeatConfig(
    val offset: OffsetConfig,
    val interaction: InteractionConfig
)

data class InteractionConfig(
    val width: Float = 0.5f,
    val height: Float = 1.0f
)

/**
 * 从 InputStream 加载车厢模型配置。
 */
fun loadCarModelConfig(input: InputStream): CarModelConfig {
    val yaml = Yaml()
    val map = yaml.load<Map<String, Any>>(input)
    return parseCarModelConfig(map)
}

private fun parseCarModelConfig(map: Map<String, Any>): CarModelConfig {
    val carMap = map["car"] as? Map<String, Any> ?: error("Missing 'car' section")
    val bogieMap = map["bogie"] as? Map<String, Any> ?: error("Missing 'bogie' section")
    val seatMap = map["seat"] as? Map<String, Any> ?: error("Missing 'seat' section")

    return CarModelConfig(
        car = parseCarParts(carMap),
        bogie = parseBogieParts(bogieMap),
        seat = parseSeatConfig(seatMap)
    )
}

private fun parseCarParts(map: Map<String, Any>): CarPartsConfig {
    val floorMap = map["floor"] as? Map<String, Any> ?: error("Missing car.floor")
    val wallsList = (map["walls"] as? List<*>)?.mapNotNull { parsePart(it as? Map<String, Any>) } ?: emptyList()
    val roofMap = map["roof"] as? Map<String, Any>

    return CarPartsConfig(
        floor = parsePart(floorMap)!!,
        walls = wallsList,
        roof = roofMap?.let { parsePart(it) }
    )
}

private fun parseBogieParts(map: Map<String, Any>): BogiePartsConfig {
    val frameMap = map["frame"] as? Map<String, Any> ?: error("Missing bogie.frame")
    val railMap = map["rail"] as? Map<String, Any>

    return BogiePartsConfig(
        frame = parsePart(frameMap)!!,
        rail = railMap?.let { parsePart(it) }
    )
}

private fun parseSeatConfig(map: Map<String, Any>): SeatConfig {
    val offsetMap = map["offset"] as? Map<String, Any> ?: error("Missing seat.offset")
    val interactionMap = map["interaction"] as? Map<String, Any> ?: error("Missing seat.interaction")

    return SeatConfig(
        offset = parseOffset(offsetMap),
        interaction = InteractionConfig(
            width = (interactionMap["width"] as? Number)?.toFloat() ?: 0.5f,
            height = (interactionMap["height"] as? Number)?.toFloat() ?: 1.0f
        )
    )
}

private fun parsePart(map: Map<String, Any>?): PartConfig? {
    if (map == null) return null
    val material = map["material"] as? String ?: return null
    val offsetMap = map["offset"] as? Map<String, Any> ?: emptyMap()
    val scaleMap = map["scale"] as? Map<String, Any> ?: error("Missing scale")

    return PartConfig(
        material = material,
        offset = parseOffset(offsetMap),
        scale = parseScale(scaleMap)
    )
}

internal fun parseOffset(map: Map<String, Any>): OffsetConfig {
    return OffsetConfig(
        x = (map["x"] as? Number)?.toDouble() ?: 0.0,
        y = (map["y"] as? Number)?.toDouble() ?: 0.0,
        z = (map["z"] as? Number)?.toDouble() ?: 0.0
    )
}

internal fun parseScale(map: Map<String, Any>): ScaleConfig {
    return ScaleConfig(
        x = (map["x"] as? Number)?.toDouble() ?: error("Missing scale.x"),
        y = (map["y"] as? Number)?.toDouble() ?: error("Missing scale.y"),
        z = (map["z"] as? Number)?.toDouble() ?: error("Missing scale.z")
    )
}
