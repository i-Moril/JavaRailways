package work.art1st.javarailways.basic.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BasicRuntimeSnapshotV3(
    val formatVersion: Int,
    val infrastructureRevision: Long,
    val physicsEpoch: Long
) {
    fun restore() {
        require(formatVersion == CURRENT_FORMAT_VERSION) {
            "Unsupported Basic runtime snapshot version: $formatVersion"
        }
    }

    companion object {
        const val CURRENT_FORMAT_VERSION = 3

        fun capture(
            infrastructureRevision: Long,
            physicsEpoch: Long
        ): BasicRuntimeSnapshotV3 {
            require(infrastructureRevision >= 0L && physicsEpoch >= 0L)
            return BasicRuntimeSnapshotV3(
                formatVersion = CURRENT_FORMAT_VERSION,
                infrastructureRevision = infrastructureRevision,
                physicsEpoch = physicsEpoch
            )
        }
    }
}

object BasicRuntimeSnapshotCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; prettyPrint = true }
    fun encode(value: BasicRuntimeSnapshotV3): String =
        json.encodeToString(BasicRuntimeSnapshotV3.serializer(), value)
    fun decode(value: String): BasicRuntimeSnapshotV3 =
        json.decodeFromString(BasicRuntimeSnapshotV3.serializer(), value).also {
            require(it.formatVersion == BasicRuntimeSnapshotV3.CURRENT_FORMAT_VERSION) {
                "Unsupported Basic runtime snapshot version: ${it.formatVersion}"
            }
        }
}
