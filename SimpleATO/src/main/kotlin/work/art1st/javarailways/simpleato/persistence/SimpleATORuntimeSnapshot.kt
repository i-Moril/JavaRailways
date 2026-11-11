package work.art1st.javarailways.simpleato.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.simpleato.api.depot.FixedEmuUnit
import work.art1st.javarailways.simpleato.api.depot.FixedFormationChange
import work.art1st.javarailways.simpleato.api.depot.FixedFormationChangeKind
import work.art1st.javarailways.simpleato.api.depot.FixedFormationOwner
import work.art1st.javarailways.simpleato.api.depot.FixedFormationOutput
import work.art1st.javarailways.simpleato.api.depot.FixedFormationPhase
import work.art1st.javarailways.simpleato.api.depot.FixedFormationHeldOutput
import work.art1st.javarailways.simpleato.api.depot.FixedFormationDisposition
import work.art1st.javarailways.simpleato.api.depot.FixedEmuUnitAssignment
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
    val modelPackageId: String?,
    val tags: Set<String>,
    val carCount: Int,
    val lengthMeters: Double,
    val maximumSpeedMetersPerSecond: Double,
    val capabilities: Set<String>,
    val state: String,
    val stablingPointId: String? = null,
    val assignmentId: String? = null,
    val stablingDepotId: String = homeDepotId
) {
    fun restore(): RollingStockSnapshot = RollingStockSnapshot(
        record = RollingStockRecord(
            trainId, DepotId(homeDepotId), modelPackageId, tags, carCount,
            lengthMeters, maximumSpeedMetersPerSecond, capabilities
        ),
        state = RollingStockState.valueOf(state),
        stablingPointRef = stablingPointId?.let { StablingPointRef(DepotId(stablingDepotId), it) },
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
            assignmentId = value.assignmentId,
            stablingDepotId = value.stablingPointRef?.depotId?.value ?: value.record.homeDepotId.value
        )
    }
}

@Serializable
data class SavedFixedEmuUnit(
    val id: String,
    val homeDepotId: String,
    val carIds: List<String>,
    val controlCompatibilityKey: String
) {
    fun restore() = FixedEmuUnit(id, DepotId(homeDepotId), carIds, controlCompatibilityKey)
    companion object {
        fun capture(unit: FixedEmuUnit) = SavedFixedEmuUnit(unit.id, unit.homeDepotId.value, unit.carIds, unit.controlCompatibilityKey)
    }
}

@Serializable
data class SavedFixedEmuUnitAssignment(val unitId: String, val trainId: String, val assignmentId: String) {
    fun restore() = FixedEmuUnitAssignment(unitId, trainId, assignmentId)
    companion object {
        fun capture(claim: FixedEmuUnitAssignment) = SavedFixedEmuUnitAssignment(claim.unitId, claim.trainId, claim.assignmentId)
    }
}

@Serializable
data class SavedFixedFormationOwner(val trainId: String, val assignmentId: String, val carIds: List<String>, val reversed: Boolean = false) {
    fun restore() = FixedFormationOwner(trainId, assignmentId, carIds, reversed)
    companion object {
        fun capture(owner: FixedFormationOwner) = SavedFixedFormationOwner(owner.trainId, owner.assignmentId, owner.carIds, owner.reversed)
    }
}

/** Flat owner fields preserve the existing held-output shape; omitted disposition means HOLD. */
@Serializable
data class SavedFixedFormationHeldOutput(val trainId: String, val assignmentId: String, val carIds: List<String>,
    val reversed: Boolean = false, val disposition: String = FixedFormationDisposition.HOLD.name) {
    fun restore() = FixedFormationHeldOutput(FixedFormationOwner(trainId, assignmentId, carIds, reversed),
        FixedFormationDisposition.valueOf(disposition))
    companion object {
        fun capture(held: FixedFormationHeldOutput) = SavedFixedFormationHeldOutput(held.owner.trainId,
            held.owner.assignmentId, held.owner.carIds, held.owner.reversed, held.disposition.name)
    }
}

@Serializable
data class SavedFixedFormationOutput(val owner: SavedFixedFormationOwner, val disposition: String) {
    fun restore() = FixedFormationOutput(owner.restore(), FixedFormationDisposition.valueOf(disposition))
    companion object {
        fun capture(output: FixedFormationOutput) = SavedFixedFormationOutput(SavedFixedFormationOwner.capture(output.owner), output.disposition.name)
    }
}

@Serializable
data class SavedFixedFormationChange(
    val eventId: String,
    val kind: String,
    val sources: List<SavedFixedFormationOwner>,
    val outputs: List<SavedFixedFormationOutput>,
    val phase: String = FixedFormationPhase.SOURCES_HELD.name
) {
    fun restore() = FixedFormationChange(eventId, FixedFormationChangeKind.valueOf(kind), sources.map { it.restore() }, outputs.map { it.restore() }, FixedFormationPhase.valueOf(phase))
    companion object {
        fun capture(change: FixedFormationChange) = SavedFixedFormationChange(change.eventId, change.kind.name,
            change.sources.map(SavedFixedFormationOwner::capture), change.outputs.map(SavedFixedFormationOutput::capture), change.phase.name)
    }
}

@Serializable
data class SimpleATORuntimeSnapshotV1(
    val formatVersion: Int,
    val infrastructureRevision: Long,
    val physicsEpoch: Long,
    val dispatcher: SavedDispatcherV1,
    val timetables: List<SavedTimetableV1> = emptyList(),
    val rollingStock: List<SavedRollingStockV1> = emptyList(),
    val fixedUnits: List<SavedFixedEmuUnit> = emptyList(),
    val fixedUnitAssignments: List<SavedFixedEmuUnitAssignment> = emptyList(),
    val unresolvedFormationChanges: List<SavedFixedFormationChange> = emptyList(),
    val heldFormationOutputs: List<SavedFixedFormationHeldOutput> = emptyList()
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
        runtime.depots.restoreOperationalLedger(
            restoredRollingStock,
            fixedUnits.map(SavedFixedEmuUnit::restore),
            fixedUnitAssignments.map(SavedFixedEmuUnitAssignment::restore),
            world.allTrains.associate { train -> train.id to train.cars.map { it.id } },
            unresolvedFormationChanges.map(SavedFixedFormationChange::restore),
            world.allTrains.associate { it.id to it.isReversed },
            heldFormationOutputs.map { it.restore() }
        )
        runtime.restoreFormationRecoveryMovementHolds(world)
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
                    .map(SavedRollingStockV1::capture),
                fixedUnits = world.simpleAtoWorldData.depots.allFixedUnits().map(SavedFixedEmuUnit::capture),
                fixedUnitAssignments = world.simpleAtoWorldData.depots.allFixedUnitAssignments().map(SavedFixedEmuUnitAssignment::capture),
                unresolvedFormationChanges = world.simpleAtoWorldData.depots.unresolvedFormationChanges().map(SavedFixedFormationChange::capture),
                heldFormationOutputs = world.simpleAtoWorldData.depots.heldFormationOutputs().map { SavedFixedFormationHeldOutput.capture(it) }
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
