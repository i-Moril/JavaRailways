package work.art1st.javarailways.simpleato.timetable

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import work.art1st.javarailways.basic.api.destination.DestinationRole
import work.art1st.javarailways.basic.api.destination.OperationalDestinationService
import work.art1st.javarailways.basic.api.station.DoorSide
import work.art1st.javarailways.simpleato.timetable.Timetable
import work.art1st.javarailways.simpleato.timetable.TimetableAction
import work.art1st.javarailways.simpleato.timetable.TimetableService
import work.art1st.javarailways.simpleato.timetable.TimetableStep
import work.art1st.javarailways.simpleato.timetable.TrainSelector
import work.art1st.javarailways.core.api.physics.World
import java.util.Locale

data class ManualNavigationOptions(
    val minimumDwellTicks: Long? = null,
    val stoppingOffsetMetersBefore: Double? = null,
    val doorSide: DoorSide? = null
) {
    init {
        require(minimumDwellTicks == null || minimumDwellTicks >= 0L)
        require(stoppingOffsetMetersBefore == null ||
            stoppingOffsetMetersBefore.isFinite() && stoppingOffsetMetersBefore >= 0.0)
    }
}

data class ManualNavigationWaypoint(
    val id: String,
    val pass: Boolean,
    val options: ManualNavigationOptions? = null
) {
    init {
        require(id.isNotBlank() && id == id.trim())
        require(!pass || options == null) { "pass waypoint cannot declare stop options" }
    }
}

object ManualNavigationParser {
    private val json = Json { isLenient = false; ignoreUnknownKeys = false }
    private val optionNames = setOf(
        "minimumDwellTicks",
        "stoppingOffsetMetersBefore",
        "doorSide"
    )

    fun parse(tokens: List<String>): List<ManualNavigationWaypoint> {
        require(tokens.isNotEmpty()) { "至少需要一个 waypoint" }
        val points = tokens.mapIndexed { index, token -> parseToken(token, index) }
        require(!points.last().pass) { "命令终点必须是停车点，不能以 + 开头" }
        return points
    }

    private fun parseToken(token: String, index: Int): ManualNavigationWaypoint {
        require(token.isNotBlank()) { "waypoint#$index 不能为空" }
        val pass = token.startsWith('+')
        val body = if (pass) token.drop(1) else token
        val optionsStart = body.indexOf('{')
        val id = if (optionsStart < 0) body else body.substring(0, optionsStart)
        require(id.isNotBlank() && id == id.trim()) { "waypoint#$index ID 非法" }
        if (optionsStart < 0) return ManualNavigationWaypoint(id, pass)
        require(!pass) { "通过点 +$id 不能声明停车 options" }
        val rawOptions = body.substring(optionsStart)
        val objectValue = runCatching { json.parseToJsonElement(rawOptions) as? JsonObject }
            .getOrNull() ?: throw IllegalArgumentException("waypoint#$index options 必须是 JSON object")
        val unknown = objectValue.keys - optionNames
        require(unknown.isEmpty()) { "waypoint#$index 未知 options: ${unknown.sorted().joinToString()}" }
        val minimumDwellTicks = objectValue["minimumDwellTicks"]?.jsonPrimitive?.longOrNull
            ?: objectValue["minimumDwellTicks"]?.let {
                throw IllegalArgumentException("waypoint#$index minimumDwellTicks 必须是整数")
            }
        val stoppingOffset = objectValue["stoppingOffsetMetersBefore"]?.jsonPrimitive?.doubleOrNull
            ?: objectValue["stoppingOffsetMetersBefore"]?.let {
                throw IllegalArgumentException(
                    "waypoint#$index stoppingOffsetMetersBefore 必须是数字"
                )
            }
        val doorSide = objectValue["doorSide"]?.jsonPrimitive?.content?.let { raw ->
            runCatching { DoorSide.valueOf(raw.uppercase(Locale.ROOT)) }.getOrElse {
                throw IllegalArgumentException(
                    "waypoint#$index doorSide 必须是 none/left/right/both"
                )
            }
        }
        return ManualNavigationWaypoint(
            id = id,
            pass = false,
            options = ManualNavigationOptions(
                minimumDwellTicks = minimumDwellTicks,
                stoppingOffsetMetersBefore = stoppingOffset,
                doorSide = doorSide
            )
        )
    }
}

object ManualNavigationTimetableFactory {
    fun create(
        world: World,
        timetableId: String,
        serviceId: String,
        trainId: String,
        originWayPointId: String,
        points: List<ManualNavigationWaypoint>
    ): Timetable {
        require(points.isNotEmpty() && !points.last().pass)
        val steps = ArrayList<TimetableStep>()
        val destinations = OperationalDestinationService(world)
        steps += TimetableStep.Check(originWayPointId)
        val passPoints = ArrayList<String>()
        points.forEach { point ->
            if (point.pass) {
                passPoints += point.id
            } else {
                val stationRole = destinations[point.id]?.role as?
                    DestinationRole.StationBerthRole
                val options = point.options
                if (stationRole != null) {
                    steps += TimetableStep.At(
                        0L,
                        TimetableAction.CallStation(
                            stationId = stationRole.stationId.value,
                            berthId = stationRole.berthId,
                            minimumDwellTicks = options?.minimumDwellTicks,
                            stoppingOffsetMetersBefore = options?.stoppingOffsetMetersBefore,
                            doorSideOverride = options?.doorSide
                        )
                    )
                } else if (options != null) {
                    steps += TimetableStep.At(
                        0L,
                        TimetableAction.DestinationOptions(
                            destinationId = point.id,
                            minimumDwellTicks = options.minimumDwellTicks,
                            stoppingOffsetMetersBefore = options.stoppingOffsetMetersBefore,
                            doorSide = options.doorSide
                        )
                    )
                }
                val targets = passPoints + point.id
                steps += TimetableStep.At(
                    0L,
                    if (targets.size == 1) TimetableAction.GoTo(targets.single())
                    else TimetableAction.GoVia(targets)
                )
                passPoints.clear()
            }
        }
        return Timetable(
            id = timetableId,
            periodDays = 1L,
            services = listOf(
                TimetableService(
                    id = serviceId,
                    trainSelector = TrainSelector.TrainId(trainId),
                    steps = steps
                )
            )
        )
    }
}
