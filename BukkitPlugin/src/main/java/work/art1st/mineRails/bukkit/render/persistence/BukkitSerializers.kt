package work.art1st.mineRails.bukkit.render.persistence

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.joml.Vector3d

object Vector3dSerializer : KSerializer<Vector3d> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Vector3d") {
        element<Double>("x")
        element<Double>("y")
        element<Double>("z")
    }

    override fun serialize(encoder: Encoder, value: Vector3d) {
        encoder.encodeStructure(descriptor) {
            encodeDoubleElement(descriptor, 0, value.x)
            encodeDoubleElement(descriptor, 1, value.y)
            encodeDoubleElement(descriptor, 2, value.z)
        }
    }

    override fun deserialize(decoder: Decoder): Vector3d {
        var x = 0.0
        var y = 0.0
        var z = 0.0
        decoder.decodeStructure(descriptor) {
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> x = decodeDoubleElement(descriptor, 0)
                    1 -> y = decodeDoubleElement(descriptor, 1)
                    2 -> z = decodeDoubleElement(descriptor, 2)
                    else -> error("Unexpected index: $index")
                }
            }
        }
        return Vector3d(x, y, z)
    }
}

object BukkitColorSerializer : KSerializer<Color> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("BukkitColor") {
        element<Int>("red")
        element<Int>("green")
        element<Int>("blue")
    }

    override fun serialize(encoder: Encoder, value: Color) {
        encoder.encodeStructure(descriptor) {
            encodeIntElement(descriptor, 0, value.red)
            encodeIntElement(descriptor, 1, value.green)
            encodeIntElement(descriptor, 2, value.blue)
        }
    }

    override fun deserialize(decoder: Decoder): Color {
        var red = 0
        var green = 0
        var blue = 0
        decoder.decodeStructure(descriptor) {
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> red = decodeIntElement(descriptor, 0)
                    1 -> green = decodeIntElement(descriptor, 1)
                    2 -> blue = decodeIntElement(descriptor, 2)
                    else -> error("Unexpected index: $index")
                }
            }
        }
        return Color.fromRGB(red, green, blue)
    }
}

object BukkitItemStackSerializer : KSerializer<ItemStack> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("BukkitItemStack") {
        element<String>("material")
        element<Int?>("customModelData", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: ItemStack) {
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, value.type.name)
            val itemMeta = value.itemMeta
            if (itemMeta != null && itemMeta.hasCustomModelData()) {
                encodeIntElement(descriptor, 1, itemMeta.customModelData)
            }
        }
    }

    override fun deserialize(decoder: Decoder): ItemStack {
        var material: Material? = null
        var customModelData: Int? = null
        decoder.decodeStructure(descriptor) {
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> {
                        val matName = decodeStringElement(descriptor, 0)
                        material = try {
                            Material.valueOf(matName)
                        } catch (failure: IllegalArgumentException) {
                            throw SerializationException("Unknown Bukkit material '$matName'", failure)
                        }
                    }
                    1 -> customModelData = decodeIntElement(descriptor, 1)
                    else -> error("Unexpected index: $index")
                }
            }
        }
        val itemStack = ItemStack(
            material ?: throw SerializationException("Missing required BukkitItemStack.material")
        )
        if (customModelData != null) {
            val meta = itemStack.itemMeta
            meta.setCustomModelData(customModelData)
            itemStack.itemMeta = meta
        }
        return itemStack
    }
}
