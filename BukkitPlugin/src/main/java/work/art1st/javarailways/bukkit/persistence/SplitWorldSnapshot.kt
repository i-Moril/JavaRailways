package work.art1st.javarailways.bukkit.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotCodec
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotV3
import work.art1st.javarailways.basic.persistence.BasicRuntimeSnapshotCodec
import work.art1st.javarailways.basic.persistence.BasicRuntimeSnapshotV3
import work.art1st.javarailways.core.persistence.WorldSnapshotCodec
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.simpleato.persistence.SimpleATORuntimeSnapshotCodec
import work.art1st.javarailways.simpleato.persistence.SimpleATORuntimeSnapshotV1
import work.art1st.javarailways.simpleato.persistence.SimpleATOInfrastructureSnapshotCodec
import work.art1st.javarailways.simpleato.persistence.SimpleATOInfrastructureSnapshotV2

@Serializable
data class InfrastructureFileV2(
    val formatVersion: Int,
    val infrastructureRevision: Long,
    val coreSnapshotJson: String,
    val basicSnapshotJson: String,
    val simpleAtoSnapshotJson: String
) {
    fun core(): CoreWorld.SavedInfrastructureV2 =
        WorldSnapshotCodec.decodeInfrastructure(coreSnapshotJson).also {
            require(it.infrastructureRevision == infrastructureRevision)
        }
    fun basic(): BasicInfrastructureSnapshotV3 =
        BasicInfrastructureSnapshotCodec.decode(basicSnapshotJson).also {
            require(it.infrastructureRevision == infrastructureRevision)
        }
    fun simpleAto(): SimpleATOInfrastructureSnapshotV2 =
        SimpleATOInfrastructureSnapshotCodec.decode(simpleAtoSnapshotJson).also {
            require(it.infrastructureRevision == infrastructureRevision)
        }
}

@Serializable
data class RuntimeFileV2(
    val formatVersion: Int,
    val infrastructureRevision: Long,
    val physicsEpoch: Long,
    val coreSnapshotJson: String,
    val basicSnapshotJson: String,
    val simpleAtoSnapshotJson: String
) {
    fun core(): CoreWorld.SavedRuntimeV1 = WorldSnapshotCodec.decodeRuntime(coreSnapshotJson).also {
        require(it.infrastructureRevision == infrastructureRevision && it.physicsEpoch == physicsEpoch)
    }
    fun basic(): BasicRuntimeSnapshotV3 = BasicRuntimeSnapshotCodec.decode(basicSnapshotJson).also {
        require(it.infrastructureRevision == infrastructureRevision && it.physicsEpoch == physicsEpoch)
    }
    fun simpleAto(): SimpleATORuntimeSnapshotV1 =
        SimpleATORuntimeSnapshotCodec.decode(simpleAtoSnapshotJson).also {
            require(it.infrastructureRevision == infrastructureRevision && it.physicsEpoch == physicsEpoch)
        }
}

data class CapturedWorldSnapshots(
    val infrastructure: InfrastructureFileV2,
    val runtime: RuntimeFileV2
) {
    init {
        require(infrastructure.infrastructureRevision == runtime.infrastructureRevision)
    }
}

data class WorldSnapshotCapture(
    val coreInfrastructure: CoreWorld.SavedInfrastructureV2,
    val basicInfrastructure: BasicInfrastructureSnapshotV3,
    val simpleAtoInfrastructure: SimpleATOInfrastructureSnapshotV2,
    val coreRuntime: CoreWorld.SavedRuntimeV1,
    val basicRuntime: BasicRuntimeSnapshotV3,
    val simpleAtoRuntime: SimpleATORuntimeSnapshotV1
) {
    init {
        val revision = coreInfrastructure.infrastructureRevision
        require(basicInfrastructure.infrastructureRevision == revision)
        require(simpleAtoInfrastructure.infrastructureRevision == revision)
        require(coreRuntime.infrastructureRevision == revision)
        require(basicRuntime.infrastructureRevision == revision)
        require(simpleAtoRuntime.infrastructureRevision == revision)
        require(coreRuntime.physicsEpoch == basicRuntime.physicsEpoch)
        require(coreRuntime.physicsEpoch == simpleAtoRuntime.physicsEpoch)
    }

    fun encode(): CapturedWorldSnapshots = CapturedWorldSnapshots(
        SplitWorldSnapshotCodec.infrastructure(
            coreInfrastructure,
            basicInfrastructure,
            simpleAtoInfrastructure
        ),
        SplitWorldSnapshotCodec.runtime(coreRuntime, basicRuntime, simpleAtoRuntime)
    )
}

object SplitWorldSnapshotCodec {
    const val CURRENT_INFRASTRUCTURE_FORMAT_VERSION = 2
    const val CURRENT_RUNTIME_FORMAT_VERSION = 2
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; prettyPrint = true }

    fun infrastructure(
        core: CoreWorld.SavedInfrastructureV2,
        basic: BasicInfrastructureSnapshotV3,
        simpleAto: SimpleATOInfrastructureSnapshotV2
    ): InfrastructureFileV2 {
        require(core.infrastructureRevision == basic.infrastructureRevision)
        require(core.infrastructureRevision == simpleAto.infrastructureRevision)
        return InfrastructureFileV2(
            formatVersion = CURRENT_INFRASTRUCTURE_FORMAT_VERSION,
            infrastructureRevision = core.infrastructureRevision,
            coreSnapshotJson = WorldSnapshotCodec.encodeInfrastructure(core),
            basicSnapshotJson = BasicInfrastructureSnapshotCodec.encode(basic),
            simpleAtoSnapshotJson = SimpleATOInfrastructureSnapshotCodec.encode(simpleAto)
        )
    }

    fun runtime(
        core: CoreWorld.SavedRuntimeV1,
        basic: BasicRuntimeSnapshotV3,
        simpleAto: SimpleATORuntimeSnapshotV1
    ): RuntimeFileV2 {
        require(core.infrastructureRevision == basic.infrastructureRevision)
        require(core.infrastructureRevision == simpleAto.infrastructureRevision)
        require(core.physicsEpoch == basic.physicsEpoch)
        require(core.physicsEpoch == simpleAto.physicsEpoch)
        return RuntimeFileV2(
            formatVersion = CURRENT_RUNTIME_FORMAT_VERSION,
            infrastructureRevision = core.infrastructureRevision,
            physicsEpoch = core.physicsEpoch,
            coreSnapshotJson = WorldSnapshotCodec.encodeRuntime(core),
            basicSnapshotJson = BasicRuntimeSnapshotCodec.encode(basic),
            simpleAtoSnapshotJson = SimpleATORuntimeSnapshotCodec.encode(simpleAto)
        )
    }

    fun encode(value: InfrastructureFileV2): String =
        json.encodeToString(InfrastructureFileV2.serializer(), value)
    fun encode(value: RuntimeFileV2): String =
        json.encodeToString(RuntimeFileV2.serializer(), value)
    fun decodeInfrastructure(value: String): InfrastructureFileV2 =
        json.decodeFromString(InfrastructureFileV2.serializer(), value).also {
            require(it.formatVersion == CURRENT_INFRASTRUCTURE_FORMAT_VERSION) {
                "Unsupported infrastructure file version: ${it.formatVersion}"
            }
        }
    fun decodeRuntime(value: String): RuntimeFileV2 =
        json.decodeFromString(RuntimeFileV2.serializer(), value).also {
            require(it.formatVersion == CURRENT_RUNTIME_FORMAT_VERSION) {
                "Unsupported runtime file version: ${it.formatVersion}"
            }
        }
}
