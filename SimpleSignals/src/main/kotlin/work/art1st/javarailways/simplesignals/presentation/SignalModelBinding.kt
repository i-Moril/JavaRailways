package work.art1st.javarailways.simplesignals.presentation

import org.bukkit.plugin.Plugin
import work.art1st.javarailways.bukkit.api.model.ModelHandle
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.bukkit.api.model.ModelPresentation
import work.art1st.javarailways.bukkit.api.model.ModelPresentationService
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.runtime.SectionOccupancyRuntime
import work.art1st.javarailways.simplesignals.runtime.SectionSignalState
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** One enable-time worker preparation. A failed package never produces a partial model set. */
internal object SimpleSignalsModelPackages {
    fun load(directory: Path): Map<String, LoadedPackageModel> {
        if (!Files.exists(directory)) return emptyMap()
        require(Files.isDirectory(directory)) { "SimpleSignals model path is not a directory: $directory" }
        val models = linkedMapOf<String, LoadedPackageModel>()
        val names = hashSetOf<String>()
        Files.newDirectoryStream(directory).use { entries ->
            entries.filter { Files.isDirectory(it) }.sortedBy { it.fileName.toString() }.forEach { packageDirectory ->
                val loaded = ModelPackageLoader.load(packageDirectory)
                require(names.add(loaded.name)) { "Duplicate SimpleSignals model package '${loaded.name}': $packageDirectory" }
                loaded.models.forEach { (id, model) ->
                    require(models.putIfAbsent(id, model) == null) { "Duplicate SimpleSignals model ID '$id': $packageDirectory" }
                }
            }
        }
        return java.util.Map.copyOf(models)
    }
}

/** Pure resolved values. Validation allocates no handles and submits no display state. */
internal class PreparedSignalModels private constructor(
    internal val signals: Map<String, Device>,
    internal val indicators: Map<String, Device>
) {
    internal class Device(val objectId: String, val model: LoadedPackageModel, val pose: ModelPose)

    companion object {
        fun prepare(definitions: SimpleSignalsWorldDefinitions, models: Map<String, LoadedPackageModel>,
            rails: RailTargetService): PreparedSignalModels {
            fun device(kind: String, id: String, configuration: DeviceModelDefinition,
                variants: Collection<String>): Device {
                try {
                    val model = models[configuration.modelId]
                        ?: throw IllegalArgumentException("model '${configuration.modelId}' is missing")
                    variants.forEach(model::variant)
                    val pose = when (val placement = configuration.placement) {
                        is ModelPlacement.Ground -> ModelPlacementCalculator.calculate(placement)
                        is ModelPlacement.RailAttached -> ModelPlacementCalculator.calculate(placement,
                            rails.findCurve(definitions.worldId, placement.position.railId).orElse(null))
                    }
                    return Device("$kind/$id", model, pose)
                } catch (failure: Exception) {
                    throw IllegalArgumentException("$kind $id: ${failure.message ?: failure.javaClass.simpleName}", failure)
                }
            }
            val signals = linkedMapOf<String, Device>()
            definitions.fixedBlockSignals.forEach { definition -> definition.model?.let { model ->
                signals[definition.signalId] = device("fixed-block-signal", definition.signalId, model,
                    listOf(definition.occupiedOutput.modelVariant) + definition.clearLevels.map { it.output.modelVariant })
            } }
            definitions.protectionSignals.forEach { definition -> definition.model?.let { model ->
                signals[definition.signalId] = device("protection-signal", definition.signalId, model,
                    listOf(definition.unavailableOutput.modelVariant, definition.occupiedOutput.modelVariant) +
                        definition.clearLevels.map { it.output.modelVariant })
            } }
            val indicators = linkedMapOf<String, Device>()
            definitions.routeIndicators.forEach { definition -> definition.model?.let { model ->
                indicators[definition.indicatorId] = device("route-indicator", definition.indicatorId, model,
                    listOf(definition.noRouteModelVariant) + definition.modelVariantsByRouteId.values)
            } }
            return PreparedSignalModels(java.util.Map.copyOf(signals), java.util.Map.copyOf(indicators))
        }
    }
}

/**
 * One definition publication's display objects. Old callbacks retain this exact binding.
 * Retirement is a bounded flag change; allocation/cleanup belongs to the world lifecycle worker.
 */
