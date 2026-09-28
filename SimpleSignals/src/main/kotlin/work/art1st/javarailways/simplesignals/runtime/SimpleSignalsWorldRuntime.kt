package work.art1st.javarailways.simplesignals.runtime

import org.bukkit.plugin.Plugin
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictionSource
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService
import work.art1st.javarailways.bukkit.api.signal.RoutePlanningRestrictionSourceService
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simpleato.api.route.RoutePlanningRestrictionBinding
import work.art1st.javarailways.simplesignals.region.ProtectionRegionCatalog
import work.art1st.javarailways.simplesignals.region.ProtectionRegionCatalogStatus
import work.art1st.javarailways.simplesignals.region.ProtectionRegionDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTableCatalog
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTableCatalogStatus
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTableDefinition
import java.util.UUID

internal const val PROTECTION_ROUTE_SOURCE_ID = "simplesignals:fixed-protection-routes"

internal enum class SimpleSignalsDefinitionPublicationStatus {
    PUBLISHED,
    UNCHANGED,
    REJECTED
}

internal data class SimpleSignalsDefinitionPublicationResult(
    val status: SimpleSignalsDefinitionPublicationStatus,
    val diagnostics: List<String> = emptyList()
)

/**
 * One plugin-owned runtime for one exact host world load.
 *
 * Definitions are compiled into fresh candidate catalogs and become visible through one volatile
 * publication only after both catalogs accept them. Route search reads that publication and never
 * rebuilds region or route-table state inside its callback.
 */
internal class SimpleSignalsWorldRuntime(
    val worldId: UUID
) {
    private val lock = Any()

    @Volatile
    private var publication: Publication? = null

    val routePlanningBinding = RoutePlanningRestrictionBinding(
        PROTECTION_ROUTE_SOURCE_ID,
        RoutePlanningRestrictionSource { topology ->
            val captured = publication
                ?: error("SimpleSignals definitions have not been published for world $worldId")
            captured.routeTables.restrictionSource.restrictions(topology)
        }
    )

    fun publishDefinitions(
        topology: TopologyState,
        definitionRevision: Long,
        regionDefinitions: Collection<ProtectionRegionDefinition>,
        routeTableDefinitions: Collection<ProtectionRouteTableDefinition>
    ): SimpleSignalsDefinitionPublicationResult = synchronized(lock) {
        require(definitionRevision >= 0L) { "definitionRevision must be non-negative" }
        val copiedRegions = java.util.List.copyOf(regionDefinitions.sortedBy { it.regionId })
        val copiedTables = java.util.List.copyOf(routeTableDefinitions.sortedBy { it.tableId })
        val current = publication
        if (current != null && definitionRevision < current.definitionRevision) {
            return@synchronized rejected(
                "definition revision $definitionRevision precedes published revision " +
                    current.definitionRevision
            )
        }
        if (current != null && definitionRevision == current.definitionRevision &&
            (copiedRegions != current.regionDefinitions || copiedTables != current.routeTableDefinitions)
        ) {
            return@synchronized rejected(
                "definition content changed without advancing revision $definitionRevision"
            )
        }
        validateRetainedTableRevisions(current?.routeTableDefinitions.orEmpty(), copiedTables)?.let {
            return@synchronized rejected(it)
        }
        if (current != null && current.topology === topology &&
            definitionRevision == current.definitionRevision &&
            copiedRegions == current.regionDefinitions && copiedTables == current.routeTableDefinitions
        ) {
            return@synchronized SimpleSignalsDefinitionPublicationResult(
                SimpleSignalsDefinitionPublicationStatus.UNCHANGED
            )
        }

        val candidateRegions = ProtectionRegionCatalog()
        val regionResult = candidateRegions.publish(topology, definitionRevision, copiedRegions)
        if (regionResult.status == ProtectionRegionCatalogStatus.REJECTED) {
            return@synchronized rejected(regionResult.diagnostics.map { it.detail })
        }
        val candidateTables = ProtectionRouteTableCatalog(candidateRegions)
        val tableResult = candidateTables.publish(definitionRevision, copiedTables)
        if (tableResult.status == ProtectionRouteTableCatalogStatus.REJECTED) {
            return@synchronized rejected(tableResult.diagnostics.map { it.detail })
        }

        publication = Publication(
            topology,
            definitionRevision,
            copiedRegions,
            copiedTables,
            candidateRegions,
            candidateTables
        )
        SimpleSignalsDefinitionPublicationResult(SimpleSignalsDefinitionPublicationStatus.PUBLISHED)
    }

    private fun validateRetainedTableRevisions(
        previous: Collection<ProtectionRouteTableDefinition>,
        candidate: Collection<ProtectionRouteTableDefinition>
    ): String? {
        val previousById = previous.associateBy { it.tableId }
        for (definition in candidate) {
            val retained = previousById[definition.tableId] ?: continue
            if (definition.revision < retained.revision) {
                return "route table ${definition.tableId} revision ${definition.revision} precedes " +
                    "published revision ${retained.revision}"
            }
            if (definition.revision == retained.revision && definition != retained) {
                return "route table ${definition.tableId} changed without advancing revision " +
                    definition.revision
            }
        }
        return null
    }

    private fun rejected(detail: String) = rejected(listOf(detail))

    private fun rejected(diagnostics: List<String>) = SimpleSignalsDefinitionPublicationResult(
        SimpleSignalsDefinitionPublicationStatus.REJECTED,
        java.util.List.copyOf(diagnostics)
    )

    private data class Publication(
        val topology: TopologyState,
        val definitionRevision: Long,
        val regionDefinitions: List<ProtectionRegionDefinition>,
        val routeTableDefinitions: List<ProtectionRouteTableDefinition>,
        val regions: ProtectionRegionCatalog,
        val routeTables: ProtectionRouteTableCatalog
    )
}

