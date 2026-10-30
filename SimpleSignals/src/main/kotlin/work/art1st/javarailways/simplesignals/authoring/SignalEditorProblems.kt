package work.art1st.javarailways.simplesignals.authoring

import work.art1st.javarailways.bukkit.api.diagnostics.WorldIssue
import work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueKind
import work.art1st.javarailways.simplesignals.presentation.ModelPlacement
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsRuntimePublication
import work.art1st.javarailways.simplesignals.runtime.SectionTopologyGuard
import work.art1st.javarailways.simplesignals.runtime.TopologySectionKind
import work.art1st.javarailways.simplesignals.signal.ProtectionSignalKind

/** Static binding faults are prepared once per capture; live content issues never invalidate geometry. */
internal class SignalEditorProblems private constructor(
    private val invalid: Set<String>,
    private val modelsBySignal: Map<String, Set<String>>
) {
    fun models(issues: List<WorldIssue>): Set<String> = buildSet {
        addAll(invalid)
        issues.filter { it.kind == WorldIssueKind.SIGNAL && it.source in SOURCES }.forEach {
            addAll(modelsBySignal[it.objectId].orEmpty())
        }
    }

    companion object {
        private val SOURCES = setOf("simplesignals:topology", "simplesignals:rules", "simplesignals:models",
            "simplesignals:preparation", "simplesignals:model-preparation")

        fun prepare(publication: SimpleSignalsRuntimePublication, guard: SectionTopologyGuard.View): SignalEditorProblems {
            val definitions = publication.definitions
            val unavailable = guard.pending.keys + guard.index.unavailable
            val badSignals = unavailable.filter { it.kind == TopologySectionKind.SIGNAL }.mapTo(hashSetOf()) { it.id }
            val badRegions = unavailable.filter { it.kind == TopologySectionKind.REGION }.mapTo(hashSetOf()) { it.id }
            val badBlocks = unavailable.filter { it.kind == TopologySectionKind.BLOCK }.mapTo(hashSetOf()) { it.id }
            val boundaries = definitions.regions.flatMap { it.boundaries }.mapTo(hashSetOf()) { it.boundaryId }
            val usableRoutes = publication.routeTables.usableDefinitions().filter { it.regionId !in badRegions }.flatMap { it.routes }
            val usableEntries = usableRoutes.mapTo(hashSetOf()) { it.entryBoundaryId }
            val usableRouteIds = usableRoutes.mapTo(hashSetOf()) { it.routeId }
            definitions.fixedBlockSignals.filter { it.controlledBlockId == null || it.controlledBlockId in badBlocks }
                .mapTo(badSignals) { it.signalId }
            definitions.protectionSignals.filter {
                it.bindingId == null || when (it.kind) {
                    ProtectionSignalKind.FIXED_ROUTE -> it.bindingId !in usableRouteIds
                    ProtectionSignalKind.CURRENT_ENTRY -> it.bindingId !in usableEntries
                }
            }.mapTo(badSignals) { it.signalId }
            definitions.routeIndicators.filter { it.entryBoundaryId == null ||
                it.entryBoundaryId !in boundaries || it.entryBoundaryId !in usableEntries }
                .mapTo(badSignals) { it.indicatorId }
            val models = linkedMapOf<String, MutableSet<String>>()
            fun add(id: String, kind: PlacedSignalKind, revision: Long,
                placement: ModelPlacement?) {
                if (placement != null) models.getOrPut(id, ::linkedSetOf).add(PlacedSignalChoice(kind, id, revision, placement).renderObjectId())
            }
            definitions.fixedBlockSignals.forEach { add(it.signalId, PlacedSignalKind.FIXED_BLOCK, it.revision, it.placement) }
            definitions.protectionSignals.forEach { add(it.signalId, PlacedSignalKind.PROTECTION, it.revision, it.placement) }
            definitions.routeIndicators.forEach { add(it.indicatorId, PlacedSignalKind.INDICATOR, it.revision, it.model?.placement) }
            definitions.attachedSignals.forEach {
                add(it.signalId, PlacedSignalKind.ATTACHED, it.revision, it.model.placement)
                models.getOrPut(it.primarySignalId, ::linkedSetOf).addAll(models[it.signalId].orEmpty())
            }
            return SignalEditorProblems(badSignals.flatMapTo(linkedSetOf()) { models[it].orEmpty() },
                models.mapValues { java.util.Set.copyOf(it.value) })
        }
    }
}
