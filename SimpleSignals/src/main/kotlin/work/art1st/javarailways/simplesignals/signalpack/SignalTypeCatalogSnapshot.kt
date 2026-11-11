package work.art1st.javarailways.simplesignals.signalpack

import work.art1st.javarailways.simplesignals.presentation.RailPlacementModel

/** Minimal immutable catalog projection suitable for commands and authoring tools. */
internal class SignalTypeCatalogSnapshot private constructor(
    private val catalogIdentity: SignalPackCatalog,
    signalTypes: Collection<AvailableSignalType>
) {
    val signalTypes: List<AvailableSignalType> = java.util.List.copyOf(
        signalTypes.sortedBy(AvailableSignalType::signalType)
    )

    private val signalTypeIds = java.util.Set.copyOf(this.signalTypes.map(AvailableSignalType::signalType))

    fun contains(signalType: String): Boolean = signalTypeIds.contains(signalType)

    fun railPlacementModel(signalType: String): RailPlacementModel? =
        catalogIdentity.signals[signalType]?.model?.presentation?.let(RailPlacementModel::from)

    internal fun represents(catalog: SignalPackCatalog): Boolean = catalogIdentity === catalog

    companion object {
        fun from(catalog: SignalPackCatalog): SignalTypeCatalogSnapshot = SignalTypeCatalogSnapshot(
            catalog,
            catalog.signals.values.map { AvailableSignalType(it.signalType, it.displayName) }
        )
    }
}

internal data class AvailableSignalType(
    val signalType: String,
    val displayName: String
)
