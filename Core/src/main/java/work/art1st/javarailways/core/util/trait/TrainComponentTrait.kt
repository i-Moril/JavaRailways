package work.art1st.javarailways.core.util.trait

import work.art1st.javarailways.core.api.util.plugin.Extendable
import work.art1st.javarailways.core.api.util.plugin.Extension
import work.art1st.javarailways.core.api.util.plugin.SerializableExtensionData

interface TrainComponentTrait<T, E : Extension<T>> : Extendable<E> {
    @Suppress("UNCHECKED_CAST")
    fun initialize(savedExtensionData: Map<String, SerializableExtensionData>) {
        forEachExtensionData {
            it.initialize(this as T, savedExtensionData)
        }
    }
}