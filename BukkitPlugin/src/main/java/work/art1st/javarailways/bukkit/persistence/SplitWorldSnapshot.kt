package work.art1st.javarailways.bukkit.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotCodec
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotV1
import work.art1st.javarailways.basic.persistence.BasicRuntimeSnapshotCodec
import work.art1st.javarailways.basic.persistence.BasicRuntimeSnapshotV1
import work.art1st.javarailways.core.persistence.WorldSnapshotCodec
import work.art1st.javarailways.core.physics.CoreWorld

@Serializable
data class InfrastructureFileV1(
    val formatVersion: Int,
    val infrastructureRevision: Long,
    val coreSnapshotJson: String,
    val basicSnapshotJson: String
) {
    fun core(): CoreWorld.SavedInfrastructureV1 =
        WorldSnapshotCodec.decodeInfrastructure(coreSnapshotJson).also {
            require(it.infrastructureRevision == infrastructureRevision)
        }
    fun basic(): BasicInfrastructureSnapshotV1 =
        BasicInfrastructureSnapshotCodec.decode(basicSnapshotJson).also {
            require(it.infrastructureRevision == infrastructureRevision)
        }
}

@Serializable
data class RuntimeFileV1(
    val formatVersion: Int,
    val infrastructureRevision: Long,
    val physicsEpoch: Long,
    val coreSnapshotJson: String,
    val basicSnapshotJson: String
) {
    fun core(): CoreWorld.SavedRuntimeV1 = WorldSnapshotCodec.decodeRuntime(coreSnapshotJson).also {
        require(it.infrastructureRevision == infrastructureRevision && it.physicsEpoch == physicsEpoch)
    }
    fun basic(): BasicRuntimeSnapshotV1 = BasicRuntimeSnapshotCodec.decode(basicSnapshotJson).also {
        require(it.infrastructureRevision == infrastructureRevision && it.physicsEpoch == physicsEpoch)
    }
}

data class CapturedWorldSnapshots(
    val infrastructure: InfrastructureFileV1,
    val runtime: RuntimeFileV1
) {
    init {
        require(infrastructure.infrastructureRevision == runtime.infrastructureRevision)
    }
}

data class WorldSnapshotCapture(
    val coreInfrastructure: CoreWorld.SavedInfrastructureV1,
    val basicInfrastructure: BasicInfrastructureSnapshotV1,
    val coreRuntime: CoreWorld.SavedRuntimeV1,
    val basicRuntime: BasicRuntimeSnapshotV1
) {
    init {
        val revision = coreInfrastructure.infrastructureRevision
        require(basicInfrastructure.infrastructureRevision == revision)
        require(coreRuntime.infrastructureRevision == revision)
        require(basicRuntime.infrastructureRevision == revision)
        require(coreRuntime.physicsEpoch == basicRuntime.physicsEpoch)
    }

    fun encode(): CapturedWorldSnapshots = CapturedWorldSnapshots(
        SplitWorldSnapshotCodec.infrastructure(coreInfrastructure, basicInfrastructure),
        SplitWorldSnapshotCodec.runtime(coreRuntime, basicRuntime)
    )
}

object SplitWorldSnapshotCodec {
    const val CURRENT_FORMAT_VERSION = 1
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; prettyPrint = true }

    fun infrastructure(
        core: CoreWorld.SavedInfrastructureV1,
        basic: BasicInfrastructureSnapshotV1
    ): InfrastructureFileV1 {
        require(core.infrastructureRevision == basic.infrastructureRevision)
        return InfrastructureFileV1(
            formatVersion = CURRENT_FORMAT_VERSION,
            infrastructureRevision = core.infrastructureRevision,
            coreSnapshotJson = WorldSnapshotCodec.encodeInfrastructure(core),
            basicSnapshotJson = BasicInfrastructureSnapshotCodec.encode(basic)
        )
    }

    fun runtime(
        core: CoreWorld.SavedRuntimeV1,
        basic: BasicRuntimeSnapshotV1
    ): RuntimeFileV1 {
        require(core.infrastructureRevision == basic.infrastructureRevision)
        require(core.physicsEpoch == basic.physicsEpoch)
        return RuntimeFileV1(
            formatVersion = CURRENT_FORMAT_VERSION,
            infrastructureRevision = core.infrastructureRevision,
            physicsEpoch = core.physicsEpoch,
            coreSnapshotJson = WorldSnapshotCodec.encodeRuntime(core),
            basicSnapshotJson = BasicRuntimeSnapshotCodec.encode(basic)
        )
    }

    fun encode(value: InfrastructureFileV1): String =
        json.encodeToString(InfrastructureFileV1.serializer(), value)
    fun encode(value: RuntimeFileV1): String =
        json.encodeToString(RuntimeFileV1.serializer(), value)
    fun decodeInfrastructure(value: String): InfrastructureFileV1 =
        json.decodeFromString(InfrastructureFileV1.serializer(), value).also {
            require(it.formatVersion == CURRENT_FORMAT_VERSION) {
                "Unsupported infrastructure file version: ${it.formatVersion}"
            }
        }
    fun decodeRuntime(value: String): RuntimeFileV1 =
        json.decodeFromString(RuntimeFileV1.serializer(), value).also {
            require(it.formatVersion == CURRENT_FORMAT_VERSION) {
                "Unsupported runtime file version: ${it.formatVersion}"
            }
        }
}
