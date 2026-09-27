package work.art1st.javarailways.simpleato

import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.util.plugin.ExtensionData
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionRegistry
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotStore
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simpleato.timetable.TimetableDispatcher
import work.art1st.javarailways.simpleato.timetable.TimetableStore

/** Mutable operational runtime owned by SimpleATO for one Core world. */
data class SimpleATOWorldData(
    val dispatcher: TimetableDispatcher,
    val signalEpochRuntime: SignalEpochRuntime,
    val signalRestrictions: TrainRestrictionSnapshotStore,
    val inspectionRuntime: InspectionRuntime,
    val timetables: TimetableStore = TimetableStore(),
    val timetableExtensions: TimetableExtensionRegistry = TimetableExtensionRegistry()
)

@OptIn(ExtensionData::class)
val World.simpleAtoWorldDataOrNull: SimpleATOWorldData?
    get() {
        val plugin = SimpleATOPlugin.instanceOrNull() ?: return null
        return getExtensionDataOrNull(plugin)
    }

@OptIn(ExtensionData::class)
val World.simpleAtoWorldData: SimpleATOWorldData
    get() {
        val plugin = SimpleATOPlugin.instanceOrNull()
            ?: throw IllegalStateException(
                "SimpleATOPlugin.INSTANCE is not initialized; register SimpleATO before creating worlds"
            )
        return getExtensionDataOrNull(plugin)
            ?: throw IllegalStateException("SimpleATOWorldData is not registered on this world")
    }
