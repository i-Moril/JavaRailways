package work.art1st.mineRails.core.api.util.plugin

interface Extension<T> {
    fun initialize(host: T, savedExtensionData: Map<String, SerializableExtensionData>)
    fun save(): Pair<String, SerializableExtensionData>?
}