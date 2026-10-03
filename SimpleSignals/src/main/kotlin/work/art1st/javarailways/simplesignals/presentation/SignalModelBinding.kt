package work.art1st.javarailways.simplesignals.presentation

import org.bukkit.plugin.Plugin
import work.art1st.javarailways.bukkit.api.model.ModelHandle
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.bukkit.api.model.ModelPresentation
import work.art1st.javarailways.bukkit.api.model.ModelPresentationService
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
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
    internal val signals: Map<String, List<Device>>,
    internal val indicators: Map<String, Device>,
    private val topology: TopologyState
) {
    internal class Device(val objectId: String, val model: LoadedPackageModel,
        val configuration: DeviceModelDefinition, val pose: ModelPose)

    companion object {
        fun prepare(definitions: SimpleSignalsWorldDefinitions, models: Map<String, LoadedPackageModel>,
            rails: RailTargetService, topology: TopologyState,
            previous: PreparedSignalModels? = null): PreparedSignalModels {
            val reusable = previous?.takeIf { it.topology === topology }
            val previousDevices = reusable?.let {
                (it.signals.values.flatten() + it.indicators.values).associateBy(Device::objectId)
            }.orEmpty()
            fun device(kind: String, id: String, configuration: DeviceModelDefinition,
                variants: Collection<String>): Device {
                try {
                    val model = models[configuration.modelId]
                        ?: throw IllegalArgumentException("model '${configuration.modelId}' is missing")
                    variants.forEach(model::variant)
                    val cached = previousDevices["$kind/$id"]?.takeIf { it.configuration == configuration }
                    val pose = cached?.pose ?: when (val placement = configuration.placement) {
                        is ModelPlacement.Ground -> ModelPlacementCalculator.calculate(placement)
                        is ModelPlacement.RailAttached -> ModelPlacementCalculator.calculate(placement,
                            rails.findCurve(definitions.worldId, placement.position.railId).orElse(null))
                    }
                    return Device("$kind/$id", model, configuration, pose)
                } catch (failure: Exception) {
                    throw IllegalArgumentException("$kind $id: ${failure.message ?: failure.javaClass.simpleName}", failure)
                }
            }
            val signals = linkedMapOf<String, MutableList<Device>>()
            val variantsBySignal = linkedMapOf<String, List<String>>()
            definitions.fixedBlockSignals.forEach { definition ->
                val variants = listOf(definition.occupiedOutput.modelVariant) + definition.clearLevels.map { it.output.modelVariant }
                variantsBySignal[definition.signalId] = variants
                definition.model?.let { model ->
                    signals.getOrPut(definition.signalId, ::mutableListOf) +=
                        device("fixed-block-signal", definition.signalId, model, variants)
                }
            }
            definitions.protectionSignals.forEach { definition ->
                val variants = listOf(definition.unavailableOutput.modelVariant, definition.occupiedOutput.modelVariant) +
                    definition.clearLevels.map { it.output.modelVariant }
                variantsBySignal[definition.signalId] = variants
                definition.model?.let { model ->
                    signals.getOrPut(definition.signalId, ::mutableListOf) +=
                        device("protection-signal", definition.signalId, model, variants)
                }
            }
            definitions.attachedSignals.forEach { definition ->
                val variants = requireNotNull(variantsBySignal[definition.primarySignalId]) {
                    "attached signal ${definition.signalId} binds unknown primary signal ${definition.primarySignalId}"
                }
                signals.getOrPut(definition.primarySignalId, ::mutableListOf) +=
                    device("attached-signal", definition.signalId, definition.model, variants)
            }
            val indicators = linkedMapOf<String, Device>()
            definitions.routeIndicators.forEach { definition -> definition.model?.let { model ->
                indicators[definition.indicatorId] = device("route-indicator", definition.indicatorId, model,
                    listOf(definition.noRouteModelVariant) + definition.modelVariantsByRouteId.values)
            } }
            return PreparedSignalModels(java.util.Map.copyOf(signals.mapValues { java.util.List.copyOf(it.value) }),
                java.util.Map.copyOf(indicators), topology)
        }
    }
}

/** Handle state belongs to one world load, independently of definition publications. */
internal class SignalModelHandles {
    internal class Display(val device: PreparedSignalModels.Device, val handle: ModelHandle) {
        var scenes: Map<String, String>? = null
        var pose: ModelPose? = null
        var revision = 0L
    }

    internal val submissionLock = Any()
    internal var active: SignalModelBinding? = null
    internal var allocated: Map<String, Display> = emptyMap()

    /** Fences an already-running old submit before the bounded publication swap. */
    fun publish(binding: SignalModelBinding?, publication: () -> Unit) = synchronized(submissionLock) {
        active?.retire()
        active = binding
        publication()
    }

    fun retire() = synchronized(submissionLock) {
        active?.retire()
        active = null
    }

    /** Only the serialized world lifecycle worker closes the actual allocated collection. */
    fun close(reportFailure: (Throwable) -> Unit) {
        val removed = synchronized(submissionLock) {
            retire()
            allocated.also { allocated = emptyMap() }
        }
        removed.values.forEach {
            try { it.handle.close() } catch (failure: Exception) { reportFailure(failure) }
        }
    }
}

