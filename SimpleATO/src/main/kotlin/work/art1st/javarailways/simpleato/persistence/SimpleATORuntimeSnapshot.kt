package work.art1st.javarailways.simpleato.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.simpleato.api.depot.DepotId
import work.art1st.javarailways.simpleato.api.depot.RollingStockRecord
import work.art1st.javarailways.simpleato.api.depot.RollingStockSnapshot
import work.art1st.javarailways.simpleato.api.depot.RollingStockState
import work.art1st.javarailways.simpleato.api.depot.StablingPointRef
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.timetable.TimetableCheckpointRestoreResult

@Serializable
data class SavedRollingStockV1(
    val trainId: String,
    val homeDepotId: String,
    val modelPackageId: String,
    val tags: Set<String>,
    val carCount: Int,
    val lengthMeters: Double,
    val maximumSpeedMetersPerSecond: Double,
    val capabilities: Set<String>,
    val state: String,
    val stablingPointId: String? = null,
    val assignmentId: String? = null
) {
    fun restore(): RollingStockSnapshot = RollingStockSnapshot(
        record = RollingStockRecord(
            trainId, DepotId(homeDepotId), modelPackageId, tags, carCount,
            lengthMeters, maximumSpeedMetersPerSecond, capabilities
        ),
        state = RollingStockState.valueOf(state),
        stablingPointRef = stablingPointId?.let { StablingPointRef(DepotId(homeDepotId), it) },
        assignmentId = assignmentId
    )

    companion object {
        fun capture(value: RollingStockSnapshot): SavedRollingStockV1 = SavedRollingStockV1(
            trainId = value.record.trainId,
            homeDepotId = value.record.homeDepotId.value,
            modelPackageId = value.record.modelPackageId,
            tags = value.record.tags,
            carCount = value.record.carCount,
            lengthMeters = value.record.lengthMeters,
            maximumSpeedMetersPerSecond = value.record.maximumSpeedMetersPerSecond,
            capabilities = value.record.capabilities,
            state = value.state.name,
            stablingPointId = value.stablingPointRef?.pointId,
            assignmentId = value.assignmentId
        )
    }
}

@Serializable
data class SimpleATORuntimeSnapshotV1(
    val formatVersion: Int,
    val infrastructureRevision: Long,
    val physicsEpoch: Long,
    val dispatcher: SavedDispatcherV1,
    val timetables: List<SavedTimetableV1> = emptyList(),
    val rollingStock: List<SavedRollingStockV1> = emptyList()
) {
    fun restore(world: World) {
        require(formatVersion == CURRENT_FORMAT_VERSION) {
            "Unsupported SimpleATO runtime snapshot version: $formatVersion"
        }
        val runtime = world.simpleAtoWorldData
        check(runtime.timetables.all().isEmpty())
        val restoredTimetables = timetables.map(SavedTimetableV1::restore)
        require(restoredTimetables.map { it.id }.distinct().size == restoredTimetables.size) {
            "SimpleATO runtime snapshot contains duplicate timetable IDs"
        }
        val restoredRollingStock = rollingStock.map(SavedRollingStockV1::restore)
        runtime.depots.restoreRollingStock(restoredRollingStock)
        restoredTimetables.forEach(runtime.timetables::add)
        when (val restored = runtime.dispatcher.restoreCheckpoint(world, dispatcher)) {
            TimetableCheckpointRestoreResult.Restored -> Unit
            is TimetableCheckpointRestoreResult.Rejected ->
                error("SimpleATO dispatcher restore rejected: ${restored.reason}")
        }
    }

    companion object {
        const val CURRENT_FORMAT_VERSION = 1

        fun capture(
            world: World,
            infrastructureRevision: Long,
            physicsEpoch: Long
        ): SimpleATORuntimeSnapshotV1 {
            require(infrastructureRevision >= 0L && physicsEpoch >= 0L)
            return SimpleATORuntimeSnapshotV1(
                formatVersion = CURRENT_FORMAT_VERSION,
                infrastructureRevision = infrastructureRevision,
                physicsEpoch = physicsEpoch,
                dispatcher = world.simpleAtoWorldData.dispatcher.saveCheckpoint(),
                timetables = world.simpleAtoWorldData.timetables.all().map(SavedTimetableV1::capture),
                rollingStock = world.simpleAtoWorldData.depots.allRollingStock()
                    .map(SavedRollingStockV1::capture)
            )
        }
    }
}

object SimpleATORuntimeSnapshotCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; prettyPrint = true }

    fun encode(value: SimpleATORuntimeSnapshotV1): String =
        json.encodeToString(SimpleATORuntimeSnapshotV1.serializer(), value)

    fun decode(value: String): SimpleATORuntimeSnapshotV1 =
        json.decodeFromString(SimpleATORuntimeSnapshotV1.serializer(), value).also {
            require(it.formatVersion == SimpleATORuntimeSnapshotV1.CURRENT_FORMAT_VERSION) {
                "Unsupported SimpleATO runtime snapshot version: ${it.formatVersion}"
            }
        }
}
