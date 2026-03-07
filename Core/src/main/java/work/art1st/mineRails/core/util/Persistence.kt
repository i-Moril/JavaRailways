package work.art1st.mineRails.core.util

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModuleBuilder
import org.koin.core.scope.Scope

interface PersistentBy<Serializable> {
    fun save() : Serializable
}

interface Loads<Data> {
    fun load(dependencyProvider: Scope) : Data
}

class DelegateSerializer<T : PersistentBy<S>, S : Loads<T>>(
    private val surrogateSerializer: KSerializer<S>,
    private val dependencyProvider: Scope
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = surrogateSerializer.descriptor

    override fun serialize(encoder: Encoder, value: T) {
        return encoder.encodeSerializableValue(surrogateSerializer, value.save())
    }

    override fun deserialize(decoder: Decoder): T {
        val surrogate = decoder.decodeSerializableValue(surrogateSerializer)
        return surrogate.load(dependencyProvider)
    }
}

fun <T : PersistentBy<S>, S : Loads<T>> SerializersModuleBuilder.registerDependencyEntity(
    kClass: kotlin.reflect.KClass<T>,
    surrogateSerializer: KSerializer<S>,
    dependency: Scope
) {
    contextual(kClass, DelegateSerializer(surrogateSerializer, dependency))
}