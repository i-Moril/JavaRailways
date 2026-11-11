package work.art1st.javarailways.basic.rail

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SpeedLimitType {
    CURVE,
    SUBGRADE
}

@Serializable
data class SpeedLimitEntry(
    val valueMetersPerSecond: Double,
    val type: SpeedLimitType
) {
    init {
        require(valueMetersPerSecond.isFinite() && valueMetersPerSecond > 0.0)
    }
}

/**
 * Immutable limit set.
 *
 * Both construction and deserialization copy entries into an unmodifiable list.
 * Consumers cannot mutate this value through the exposed list.
 */
@Serializable(with = SpeedLimitMetadataSerializer::class)
class SpeedLimitMetadata private constructor(
    private val storedEntries: List<SpeedLimitEntry>
) {
    val entries: List<SpeedLimitEntry> get() = storedEntries

    init {
        require(storedEntries.distinctBy(SpeedLimitEntry::type).size == storedEntries.size) {
            "SpeedLimitMetadata contains duplicate limit types"
        }
    }

    fun value(type: SpeedLimitType): Double? = storedEntries.firstOrNull { it.type == type }?.valueMetersPerSecond

    fun effectiveLimitMetersPerSecond(): Double? =
        storedEntries.minOfOrNull(SpeedLimitEntry::valueMetersPerSecond)

    override fun equals(other: Any?): Boolean =
        this === other || (other is SpeedLimitMetadata && storedEntries == other.storedEntries)

    override fun hashCode(): Int = storedEntries.hashCode()

    override fun toString(): String = "SpeedLimitMetadata(entries=$storedEntries)"

    companion object {
        @JvmStatic
        @JvmOverloads
        operator fun invoke(entries: List<SpeedLimitEntry> = emptyList()): SpeedLimitMetadata =
            SpeedLimitMetadata(java.util.List.copyOf(entries))
    }
}

/** Wire shape stays unchanged; decoded lists pass through the same immutable value boundary. */
internal object SpeedLimitMetadataSerializer : KSerializer<SpeedLimitMetadata> {
    @Serializable
    @SerialName("work.art1st.javarailways.basic.rail.SpeedLimitMetadata")
    private data class Payload(val entries: List<SpeedLimitEntry> = emptyList())

    override val descriptor: SerialDescriptor = Payload.serializer().descriptor

    override fun serialize(encoder: Encoder, value: SpeedLimitMetadata) {
        encoder.encodeSerializableValue(Payload.serializer(), Payload(value.entries))
    }

    override fun deserialize(decoder: Decoder): SpeedLimitMetadata =
        SpeedLimitMetadata(decoder.decodeSerializableValue(Payload.serializer()).entries)
}

@Serializable
data class CurveSafetyMetadata(
    val baseCurveDerailSpeedMetersPerSecond: Double? = null
) {
    init {
        require(
            baseCurveDerailSpeedMetersPerSecond == null ||
                baseCurveDerailSpeedMetersPerSecond.isFinite() && baseCurveDerailSpeedMetersPerSecond > 0.0
        )
    }
}