enum class SimpleSignalsWorldInstallStatus {
    INSTALLED,
    ALREADY_INSTALLED,
    UNAVAILABLE
}

data class SimpleSignalsWorldInstallResult(
    val status: SimpleSignalsWorldInstallStatus,
    val detail: String = ""
)

/** Owns the local runtime map and the single host route-planning binding for each world load. */
class SimpleSignalsWorldRuntimeManager(
    private val plugin: Plugin,
    private val infrastructure: InfrastructureResourceService,
    private val routePlanningSources: RoutePlanningRestrictionSourceService
) {
    private val lock = Any()
    private val runtimes = linkedMapOf<UUID, SimpleSignalsWorldRuntime>()

    fun install(worldId: UUID): SimpleSignalsWorldInstallResult = synchronized(lock) {
        if (worldId in runtimes) {
            return@synchronized SimpleSignalsWorldInstallResult(
                SimpleSignalsWorldInstallStatus.ALREADY_INSTALLED
            )
        }
        try {
            val topology = infrastructure.currentTopology(worldId)
            val runtime = SimpleSignalsWorldRuntime(worldId)
            val initial = runtime.publishDefinitions(topology, 0L, emptyList(), emptyList())
            check(initial.status == SimpleSignalsDefinitionPublicationStatus.PUBLISHED) {
                "empty SimpleSignals definitions were rejected: ${initial.diagnostics.joinToString()}"
            }
            routePlanningSources.replaceRoutePlanningSources(
                plugin,
                worldId,
                listOf(runtime.routePlanningBinding)
            )
            runtimes[worldId] = runtime
            SimpleSignalsWorldInstallResult(SimpleSignalsWorldInstallStatus.INSTALLED)
        } catch (failure: Exception) {
            SimpleSignalsWorldInstallResult(
                SimpleSignalsWorldInstallStatus.UNAVAILABLE,
                failure.message ?: failure.javaClass.simpleName
            )
        }
    }

    fun remove(worldId: UUID) {
        synchronized(lock) { runtimes.remove(worldId) }
    }

    fun clearLocal() {
        synchronized(lock) { runtimes.clear() }
    }

    internal fun publishDefinitions(
        worldId: UUID,
        definitionRevision: Long,
        regionDefinitions: Collection<ProtectionRegionDefinition>,
        routeTableDefinitions: Collection<ProtectionRouteTableDefinition>
    ): SimpleSignalsDefinitionPublicationResult {
        val topology = infrastructure.currentTopology(worldId)
        val runtime = synchronized(lock) {
            checkNotNull(runtimes[worldId]) { "SimpleSignals world runtime is not installed: $worldId" }
        }
        return runtime.publishDefinitions(
            topology,
            definitionRevision,
            regionDefinitions,
            routeTableDefinitions
        )
    }
}