internal class SignalModelBinding(
    private val plugin: Plugin,
    private val worldId: UUID,
    private val topologyRevision: Long,
    private val prepared: PreparedSignalModels,
    private val service: ModelPresentationService,
    private val reportFailure: (String, Throwable) -> Unit
) {
    private class Display(val device: PreparedSignalModels.Device, val handle: ModelHandle) {
        var scenes: Map<String, String>? = null
        var revision = 0L
    }

    private val submissionLock = Any()
    private var signals: Map<String, Display> = emptyMap()
    private var indicators: Map<String, Display> = emptyMap()
    private var lastEpoch = -1L
    private var initialized = false
    @Volatile private var ready = false
    @Volatile private var retired = false

    fun retire() { retired = true }

    /** Caller serializes close/create outside publication locks. No entity work is done here. */
    fun start(current: () -> Boolean, snapshot: () -> SectionOccupancyRuntime.PublishedState?) {
        if (retired || !current()) return
        val created = mutableListOf<ModelHandle>()
        try {
            fun create(devices: Map<String, PreparedSignalModels.Device>): Map<String, Display> {
                val displays = linkedMapOf<String, Display>()
                for ((id, device) in devices) {
                    if (retired || !current()) {
                        retire()
                        return emptyMap()
                    }
                    val handle = service.create(plugin, worldId, device.objectId, device.model.definition)
                    created += handle
                    displays[id] = Display(device, handle)
                }
                return displays
            }
            signals = create(prepared.signals)
            indicators = create(prepared.indicators)
            if (retired || !current()) {
                retire()
                closeHandles(created)
                return
            }
            ready = true
            // Attach precedes allocation. Latest complete state covers callbacks skipped before ready.
            consume(snapshot(), signals.keys, indicators.keys)
        } catch (failure: Exception) {
            val superseded = retired || !current()
            retire()
            closeHandles(created)
            if (!superseded) report("SimpleSignals rendering unavailable for world $worldId", failure)
        }
    }

    /** Only bounded lookups, comparison and public handle.submit; never I/O, Bukkit or logging. */
    fun published(state: SectionOccupancyRuntime.PublishedState,
        changedSignals: Collection<String>, changedIndicators: Collection<String>) {
        consume(state, changedSignals, changedIndicators)
    }

    private fun consume(state: SectionOccupancyRuntime.PublishedState?,
        changedSignals: Collection<String>, changedIndicators: Collection<String>) {
        if (!ready || retired) return
        synchronized(submissionLock) {
            if (!ready || retired) return
            val stamp = state?.occupancy?.stamp
            if (stamp != null) {
                if (stamp.worldId != worldId.toString() || stamp.topologyRevision != topologyRevision ||
                    stamp.physicsEpoch <= lastEpoch) return
                lastEpoch = stamp.physicsEpoch
            } else if (lastEpoch >= 0) return
            // A partial live change can win the race with initial catch-up. Its complete state
            // must initialize unchanged devices too, once; later publications stay changed-only.
            val signalIds = if (initialized) changedSignals else signals.keys
            val indicatorIds = if (initialized) changedIndicators else indicators.keys
            initialized = true
            fun submit(display: Display, variant: String?) {
                if (retired) return
                try {
                    val scenes = variant?.let(display.device.model::variant) ?: display.device.model.defaultScenes
                    if (scenes == display.scenes) return
                    display.handle.submit(ModelPresentation(display.revision++, display.device.pose, scenes))
                    display.scenes = scenes
                } catch (failure: Exception) {
                    report("SimpleSignals display ${display.device.objectId} in world $worldId failed", failure)
                }
            }
            signalIds.forEach { id -> signals[id]?.let { display ->
                val variant = (state?.signals?.states?.get(id) as? SectionSignalState.Available)?.output?.modelVariant
                submit(display, variant)
            } }
            indicatorIds.forEach { id -> indicators[id]?.let { display ->
                submit(display, state?.indicators?.get(id)?.modelVariant)
            } }
        }
    }

    fun close() {
        retire()
        closeHandles(signals.values.map { it.handle } + indicators.values.map { it.handle })
    }

    private fun closeHandles(handles: Collection<ModelHandle>) {
        handles.forEach { handle ->
            try { handle.close() } catch (failure: Exception) {
                report("SimpleSignals model cleanup failed in world $worldId", failure)
            }
        }
    }

    private fun report(context: String, failure: Throwable) {
        // A reporting/scheduler failure is still a display failure, never a safety callback failure.
        try { reportFailure(context, failure) } catch (_: Exception) { }
    }
}
