package work.art1st.javarailways.simplesignals.presentation

/** Minimal immutable authoring view over the separately loaded presentation-only model catalog. */
internal class ModelTypeCatalogSnapshot private constructor(
    private val catalogIdentity: Map<String, LoadedPackageModel>,
    modelIds: Collection<String>
) {
    val modelIds: List<String> = java.util.List.copyOf(modelIds.sorted())

    fun contains(modelId: String): Boolean = modelId in catalogIdentity

    fun represents(catalog: Map<String, LoadedPackageModel>): Boolean = catalogIdentity === catalog

    companion object {
        fun from(catalog: Map<String, LoadedPackageModel>) = ModelTypeCatalogSnapshot(catalog, catalog.keys)
    }
}
