package work.art1st.javarailways.bukkit.http

import work.art1st.javarailways.basic.api.destination.DestinationRole
import work.art1st.javarailways.basic.pathFinding.basicWorldData
import work.art1st.javarailways.simpleato.persistence.SavedTimetableActionTypeV1
import work.art1st.javarailways.simpleato.persistence.SavedTimetableServiceV1
import work.art1st.javarailways.simpleato.persistence.SavedTimetableStepV1
import work.art1st.javarailways.simpleato.persistence.SavedTimetableV1
import work.art1st.javarailways.core.api.physics.World

/** Resolves world-dependent destination roles after the isolated Worker has produced pure JSON. */
object TimetableScriptNormalizer {
    fun normalize(saved: SavedTimetableV1, world: World): SavedTimetableV1 = saved.copy(
        services = saved.services.map { service -> normalizeService(service, world) }
    )

    private fun normalizeService(service: SavedTimetableServiceV1, world: World): SavedTimetableServiceV1 {
        val steps = service.steps.mapIndexed { index, step ->
            normalizeDestinationOptions(step, service.steps.getOrNull(index + 1), world)
        }
        return service.copy(steps = steps)
    }

    private fun normalizeDestinationOptions(
        optionsStep: SavedTimetableStepV1,
        movementStep: SavedTimetableStepV1?,
        world: World
    ): SavedTimetableStepV1 {
        val options = optionsStep.action?.takeIf {
            it.type == SavedTimetableActionTypeV1.DESTINATION_OPTIONS
        } ?: return optionsStep
        val movement = movementStep?.action?.takeIf {
            it.type == SavedTimetableActionTypeV1.DESTINATION
        } ?: return optionsStep
        val target = options.wayPointIds.singleOrNull() ?: return optionsStep
        if (movement.wayPointIds.lastOrNull() != target) return optionsStep
        val role = world.basicWorldData.destinations[target]?.role as? DestinationRole.StationBerthRole
            ?: return optionsStep
        return optionsStep.copy(action = options.copy(
            type = SavedTimetableActionTypeV1.STATION_DESTINATION_OPTIONS,
            wayPointIds = emptyList(),
            stationId = role.stationId.value,
            berthId = role.berthId
        ))
    }
}
