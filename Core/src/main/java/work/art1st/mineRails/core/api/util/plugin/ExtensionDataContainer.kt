package work.art1st.mineRails.core.api.util.plugin

class ExtensionDataContainer<Base> : Extendable<Base> {
    val extensionData: MutableList<Base?> = ArrayList()

    @ExtensionData
    @Suppress("UNCHECKED_CAST")
    override fun <T : Base> getExtensionData(plugin: Plugin) : T {
        return extensionData[plugin.identifier] as T
    }

    internal fun <T: Base> setExtensionData(plugin: Plugin, data: T) {
        extensionData[plugin.identifier] = data
    }

    override fun forEachExtensionData(action: (Base) -> Unit) = extensionData.forEach { it?.let(action) }
    override fun <T> forEachExtensionData(action: (Base) -> T) : List<T> = extensionData.mapNotNull { it?.let(action) }
}