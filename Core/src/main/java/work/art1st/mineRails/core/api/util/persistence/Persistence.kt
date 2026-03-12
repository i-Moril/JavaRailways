package work.art1st.mineRails.core.api.util.persistence

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModuleBuilder
import kotlin.reflect.KClass

interface PersistentBy<out Serializable> {
    fun save() : Serializable
}

interface Loads<out Data, Dependency> {
    fun load(dependency: Dependency) : Data
}

class DelegateSerializer<T : PersistentBy<S>, S : Loads<T, D>, D>(
    private val surrogateSerializer: KSerializer<S>,
    private val dependency: D
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = surrogateSerializer.descriptor

    override fun serialize(encoder: Encoder, value: T) {
        return encoder.encodeSerializableValue(surrogateSerializer, value.save())
    }

    override fun deserialize(decoder: Decoder): T {
        val surrogate = decoder.decodeSerializableValue(surrogateSerializer)
        return surrogate.load(dependency)
    }
}

fun <T : PersistentBy<S>, S : Loads<T, D>, D> SerializersModuleBuilder.registerDependencyEntity(
    kClass: KClass<T>,
    surrogateSerializer: KSerializer<S>,
    dependency: D
) {
    contextual(kClass, DelegateSerializer(surrogateSerializer, dependency))
}