package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.ServicesManager
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService
import work.art1st.javarailways.bukkit.api.observation.RailwayObservationService
import work.art1st.javarailways.bukkit.api.operation.PlayerOperationService
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
import work.art1st.javarailways.bukkit.api.model.ModelPresentationService
import work.art1st.javarailways.bukkit.api.editor.EditorTextService
import work.art1st.javarailways.bukkit.api.signal.InspectionSourceService
import work.art1st.javarailways.bukkit.api.signal.SignalEpochSourceService
import work.art1st.javarailways.bukkit.api.signal.RoutePlanningRestrictionSourceService

/** Registers the complete public JavaRailways service set under one Bukkit plugin owner. */
@JvmSynthetic
internal fun ServicesManager.registerJavaRailwaysServices(
    owner: Plugin,
    railTargets: RailTargetService,
    playerOperations: PlayerOperationService,
    observations: RailwayObservationService,
    infrastructure: InfrastructureResourceService,
    signalSources: SignalEpochSourceService,
    inspectionSources: InspectionSourceService,
    routePlanningSources: RoutePlanningRestrictionSourceService,
    modelPresentations: ModelPresentationService,
    editorTexts: EditorTextService
) {
    register(RailTargetService::class.java, railTargets, owner, ServicePriority.Normal)
    register(PlayerOperationService::class.java, playerOperations, owner, ServicePriority.Normal)
    register(ModelPresentationService::class.java, modelPresentations, owner, ServicePriority.Normal)
    register(EditorTextService::class.java, editorTexts, owner, ServicePriority.Normal)
    register(RailwayObservationService::class.java, observations, owner, ServicePriority.Normal)
    register(InfrastructureResourceService::class.java, infrastructure, owner, ServicePriority.Normal)
    register(SignalEpochSourceService::class.java, signalSources, owner, ServicePriority.Normal)
    register(InspectionSourceService::class.java, inspectionSources, owner, ServicePriority.Normal)
    register(
        RoutePlanningRestrictionSourceService::class.java,
        routePlanningSources,
        owner,
        ServicePriority.Normal
    )
}
