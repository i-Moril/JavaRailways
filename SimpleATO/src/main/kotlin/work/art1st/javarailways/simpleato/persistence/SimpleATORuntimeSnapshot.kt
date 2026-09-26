package work.art1st.javarailways.simpleato.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.timetable.TimetableCheckpointRestoreResult

@Serializable
data class SimpleATORuntimeSnapshotV1(
    val formatVersion: Int,
    val infrastructureRevision: Long,
    val physicsEpoch: Long,
    val dispatcher: SavedDispatcherV1
) {
    fun restore(world: World) {
        require(formatVersion == CURRENT_FORMAT_VERSION) {
            "Unsupported SimpleATO runtime snapshot version: $formatVersion"
        }
        when (val restored = world.simpleAtoWorldData.dispatcher.restoreCheckpoint(world, dispatcher)) {
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
                dispatcher = world.simpleAtoWorldData.dispatcher.saveCheckpoint()
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
