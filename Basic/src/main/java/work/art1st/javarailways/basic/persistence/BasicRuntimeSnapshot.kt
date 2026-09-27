package work.art1st.javarailways.basic.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BasicRuntimeSnapshotV4(
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
        const val CURRENT_FORMAT_VERSION = 4

        fun capture(
            infrastructureRevision: Long,
            physicsEpoch: Long
        ): BasicRuntimeSnapshotV4 {
            require(infrastructureRevision >= 0L && physicsEpoch >= 0L)
            return BasicRuntimeSnapshotV4(
                formatVersion = CURRENT_FORMAT_VERSION,
                infrastructureRevision = infrastructureRevision,
                physicsEpoch = physicsEpoch
            )
        }
    }
}

object BasicRuntimeSnapshotCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; prettyPrint = true }
    fun encode(value: BasicRuntimeSnapshotV4): String =
        json.encodeToString(BasicRuntimeSnapshotV4.serializer(), value)
    fun decode(value: String): BasicRuntimeSnapshotV4 =
        json.decodeFromString(BasicRuntimeSnapshotV4.serializer(), value).also {
            require(it.formatVersion == BasicRuntimeSnapshotV4.CURRENT_FORMAT_VERSION) {
                "Unsupported Basic runtime snapshot version: ${it.formatVersion}"
            }
        }
}
