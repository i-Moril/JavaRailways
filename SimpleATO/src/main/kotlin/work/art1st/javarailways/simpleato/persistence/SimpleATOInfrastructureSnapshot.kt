package work.art1st.javarailways.simpleato.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import work.art1st.javarailways.basic.api.station.StationId
import work.art1st.javarailways.basic.pathFinding.basicWorldData
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.simpleato.api.line.Line
import work.art1st.javarailways.simpleato.api.line.LineId
import work.art1st.javarailways.simpleato.simpleAtoWorldData

@Serializable
data class SavedLineV1(
    val id: String,
    val displayName: String,
    val upStationIds: List<String>,
    val downStationIds: List<String>,
    val color: String? = null
) {
    fun restore(): Line = Line(
        LineId(id),
        displayName,
        upStationIds.map(::StationId),
        downStationIds.map(::StationId),
        color
    )

    companion object {
        fun capture(value: Line): SavedLineV1 = SavedLineV1(
            id = value.id.value,
            displayName = value.displayName,
            upStationIds = value.upStationIds.map { it.value },
            downStationIds = value.downStationIds.map { it.value },
            color = value.color
        )
    }
}

/** SimpleATO-owned infrastructure definitions restored after Basic operational stops and stations. */
@Serializable
data class SimpleATOInfrastructureSnapshotV1(
    val formatVersion: Int,
    val infrastructureRevision: Long,
    val lines: List<SavedLineV1>,
    val lineRevision: Long
) {
    fun restore(world: World) {
        require(formatVersion == CURRENT_FORMAT_VERSION) {
            "Unsupported SimpleATO infrastructure snapshot version: $formatVersion"
        }
        val restoredLines = lines.map(SavedLineV1::restore)
        val duplicateLineIds = restoredLines.groupingBy(Line::id).eachCount()
            .filterValues { it > 1 }
            .keys
        check(duplicateLineIds.isEmpty()) {
            "SimpleATO infrastructure snapshot contains duplicate lines: ${duplicateLineIds.joinToString()}"
        }
        val stationIds = world.basicWorldData.stations.all().mapTo(HashSet()) { it.id }
        val missingStations = restoredLines
            .flatMap { it.upStationIds + it.downStationIds }
            .filterNot { it in stationIds }
            .distinct()
        check(missingStations.isEmpty()) {
            "SimpleATO infrastructure snapshot contains lines with missing stations: ${missingStations.joinToString()}"
        }
        val store = world.simpleAtoWorldData.lines
        check(store.snapshot().let { it.lines.isEmpty() && it.revision == 0L }) {
            "SimpleATO LineStore is not empty"
        }
        store.restore(restoredLines, lineRevision)
    }

    companion object {
        const val CURRENT_FORMAT_VERSION = 1

        fun capture(world: World, infrastructureRevision: Long): SimpleATOInfrastructureSnapshotV1 {
            require(infrastructureRevision >= 0L)
            val lineSnapshot = world.simpleAtoWorldData.lines.snapshot()
            return SimpleATOInfrastructureSnapshotV1(
                formatVersion = CURRENT_FORMAT_VERSION,
                infrastructureRevision = infrastructureRevision,
                lines = lineSnapshot.lines.map(SavedLineV1::capture),
                lineRevision = lineSnapshot.revision
            )
        }
    }
}

object SimpleATOInfrastructureSnapshotCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        prettyPrint = true
    }

    fun encode(snapshot: SimpleATOInfrastructureSnapshotV1): String =
        json.encodeToString(SimpleATOInfrastructureSnapshotV1.serializer(), snapshot)

    fun decode(data: String): SimpleATOInfrastructureSnapshotV1 =
        json.decodeFromString(SimpleATOInfrastructureSnapshotV1.serializer(), data).also {
            require(it.formatVersion == SimpleATOInfrastructureSnapshotV1.CURRENT_FORMAT_VERSION) {
                "Unsupported SimpleATO infrastructure snapshot version: ${it.formatVersion}"
            }
        }
}
