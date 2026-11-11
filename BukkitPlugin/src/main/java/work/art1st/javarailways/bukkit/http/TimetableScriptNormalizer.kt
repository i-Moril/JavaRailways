package work.art1st.javarailways.bukkit.http

import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationStore
import work.art1st.javarailways.simpleato.persistence.SavedTimetableActionTypeV1
import work.art1st.javarailways.simpleato.persistence.SavedTimetableServiceV1
import work.art1st.javarailways.simpleato.persistence.SavedTimetableStepV1
import work.art1st.javarailways.simpleato.persistence.SavedTimetableV1

/** Resolves world-dependent destination roles after the isolated Worker has produced pure JSON. */
object TimetableScriptNormalizer {
    fun normalize(
        saved: SavedTimetableV1,
        destinations: OperationalDestinationStore
    ): SavedTimetableV1 = saved.copy(
        services = saved.services.map { service -> normalizeService(service, destinations) }
    )

    private fun normalizeService(
        service: SavedTimetableServiceV1,
        destinations: OperationalDestinationStore
    ): SavedTimetableServiceV1 {
        val steps = service.steps.mapIndexed { index, step ->
            normalizeDestinationOptions(step, service.steps.getOrNull(index + 1), destinations)
        }
        return service.copy(steps = steps)
    }

    private fun normalizeDestinationOptions(
        optionsStep: SavedTimetableStepV1,
        movementStep: SavedTimetableStepV1?,
        destinations: OperationalDestinationStore
    ): SavedTimetableStepV1 {
        val options = optionsStep.action?.takeIf {
            it.type == SavedTimetableActionTypeV1.DESTINATION_OPTIONS
        } ?: return optionsStep
        val movement = movementStep?.action?.takeIf {
            it.type == SavedTimetableActionTypeV1.DESTINATION
        } ?: return optionsStep
        val target = options.wayPointIds.singleOrNull() ?: return optionsStep
        if (movement.wayPointIds.lastOrNull() != target) return optionsStep
        val role = destinations[target]?.role as? DestinationRole.StationBerthRole
            ?: return optionsStep
        return optionsStep.copy(action = options.copy(
            type = SavedTimetableActionTypeV1.STATION_DESTINATION_OPTIONS,
            wayPointIds = emptyList(),
            stationId = role.stationId.value,
            berthId = role.berthId
        ))
    }
}