/** Immutable publication selections/poses; old callbacks cannot use retained world handles. */
internal class SignalModelBinding(
    private val plugin: Plugin,
    private val worldId: UUID,
    private val topologyRevision: Long,
    internal val prepared: PreparedSignalModels,
    private val service: ModelPresentationService,
    private val reportFailure: (String, Throwable) -> Unit,
    internal val handles: SignalModelHandles
) {
    private var lastEpoch = -1L
    private var initialized = false
    @Volatile private var ready = false
    @Volatile private var retired = false

    fun retire() = synchronized(handles.submissionLock) { retired = true }

    fun publish(publication: () -> Unit) = handles.publish(this, publication)

    /** Reconcile actual allocations; caller serializes this outside publication locks. */
    fun start(current: () -> Boolean, snapshot: () -> SectionOccupancyRuntime.PublishedState?) {
        if (retired || ready || !current()) return
        val devices = (prepared.signals.values.flatten() + prepared.indicators.values).associateBy { it.objectId }
        val removed = handles.allocated.values.filter { display ->
            val wanted = devices[display.device.objectId]
            wanted == null || wanted.model.id != display.device.model.id ||
                wanted.model.definition != display.device.model.definition
        }
        val removedIds = removed.mapTo(hashSetOf()) { it.device.objectId }
        val retained = handles.allocated.filterKeys { it !in removedIds }
        synchronized(handles.submissionLock) {
            if (retired || !current() || handles.active !== this || ready) return
            handles.allocated = retained
        }
        closeHandles(removed.map { it.handle })
        val created = mutableListOf<ModelHandle>()
        try {
            val additions = linkedMapOf<String, SignalModelHandles.Display>()
            for ((id, device) in devices) {
                if (retired || !current()) break
                if (id !in handles.allocated) {
                    val handle = service.create(plugin, worldId, id, device.model.definition)
                    created += handle
                    additions[id] = SignalModelHandles.Display(device, handle)
                }
            }
            val candidate = retained + additions
            val installed = synchronized(handles.submissionLock) {
                if (retired || !current() || handles.active !== this) false else {
                    handles.allocated = candidate
                    ready = true
                    true
                }
            }
            if (!installed) {
                closeHandles(created)
                return
            }
            // Attach precedes allocation. Latest complete state covers callbacks skipped before ready.
            consume(snapshot(), prepared.signals.keys, prepared.indicators.keys)
        } catch (failure: Exception) {
            val superseded = retired || !current()
            // This also covers a catch-up snapshot failure after installation. Never leave
            // closed additions in the shared collection, or discard unrelated retained handles.
            synchronized(handles.submissionLock) { handles.allocated = retained }
            closeHandles(created)
            if (!superseded) {
                synchronized(handles.submissionLock) { if (handles.active === this) ready = true }
                consume(snapshot(), prepared.signals.keys, prepared.indicators.keys)
                report("SimpleSignals rendering unavailable for world $worldId", failure)
            }
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
        val failures = mutableListOf<Pair<String, Throwable>>()
        synchronized(handles.submissionLock) {
            if (!ready || retired || handles.active !== this) return
            val stamp = state?.occupancy?.stamp
            if (stamp != null) {
                if (stamp.worldId != worldId.toString() || stamp.topologyRevision != topologyRevision ||
                    stamp.physicsEpoch <= lastEpoch) return
                lastEpoch = stamp.physicsEpoch
            } else if (lastEpoch >= 0) return
            // A partial live change can win the race with initial catch-up. Its complete state
            // must initialize unchanged devices too, once; later publications stay changed-only.
            val signalIds = if (initialized) changedSignals else prepared.signals.keys
            val indicatorIds = if (initialized) changedIndicators else prepared.indicators.keys
            initialized = true
            fun submit(device: PreparedSignalModels.Device, variant: String?) {
                if (retired) return
                val display = handles.allocated[device.objectId] ?: return
                try {
                    val scenes = variant?.let(device.model::variant) ?: device.model.defaultScenes
                    if (scenes == display.scenes && device.pose == display.pose) return
                    display.handle.submit(ModelPresentation(display.revision++, device.pose, scenes))
                    display.scenes = scenes
                    display.pose = device.pose
                } catch (failure: Exception) {
                    failures += "SimpleSignals display ${device.objectId} in world $worldId failed" to failure
                }
            }
            signalIds.forEach { id -> prepared.signals[id]?.let { devices ->
                val variant = (state?.signals?.states?.get(id) as? SectionSignalState.Available)?.output?.modelVariant
                devices.forEach { submit(it, variant) }
            } }
            indicatorIds.forEach { id -> prepared.indicators[id]?.let { device ->
                submit(device, state?.indicators?.get(id)?.modelVariant)
            } }
        }
        failures.forEach { (context, failure) -> report(context, failure) }
    }

    private fun closeHandles(handles: Collection<ModelHandle>) {
        handles.forEach { handle ->
            try { handle.close() } catch (failure: Exception) {
                report("SimpleSignals model cleanup failed in world $worldId", failure)
            }
        }
    }

    fun cleanupFailure(failure: Throwable) = report("SimpleSignals model cleanup failed in world $worldId", failure)

    private fun report(context: String, failure: Throwable) {
        // A reporting/scheduler failure is still a display failure, never a safety callback failure.
        try { reportFailure(context, failure) } catch (_: Exception) { }
    }
}
