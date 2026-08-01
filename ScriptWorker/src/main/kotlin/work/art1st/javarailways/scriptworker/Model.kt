package work.art1st.javarailways.scriptworker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable enum class SavedTrainSelectorTypeV1 { ANY, TRAIN_ID }
@Serializable enum class SavedLineDirectionV1 { UP, DOWN }
@Serializable enum class SavedTimetableStepTypeV1 { CHECK, AT }
@Serializable enum class SavedTimetableConditionTypeV1 { PREDICATE, ALL, ANY, NOT }
@Serializable enum class SavedTimetableActivationTypeV1 { SCHEDULED, MANUAL, CONDITIONAL }
@Serializable enum class SavedTimetableActionTypeV1 { TAKE_FROM_DEPOT, TAKE_FROM_WAYPOINT, STORE_AT_DEPOT, DESTINATION, DESTINATION_OPTIONS, STOP, HALT, REVERSE, STATION_DESTINATION_OPTIONS, EXTENSION, CONDITIONAL }

@Serializable data class SavedTimetableExtensionCallV1(val typeId: String, val version: Int = 1, val parameters: JsonObject = JsonObject(emptyMap()))
@Serializable data class SavedTimetableConditionV1(val type: SavedTimetableConditionTypeV1, val call: SavedTimetableExtensionCallV1? = null, val conditions: List<SavedTimetableConditionV1> = emptyList())
@Serializable data class SavedTimetableActivationV1(val type: SavedTimetableActivationTypeV1 = SavedTimetableActivationTypeV1.SCHEDULED, val condition: SavedTimetableConditionV1? = null, val falseBehavior: String? = null)
@Serializable data class SavedTimetableActionV1(
    val type: SavedTimetableActionTypeV1,
    val depotId: String? = null,
    val requiredTags: Set<String> = emptySet(), val minimumCars: Int? = null, val maximumCars: Int? = null,
    val minimumSpeedMetersPerSecond: Double? = null, val requiredCapabilities: Set<String> = emptySet(),
    val stablingPointIds: Set<String> = emptySet(), val wayPointIds: List<String> = emptyList(),
    val stopTicks: Long? = null, val stationId: String? = null, val berthId: String? = null,
    /** Presentation-only planned arrival; the runtime snapshot restore deliberately ignores it. */
    val arrivalTick: Long? = null, val minimumDwellTicks: Long? = null, val stoppingOffsetMetersBefore: Double? = null,
    val doorSideOverride: String? = null, val extensionCall: SavedTimetableExtensionCallV1? = null,
    val condition: SavedTimetableConditionV1? = null, val thenActions: List<SavedTimetableActionV1> = emptyList(),
    val elseActions: List<SavedTimetableActionV1> = emptyList()
)
@Serializable data class SavedTimetableStepV1(val type: SavedTimetableStepTypeV1, val tick: Long? = null, val wayPointId: String? = null, val action: SavedTimetableActionV1? = null)
@Serializable data class SavedTimetableServiceV1(val id: String, val selectorType: SavedTrainSelectorTypeV1, val trainId: String? = null, val steps: List<SavedTimetableStepV1>, val activation: SavedTimetableActivationV1 = SavedTimetableActivationV1(), val diagramLineId: String? = null, val lineDirection: SavedLineDirectionV1? = null)
@Serializable data class SavedDiagramLineV1(val id: String, val upStationIds: List<String>, val downStationIds: List<String>)
@Serializable enum class SavedObjectSelectorTypeV1 { REGEX, AND, OR, NOT, TAGS }
@Serializable data class SavedObjectSelectorV1(val type: SavedObjectSelectorTypeV1, val pattern: String? = null, val selectors: List<SavedObjectSelectorV1> = emptyList(), val tags: Set<String> = emptySet())
@Serializable data class SavedInitialPositionV1(val trainSelector: SavedObjectSelectorV1, val wayPointSelector: SavedObjectSelectorV1)
@Serializable data class SavedTimetableV1(val id: String, val periodDays: Long, val services: List<SavedTimetableServiceV1>, val diagramLines: List<SavedDiagramLineV1> = emptyList(), val initialPositions: List<SavedInitialPositionV1> = emptyList())
