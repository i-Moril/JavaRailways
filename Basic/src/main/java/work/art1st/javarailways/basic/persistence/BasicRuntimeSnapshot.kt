package work.art1st.javarailways.basic.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import work.art1st.javarailways.basic.api.depot.DepotId
import work.art1st.javarailways.basic.api.depot.RollingStockRecord
import work.art1st.javarailways.basic.api.depot.RollingStockSnapshot
import work.art1st.javarailways.basic.api.depot.RollingStockState
import work.art1st.javarailways.basic.api.depot.StablingPointRef
import work.art1st.javarailways.basic.pathFinding.basicWorldData
import work.art1st.javarailways.core.api.physics.World

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
data class BasicRuntimeSnapshotV1(
    val formatVersion: Int = 1,
    val infrastructureRevision: Long,
    val physicsEpoch: Long,
    val timetables: List<SavedTimetableV1>,
    val dispatcher: SavedDispatcherV1,
    val rollingStock: List<SavedRollingStockV1> = emptyList()
) {
    fun restore(world: World) {
        check(world.basicWorldData.timetables.all().isEmpty())
        timetables.map(SavedTimetableV1::restore).forEach(world.basicWorldData.timetables::add)
        rollingStock.map(SavedRollingStockV1::restore)
            .forEach(world.basicWorldData.depots::restoreRollingStock)
        world.basicWorldData.dispatcher.restoreCheckpoint(dispatcher)
    }

    companion object {
        fun capture(
            world: World,
            infrastructureRevision: Long,
            physicsEpoch: Long
        ): BasicRuntimeSnapshotV1 {
            require(infrastructureRevision >= 0L && physicsEpoch >= 0L)
            return BasicRuntimeSnapshotV1(
                infrastructureRevision = infrastructureRevision,
                physicsEpoch = physicsEpoch,
                timetables = world.basicWorldData.timetables.all().map(SavedTimetableV1::capture),
                dispatcher = world.basicWorldData.dispatcher.saveCheckpoint(),
                rollingStock = world.basicWorldData.depots.allRollingStock()
                    .map(SavedRollingStockV1::capture)
            )
        }
    }
}

object BasicRuntimeSnapshotCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }
    fun encode(value: BasicRuntimeSnapshotV1): String =
        json.encodeToString(BasicRuntimeSnapshotV1.serializer(), value)
    fun decode(value: String): BasicRuntimeSnapshotV1 =
        json.decodeFromString(BasicRuntimeSnapshotV1.serializer(), value)
}
