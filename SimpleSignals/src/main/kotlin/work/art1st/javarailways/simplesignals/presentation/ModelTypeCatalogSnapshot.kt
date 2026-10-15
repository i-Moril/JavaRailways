package work.art1st.javarailways.simplesignals.presentation

/** Minimal immutable authoring view over the separately loaded presentation-only model catalog. */
internal class ModelTypeCatalogSnapshot private constructor(
    private val catalogIdentity: Map<String, LoadedPackageModel>,
    modelIds: Collection<String>
) {
    val modelIds: List<String> = java.util.List.copyOf(modelIds.sorted())

    fun contains(modelId: String): Boolean = modelId in catalogIdentity

    fun represents(catalog: Map<String, LoadedPackageModel>): Boolean = catalogIdentity === catalog

    fun variantNames(modelId: String): List<String>? =
        catalogIdentity[modelId]?.let { java.util.List.copyOf(it.variants.keys.sorted()) }

    fun railPlacementModel(modelId: String): RailPlacementModel? =
        catalogIdentity[modelId]?.let(RailPlacementModel::from)

    companion object {
        fun from(catalog: Map<String, LoadedPackageModel>) = ModelTypeCatalogSnapshot(catalog, catalog.keys)
    }
}

/** Value-only authoring projection; no model tree, renderer or live preset inheritance. */
internal class RailPlacementModel private constructor(
    val modelId: String,
    presets: Map<String, RailPlacementPreset>
) {
    val presets: Map<String, RailPlacementPreset> = java.util.Collections.unmodifiableMap(presets.toSortedMap())

    companion object {
        fun from(model: LoadedPackageModel) = RailPlacementModel(model.id,
            model.railPlacementPresets.mapValues { (_, preset) ->
                preset.copy(followRailTilt = preset.followRailTilt ?: model.followRailTilt)
            })
    }
}
