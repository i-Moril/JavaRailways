package work.art1st.mineRails.core.control.signal

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.joml.Vector3dc
import work.art1st.mineRails.core.api.render.Renderable
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.physics.rail.RailLocationWithoutFacing
import work.art1st.mineRails.core.api.physics.rail.RailObject

@Serializable(with = Signal.SignalSerializer::class)
abstract class Signal(
    override val location: RailLocationWithoutFacing
) : Renderable, RailObject {
    override val position: Vector3dc
        get() = location.position
    override val direction: Vector3dc
        get() = location.tangent
    override val normal: Vector3dc
        get() = location.normal
    override val world: World
        get() = location.rail.world

    // TODO
    object SignalSerializer : KSerializer<Signal> {
        private val delegateSerializer = MapSerializer(String.serializer(), Int.serializer())
        override val descriptor: SerialDescriptor = SerialDescriptor("Signal", delegateSerializer.descriptor)
        override fun deserialize(decoder: Decoder): Signal {
            TODO("Not yet implemented")
        }

        override fun serialize(
            encoder: Encoder,
            value: Signal
        ) {
            TODO("Not yet implemented")
        }
    }
}