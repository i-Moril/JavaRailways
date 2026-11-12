package work.art1st.javarailways.bukkit.extension.train

import java.util.concurrent.CompletionStage
import java.util.concurrent.CompletableFuture
import work.art1st.javarailways.core.api.util.Destruction
import work.art1st.javarailways.core.api.util.DestructionSteps


import org.bukkit.Location
import org.bukkit.entity.Player
import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Vector3d
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.config.TrainModelUpdatePolicy
import work.art1st.javarailways.bukkit.debug.TrainDebugLog
import work.art1st.javarailways.bukkit.persistence.CarModelSnapshotStore
import work.art1st.javarailways.bukkit.render.model.ModelConstruction
import work.art1st.javarailways.bukkit.render.model.CompactTransform
import work.art1st.javarailways.bukkit.render.model.CompositeModel
import work.art1st.javarailways.bukkit.render.model.PlatformModel
import work.art1st.javarailways.bukkit.render.model.SeatModel
import work.art1st.javarailways.bukkit.render.chunk.railViewDistanceSettings
import work.art1st.javarailways.core.api.physics.train.Car
import work.art1st.javarailways.core.api.physics.train.CarFrameSnapshot
import work.art1st.javarailways.core.api.render.RenderableFrameSnapshot
import work.art1st.javarailways.core.api.util.plugin.SerializableExtensionData
import work.art1st.javarailways.bukkit.extension.bukkitWorldData
import work.art1st.javarailways.core.physics.train.CoreCar
import work.art1st.javarailways.bukkit.extension.renderChunkManager
import work.art1st.javarailways.bukkit.util.BukkitThreadOwnership
import work.art1st.javarailways.bukkit.behavior.CarBehavior
import work.art1st.javarailways.bukkit.behavior.CapturedCarBehavior
import work.art1st.javarailways.bukkit.behavior.carBehaviorActions
import work.art1st.javarailways.bukkit.behavior.captureAnimationCapabilities
import work.art1st.javarailways.bukkit.behavior.captureAnimationParts
import work.art1st.javarailways.bukkit.behavior.supportsAnimation
import work.art1st.javarailways.bukkit.behavior.TrainBehaviorActionDefinition
import work.art1st.javarailways.bukkit.config.model.SavedTrainPackageSnapshot
import work.art1st.javarailways.bukkit.config.model.CarControlConfig
import work.art1st.javarailways.bukkit.config.model.SavedCarModelTree
import work.art1st.javarailways.bukkit.config.model.TrainResourceVersion
import work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueInput
import work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueKind
import work.art1st.javarailways.bukkit.persistence.TrainPackageSnapshotStore
import work.art1st.javarailways.basic.api.operation.DoorSide
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Car rendering extension: builds the car model from CarDefinition and
 * updates it each tick to follow the car's position and orientation.
 *
 * The car model name must be set via [carModelName] before the model is
 * built (i.e., before the first [sendTo] call).
 */
class CarExtension : TrainComponentExtension<Car>() {

    /** Fully qualified car model name, e.g. "s100/passenger". */
    var carModelName: String? = null

    private var pendingHeldScenes: Map<String, String> = emptyMap()
    private var savedModelSnapshotHash: String? = null
    private var savedPackageSnapshotHash: String? = null
    private var savedPackageSnapshot: SavedTrainPackageSnapshot? = null
    private var savedModelSnapshot: SavedCarModelTree? = null
    var resourceType: String? = null
        private set
    private var behaviorClosed = false
    private val behaviorClockDelegate = lazy {
        work.art1st.javarailways.bukkit.behavior.CarBehaviorClock(self.world.tickManager, this, ::behaviorLocation)
    }
    private val behaviorClock by behaviorClockDelegate
    private val playback by lazy {
        work.art1st.javarailways.bukkit.behavior.CarAnimationPlayback(
            { animationParts() },
            { active -> behaviorClock.register(active) },
            { pendingHeldScenes }, ::projectBehaviorFrame)
    }
    private val behavior by lazy {
        CarBehavior(::resolveBehavior, ::resolveActions,
            { name, scene, continueClosing -> ensureBehaviorOwned(); playback.play(name, scene, continueClosing) },
            { key, volume, pitch ->
                val location = behaviorLocation()
                BukkitThreadOwnership.ensureLocation(location, "CarExtension.sound")
                location.world!!.playSound(location, key, volume.toFloat(), pitch.toFloat())
            }, { delay, action -> behaviorClock.schedule(delay, action) },
            { BukkitPlugin.INSTANCE.logger.info("[CarBehavior:${self.id}] $it") }, ::stopTrainForScriptFailure, ::hasAnimation, playback::planDoorClose, { resolveModelSnapshot() to resolveActions() })
    }
    private var behaviorModelNodes: List<work.art1st.javarailways.bukkit.render.model.AbstractModel> = emptyList()
    private var bodyModel: CompositeModel? = null
    private var bogieModels: List<CompositeModel> = emptyList()
    private var platformModels: List<PlatformModel> = emptyList()
    private var seatModels: List<SeatModel> = emptyList()
    private var modelUpdateSequence: Long = 0L
    private var modelStateApplied: Boolean = false
    private var detailStateCurrent: Boolean = false
    private var forceNextModelUpdate: Boolean = false
    private val publishedFrameGate = CarFramePublicationGate()
    @Volatile private var latestFrame: CarFrameSnapshot? = null
    private var latestPartRenderSnapshot: TrainPartRenderSnapshot? = null
    private var latestPartRenderStream: UUID? = null
    @Volatile internal var hasDynamicExtensionModel: Boolean = false
        private set
    private var builtModelGeneration: Long = -1L
    private val detailAudience: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    override fun initialize(host: Car, savedExtensionData: Map<String, SerializableExtensionData>) {
        super.initialize(host, savedExtensionData)
        behaviorClock.start()
        val savedState = savedExtensionData[BukkitPlugin.EXTENSION_ID] as? SavedCarRenderState
        if (savedState != null) {
            carModelName = savedState.carModelName
            pendingHeldScenes = savedState.heldScenes
            savedModelSnapshotHash = savedState.modelSnapshotHash
            savedPackageSnapshotHash = savedState.packageSnapshotHash
            resourceType = savedState.resourceType
        }
    }

    override fun save(): Pair<String, SerializableExtensionData>? {
        val modelName = carModelName ?: return null
        return BukkitPlugin.EXTENSION_ID to SavedCarRenderState(
            carModelName = modelName,
            heldScenes = collectHeldScenes(),
            modelSnapshotHash = savedModelSnapshotHash,
            packageSnapshotHash = savedPackageSnapshotHash,
            resourceType = resourceType
        )
    }

    fun bindCreatedResources(type: String, modelName: String, version: TrainResourceVersion) {
        resourceType = type
        carModelName = modelName
        val model = requireNotNull(version.models[modelName]) { "Template '$type' has no model '$modelName'" }
        savedModelSnapshot = model
        savedModelSnapshotHash = model.hash
        val source = version.carPackages.getValue(modelName)
        savedPackageSnapshot = source
        savedPackageSnapshotHash = source.hash
    }

    /** Read declarations from this exact car's existing source package, without initializing behavior. */
    @Synchronized
    internal fun resolveCandidateFacts(): work.art1st.javarailways.simpleato.api.depot.TrainCandidateFacts? =
        operationalSnapshot()?.let(::candidateFactsFromSource)

    @Synchronized
    internal fun resolveCandidateFacts(
        policy: TrainModelUpdatePolicy,
        loadPackage: (String) -> SavedTrainPackageSnapshot?,
        liveVersion: (String) -> TrainResourceVersion
    ): work.art1st.javarailways.simpleato.api.depot.TrainCandidateFacts? =
        operationalSnapshot(policy, loadPackage, liveVersion)?.let(::candidateFactsFromSource)

    private fun candidateFactsFromSource(source: SavedTrainPackageSnapshot) =
        work.art1st.javarailways.simpleato.api.depot.TrainCandidateFacts(
            source.packageName, emptySet(), source.control.maxSpeed, emptySet())

    /** Read-only profile lookup; structure preparation must not pin or save source packages. */
    @Synchronized
    fun resolveCarControl(): CarControlConfig? {
        val modelName = carModelName ?: return null
        return requireNotNull(operationalSnapshot()?.carControls?.get(modelName)) {
            "Car '${self.id}' has no configuration for '$modelName'"
        }
    }

    @Synchronized
    internal fun resolveCarControl(
        policy: TrainModelUpdatePolicy,
        loadSnapshot: (String) -> SavedTrainPackageSnapshot?,
        liveControl: (String) -> CarControlConfig?
    ): CarControlConfig? {
        val modelName = carModelName ?: return null
        val hash = savedPackageSnapshotHash
        if (policy != TrainModelUpdatePolicy.SNAPSHOT) return liveControl(modelName)
        requireNotNull(hash) { "Car has no creation-time configuration snapshot" }
        val snapshot = savedPackageSnapshot ?: requireNotNull(loadSnapshot(hash)) {
            "Pinned car package snapshot '$hash' is unavailable"
        }
        return requireNotNull(snapshot.carControls[modelName]) {
            "Pinned car package snapshot '$hash' has no control profile for '$modelName'"
        }
    }

    @Synchronized
    fun resolveActions(): Map<String, work.art1st.javarailways.bukkit.config.model.TrainActionConfig> {
        return operationalSnapshot()?.actions.orEmpty()
    }

    /** Value-only inputs from this car's exact operational package and model policy. */
    @Synchronized
    internal fun captureSimulationBehavior(): CapturedCarBehavior = captureSimulationBehavior(
        BukkitPlugin.INSTANCE.javaRailwaysConfig.trainModelUpdatePolicy,
        TrainPackageSnapshotStore::load, CarModelSnapshotStore::load,
        BukkitPlugin.INSTANCE.modelManager::resourceVersion
    )

    @Synchronized
    internal fun captureSimulationBehavior(
        policy: TrainModelUpdatePolicy,
        loadPackage: (String) -> SavedTrainPackageSnapshot?,
        loadModel: (String) -> SavedCarModelTree?,
        liveVersion: (String) -> TrainResourceVersion
    ): CapturedCarBehavior {
        val liveResources by lazy { liveVersion(requireNotNull(resourceType)) }
        val source = requireNotNull(operationalSnapshot(policy, loadPackage) { liveResources }) {
            "Car '${self.id}' has no operational package"
        }
        val modelName = requireNotNull(carModelName)
        val model = if (policy == TrainModelUpdatePolicy.SNAPSHOT) {
            savedModelSnapshot ?: requireNotNull(loadModel(requireNotNull(savedModelSnapshotHash) {
                "Car '${self.id}' has no creation-time model snapshot"
            })) { "Pinned car model snapshot '$savedModelSnapshotHash' is unavailable" }
        } else {
            liveResources.models.getValue(modelName)
        }
        return captureCarBehavior(source, model)
    }

    private fun resolveBehavior(): work.art1st.javarailways.bukkit.config.model.TrainBehaviorScript? =
        operationalSnapshot()?.behavior

    private fun operationalSnapshot(): SavedTrainPackageSnapshot? = operationalSnapshot(
        BukkitPlugin.INSTANCE.javaRailwaysConfig.trainModelUpdatePolicy,
        TrainPackageSnapshotStore::load, BukkitPlugin.INSTANCE.modelManager::resourceVersion
    )

    private fun operationalSnapshot(
        policy: TrainModelUpdatePolicy,
        loadPackage: (String) -> SavedTrainPackageSnapshot?,
        liveVersion: (String) -> TrainResourceVersion
    ): SavedTrainPackageSnapshot? {
        if (carModelName == null) return null
        if (policy != TrainModelUpdatePolicy.SNAPSHOT) {
            val type = requireNotNull(resourceType) { "Car '${self.id}' has no creation template" }
            return liveVersion(type).carPackages.getValue(requireNotNull(carModelName))
        }
        val hash = requireNotNull(savedPackageSnapshotHash) { "Car '${self.id}' has no creation-time configuration snapshot" }
        return savedPackageSnapshot ?: requireNotNull(loadPackage(hash)) {
            "Pinned car package snapshot '$hash' is unavailable"
        }.also { savedPackageSnapshot = it }
    }

    internal fun behaviorLocation(): Location {
        val position = latestFrame?.state?.positionVector() ?: self.initialRenderState.position
        return Location(self.world.bukkitWorldData.bukkitWorld, position.x(), position.y(), position.z())
    }

    @Synchronized
    internal fun behaviorActions(tail: Boolean): List<TrainBehaviorActionDefinition> {
        ensureBehaviorOwned()
        return carBehaviorActions(resolveActions(), behavior.metadata(tail))
    }

    @Synchronized
    internal fun executeDoor(side: DoorSide, opening: Boolean, tail: Boolean, done: (Result<Unit>) -> Unit): TrainModelOperationResult {
        ensureBehaviorOwned()
        return behavior.door(side, opening, tail, done)
    }

    /** Discard script globals, scheduled effects and persisted animation selections. */
    @Synchronized
    internal fun resetOperationalState() {
        ensureBehaviorOwned()
        behavior.reset()
        pendingHeldScenes = emptyMap()
        playback.resetToDefault()
    }

    @Synchronized
    internal fun estimateDoorCloseDurationTicks(side: DoorSide, tail: Boolean): Long {
        ensureBehaviorOwned()
        return behavior.estimateDoorCloseDurationTicks(side, tail)
    }

    @Synchronized
    internal fun executeAction(name: String, tail: Boolean, done: (Result<Unit>) -> Unit): TrainModelOperationResult {
        ensureBehaviorOwned()
        return behavior.action(name, tail, done)
    }

    @Synchronized
    internal fun executeAnimation(name: String, scene: String?): Boolean = !executeAnimationTracked(name, scene).isCompletedExceptionally

    @Synchronized
    internal fun executeAnimationTracked(name: String, scene: String?): CompletableFuture<Unit> {
        ensureBehaviorOwned()
        return playback.play(name, scene)
    }

    @Synchronized
    internal fun executeBehavior(method: String, args: List<Any?>, tail: Boolean, done: (Result<Unit>) -> Unit, optional: Boolean = false): TrainModelOperationResult {
        ensureBehaviorOwned()
        return behavior.call(method, args, tail, done, optional)
    }

    private fun stopTrainForScriptFailure(failure: work.art1st.javarailways.bukkit.behavior.TrainBehaviorScriptException) {
        val train = self.train as? work.art1st.javarailways.core.physics.train.CoreTrain
        BukkitPlugin.INSTANCE.logger.log(java.util.logging.Level.SEVERE,
            "Car behavior failed: car=${self.id}, train=${train?.id}", failure)
        if (train != null) train.world.physicsCoordinator.submitBetweenFrames {
            if (self.train === train && train.world.getTrain(train.id) === train) {
                train.derail("Car behavior failed: car=${self.id}: ${failure.message}")
            }
        }
    }

    private fun ensureBehaviorOwned() {
        check(!behaviorClosed) { "car behavior is closed" }
        BukkitThreadOwnership.ensureLocation(behaviorLocation(), "CarExtension.behavior")
    }

    @Synchronized
    internal fun closeBehavior() {
        if (behaviorClosed) return
        behaviorClosed = true
        // Accessing lazy here does not initialize a JS Context; close also covers never-used cars.
        try { behavior.close() } finally { try { playback.close() } finally { if (behaviorClockDelegate.isInitialized()) behaviorClock.close() } }
    }

    /** Synchronous commit entry point used by explicit Core command paths. */
    @Synchronized
    override fun commit() {
        applyFrameState(captureLiveFrame())
    }

    /** Applies one value-only frame; revisions are ordered only within their exact runtime train. */
    @Synchronized
    internal fun applyPublishedFrame(
        frame: CarFrameSnapshot,
        publicationStreamId: UUID,
        revision: Long,
        partRenderSnapshot: TrainPartRenderSnapshot?
    ) {
        require(revision >= 0L) { "published car frame revision must be non-negative" }
        ensureFrameLocationOwned(frame)
        if (!publishedFrameGate.accept(self as CoreCar, publicationStreamId, revision)) return
        // A pre-publication model has no source identity yet; retire it on its first
        // accepted source too, so a transfer before that first frame cannot reuse an old session.
        val streamChanged = latestPartRenderStream != publicationStreamId &&
            (latestPartRenderStream != null || hasDynamicExtensionModel)
        val contextChanged = partRenderSnapshot != null &&
            latestPartRenderSnapshot?.contextKey != partRenderSnapshot.contextKey
        // A geometry-only envelope carries no new metadata. It may retain the last sample only
        // within the same train stream; never borrow the old train's values after split/join.
        if (streamChanged) latestPartRenderSnapshot = null
        if (partRenderSnapshot != null) latestPartRenderSnapshot = partRenderSnapshot
        latestPartRenderStream = publicationStreamId
        applyFrameState(
            frame,
            locationOwnershipChecked = true,
            rebuildForStreamChange = streamChanged,
            invalidateExtensionContext = contextChanged && !streamChanged
        )
    }

    private fun applyFrameState(
        frame: CarFrameSnapshot,
        locationOwnershipChecked: Boolean = false,
        rebuildForStreamChange: Boolean = false,
        invalidateExtensionContext: Boolean = false
    ) {
        if (!locationOwnershipChecked) ensureFrameLocationOwned(frame)
        val oldPosition = latestFrame?.state?.positionVector() ?: self.initialRenderState.position
        latestFrame = frame
        if (invalidateExtensionContext) {
            bodyModel?.invalidateExtensionContext()
            bogieModels.forEach { it.invalidateExtensionContext() }
        }
        self.world.renderChunkManager.update(self, oldPosition, frame.state.positionVector())
        if (rebuildForStreamChange) rebuildModelIfBuilt()
        super.commit()
    }

    private fun ensureFrameLocationOwned(frame: CarFrameSnapshot) {
        BukkitThreadOwnership.ensureLocation(
            Location(
                self.world.bukkitWorldData.bukkitWorld,
                frame.state.positionX,
                frame.state.positionY,
                frame.state.positionZ
            ),
            "CarExtension.applyPublishedFrame"
        )
    }

    private val destruction = Destruction()

    @Synchronized
    override fun destroy(): CompletionStage<Void> = destruction.run {
        val steps = DestructionSteps()
        steps.action(::closeBehavior)
        steps.await { super.destroy() }
        steps.await { self.world.renderChunkManager.remove(self, latestFrame?.state?.positionVector() ?: self.position) }
        steps.finish()
    }

    override fun buildModel() {
        val plugin = BukkitPlugin.INSTANCE
        val worldId = self.world.bukkitWorldData.bukkitWorld.uid
        val trainId = (self as? CoreCar)?.train?.id ?: self.id
        try {
            buildPreparedModel()
            plugin.worldIssues.resolve(worldId, "javarailways:train-model", WorldIssueKind.TRAIN, trainId, "MODEL_UNAVAILABLE:${self.id}")
        } catch (failure: Exception) {
            plugin.worldIssues.report(worldId, "javarailways:train-model", WorldIssueInput(WorldIssueKind.TRAIN, trainId,
                "MODEL_UNAVAILABLE:${self.id}", "Car '${self.id}' model is unavailable: ${failure.message}"))
            throw failure
        }
    }

    private var capabilityModel: SavedCarModelTree? = null
    private var animationCapabilities: Map<String, Set<String>> = emptyMap()

    /** Same resource policy as display construction, independent of visibility. */
    private fun resolveModelSnapshot(policy: TrainModelUpdatePolicy = BukkitPlugin.INSTANCE.javaRailwaysConfig.trainModelUpdatePolicy): SavedCarModelTree {
        val modelName = requireNotNull(carModelName) { "Car model name not set" }
        val modelManager = BukkitPlugin.INSTANCE.modelManager
        return if (policy == TrainModelUpdatePolicy.SNAPSHOT) {
            savedModelSnapshot ?: requireNotNull(CarModelSnapshotStore.load(requireNotNull(savedModelSnapshotHash) {
                "Car '${self.id}' has no creation-time model snapshot"
            })) { "Pinned car model snapshot '$savedModelSnapshotHash' is unavailable" }.also { savedModelSnapshot = it }
        } else {
            requireNotNull(modelManager.resourceVersion(requireNotNull(resourceType) {
                "Car '${self.id}' has no creation template"
            }).models[modelName]) { "Template '$resourceType' has no model '$modelName'" }
        }
    }

    private var playbackModel: SavedCarModelTree? = null
    private var playbackParts: List<Map<String, work.art1st.javarailways.bukkit.render.Animation>> = emptyList()
    private fun animationParts(source: SavedCarModelTree = resolveModelSnapshot()): List<Map<String, work.art1st.javarailways.bukkit.render.Animation>> {
        if (playbackModel !== source) {
            if (playbackModel != null) playback.resetForSourceChange()
            playbackParts = source.captureAnimationParts()
            playbackModel = source
        }
        return playbackParts
    }

    private fun projectBehaviorFrame(index: Int, frame: work.art1st.javarailways.bukkit.behavior.CarAnimationPlayback.Frame) {
        if (bodyModel != null || bogieModels.isNotEmpty())
            behaviorModelNodes.getOrNull(index)?.projectAnimationFrame(frame.name, frame.scene, frame.transform, frame.display)
        if (frame.name != null && frame.scene != null) pendingHeldScenes = pendingHeldScenes + (frame.name to frame.scene)
    }

    private fun hasAnimation(name: String, scene: String?): Boolean {
        val snapshot = resolveModelSnapshot()
        if (capabilityModel !== snapshot) {
            animationCapabilities = snapshot.captureAnimationCapabilities()
            capabilityModel = snapshot
        }
        return animationCapabilities.supportsAnimation(name, scene)
    }

    private fun buildPreparedModel() {
        val modelManager = BukkitPlugin.INSTANCE.modelManager
        val policy = BukkitPlugin.INSTANCE.javaRailwaysConfig.trainModelUpdatePolicy
        val live = if (policy == TrainModelUpdatePolicy.SNAPSHOT) null else modelManager.captureResourceVersion(requireNotNull(resourceType))
        val modelGeneration = live?.first ?: modelManager.generation
        val snapshot = if (live == null) resolveModelSnapshot(policy) else live.second.models.getValue(requireNotNull(carModelName))
        val sourcePackage = requireNotNull(operationalSnapshot(policy, TrainPackageSnapshotStore::load) { live!!.second }) {
            "Car '${self.id}' has no source package snapshot"
        }
        animationParts(snapshot) // Settle old playback using this exact construction version.
        val carDef = snapshot.car
        val builder = modelManager.createTreeBuilder(self.world, snapshot.partResolver(), sourcePackage)
        val bukkitWorld = self.world.bukkitWorldData.bukkitWorld
        val rootState = renderFrame().state
        val rootLocation = Location(bukkitWorld, rootState.positionX, rootState.positionY, rootState.positionZ)

        ModelConstruction.build { construction ->
            val body = builder.buildCarBody(carDef, rootLocation, self)
            construction.own(body, body::destroy)
            val bogies = carDef.bogies.map { bogieDef ->
                builder.buildBogie(bogieDef, carDef.namespace, rootLocation).also { construction.own(it, it::destroy) }
            }
            val platforms = buildList<PlatformModel> {
                body.collectModelsOfType(this)
                bogies.forEach { it.collectModelsOfType(this) }
            }
            val seats = buildList<SeatModel> {
                body.collectModelsOfType(this)
                bogies.forEach { it.collectModelsOfType(this) }
            }
            body.setAnimationScenes(pendingHeldScenes)
            bogies.forEach { it.setAnimationScenes(pendingHeldScenes) }
            bodyModel = body
            bogieModels = bogies
            platformModels = platforms
            seatModels = seats
            behaviorModelNodes = buildList {
                body.forEachModelRecursive { if (it.animation.isNotEmpty()) add(it) }
                bogies.forEach { model -> model.forEachModelRecursive { if (it.animation.isNotEmpty()) add(it) } }
            }
            playback.projectCurrentFrames()
        }
        hasDynamicExtensionModel = bodyModel?.hasExtensionRenderers == true || bogieModels.any { it.hasExtensionRenderers }
        builtModelGeneration = modelGeneration
    }

    override fun updateModel() {
        val plugin = BukkitPlugin.INSTANCE
        if (plugin.javaRailwaysConfig.trainModelUpdatePolicy == TrainModelUpdatePolicy.FOLLOW_PACKAGE &&
            builtModelGeneration != plugin.modelManager.generation
        ) {
            rebuildModelIfBuilt()
            return
        }
        plugin.trainPacketBundles.frame(::updateModelBundled)
    }

    private fun updateModelBundled() {
        val model = bodyModel ?: return
        hasDynamicExtensionModel = model.hasExtensionRenderers || bogieModels.any { it.hasExtensionRenderers }
        latestPartRenderSnapshot?.let { input ->
            model.updateExtensionInput(input.data, input.tick)
            bogieModels.forEach { it.updateExtensionInput(input.data, input.tick) }
        }
        val frame = renderFrame()
        val state = frame.state
        val position = state.positionVector()
        val direction = state.directionVector()
        val normal = state.normalVector()
        val motion = frame.bukkitMotion()
        val velocity = motion.bodyVelocityBlocksPerTick
        modelUpdateSequence += 1L
        val nearDetailAudience = detailAudience.isNotEmpty()
        val movingWithAudience = velocity.lengthSquared() > MOVING_EPSILON_SQUARED && hasAudience()
        val updateInterval = if (nearDetailAudience || movingWithAudience) NEAR_UPDATE_INTERVAL else FAR_UPDATE_INTERVAL
        val shouldUpdateModel = forceNextModelUpdate || !modelStateApplied ||
            updateInterval == 1 || modelUpdateSequence % updateInterval == 0L
        if (!shouldUpdateModel) {
            detailStateCurrent = false
            // Keep world positions exact every tick while avoiding the expensive recursive child
            // transform walk for distant cars.
            model.updateRootPosition(position)
            frame.bogies.zip(bogieModels).forEach { (bogieState, bogieModel) ->
                bogieModel.updateRootPosition(bogieRootPosition(bogieState))
            }
            return
        }
        forceNextModelUpdate = false
        modelStateApplied = true
        detailStateCurrent = true
        val renderRotation = rotationFrom(direction, normal)
        val carTransform = CompactTransform(position = position, rotation = renderRotation)
        model.updateState(carTransform, velocity)

        if (TrainDebugLog.enabled) {
            val euler = TrainDebugLog.euler(renderRotation)
            TrainDebugLog.logRow(listOf(
                System.currentTimeMillis(), "render", self.world.bukkitWorldData.bukkitWorld.uid,
                null, null, (self as? CoreCar)?.id ?: self.hashCode(),
                null, null, null,
                null, null, null, null, null,
                position.x(), position.y(), position.z(),
                direction.x(), direction.y(), direction.z(),
                normal.x(), normal.y(), normal.z(),
                null, null, null, null, null, null, null, null, null, null, null, null,
                euler.x(), euler.y(), euler.z(),
                renderRotation.x(), renderRotation.y(), renderRotation.z(), renderRotation.w(),
                "CarExtension.updateModel"
            ))
        }

        frame.bogies.zip(bogieModels).forEachIndexed { index, (bogieState, bogieModel) ->
            val bogieTransform = CompactTransform(
                position = bogieRootPosition(bogieState),
                rotation = rotationFrom(bogieState.directionVector(), bogieState.normalVector())
            )
            bogieModel.updateState(bogieTransform, motion.bogieVelocitiesBlocksPerTick[index])
        }

    }

    @Synchronized
    override fun refreshFor(player: Player) {
        BukkitThreadOwnership.ensureEntity(player, "CarExtension.refreshFor")
        if (!ensureModelBuilt()) return
        bodyModel?.showTo(player)
        bodyModel?.updatePlayerVisibility(player)
        bogieModels.forEach { model ->
            model.showTo(player)
            model.updatePlayerVisibility(player)
        }
        if (isWithinDetailDistance(player)) {
            detailAudience.add(player.uniqueId)
            if (!detailStateCurrent) forceNextModelUpdate = true
        } else {
            detailAudience.remove(player.uniqueId)
        }
        refreshInteractiveActivation(player)
    }

    override fun onAudienceRemoved(player: Player) {
        detailAudience.remove(player.uniqueId)
        bodyModel?.hideFrom(player)
        bogieModels.forEach { it.hideFrom(player) }
        deactivateInteractiveModelsFor(player)
    }

    override fun destroyModel(): CompletionStage<Void> {
        hasDynamicExtensionModel = false
        val steps = DestructionSteps()
        steps.action { snapshotHeldScenesForReload() }
        bodyModel?.let { model -> steps.await { model.destroy() } }
        bogieModels.forEach { model -> steps.await { model.destroy() } }
        behaviorModelNodes = emptyList()
        bodyModel = null
        bogieModels = emptyList()
        platformModels = emptyList()
        seatModels = emptyList()
        modelUpdateSequence = 0L
        modelStateApplied = false
        detailStateCurrent = false
        forceNextModelUpdate = false
        builtModelGeneration = -1L
        detailAudience.clear()
        return steps.finish()
    }

    override fun applyAnimation(name: String): Boolean {
        ensureBehaviorOwned()
        return !playback.play(name, null).isCompletedExceptionally
    }

    override fun applyAnimation(name: String, sceneName: String): Boolean {
        ensureBehaviorOwned()
        return !playback.play(name, sceneName).isCompletedExceptionally
    }

    private fun snapshotHeldScenesForReload() {
        if (bodyModel == null && bogieModels.isEmpty()) return
        bodyModel?.prepareForUnload()
        bogieModels.forEach { it.prepareForUnload() }
        pendingHeldScenes = collectHeldScenes()
    }

    private fun collectHeldScenes(): Map<String, String> {
        val scenes = linkedMapOf<String, String>()
        pendingHeldScenes.forEach { (name, scene) -> scenes[name] = scene }
        bodyModel?.collectHeldAnimationScenes()?.forEach { (name, scene) -> scenes[name] = scene }
        bogieModels.forEach { model ->
            model.collectHeldAnimationScenes().forEach { (name, scene) -> scenes[name] = scene }
        }
        return scenes
    }

    private fun applyHeldScenes(scenes: Map<String, String>) {
        if (scenes.isEmpty()) return
        var applied = bodyModel?.setAnimationScenes(scenes) ?: false
        bogieModels.forEach { model ->
            applied = model.setAnimationScenes(scenes) || applied
        }
        if (applied) {
            pendingHeldScenes = scenes
        }
    }

    private fun refreshInteractiveActivation(player: Player) {
        platformModels.forEach { it.refreshActivation(player) }
        val playerLocation = player.location
        seatModels.forEach { it.refreshActivation(player, playerLocation) }
    }

    private fun deactivateInteractiveModelsFor(player: Player) {
        platformModels.forEach { it.deactivateFor(player) }
        seatModels.forEach { it.deactivateFor(player) }
    }

    private fun isWithinDetailDistance(player: Player): Boolean {
        if (player.world != self.world.bukkitWorldData.bukkitWorld) return false
        val location = player.location
        val state = renderFrame().state
        val dx = location.x - state.positionX
        val dy = location.y - state.positionY
        val dz = location.z - state.positionZ
        val threshold = player.railViewDistanceSettings.lodThreshold
        return dx * dx + dy * dy + dz * dz <= threshold * threshold
    }

    private inline fun <reified T> CompositeModel.collectModelsOfType(target: MutableList<T>) {
        forEachModelRecursive { model ->
            if (model is T) target += model
        }
    }

    /**
     * Compute a model-space rotation from direction and normal vectors.
     */
    private fun rotationFrom(direction: org.joml.Vector3dc, normal: org.joml.Vector3dc): Quaterniond {
        val right = Vector3d()
        normal.cross(direction, right).normalize()
        // Matrix columns: model-X→right(width), model-Y→normal(up), model-Z→direction(fwd).
        val rotationMatrix = Matrix3d(
            right.x(), right.y(), right.z(),
            normal.x(), normal.y(), normal.z(),
            direction.x(), direction.y(), direction.z()
        )
        return Quaterniond().setFromNormalized(rotationMatrix)
    }

    private fun renderFrame(): CarFrameSnapshot = latestFrame ?: captureLiveFrame()

    private fun captureLiveFrame(): CarFrameSnapshot = CarFrameSnapshot.create(
        carId = (self as? CoreCar)?.id ?: self.hashCode().toString(),
        lengthMeters = self.length,
        state = RenderableFrameSnapshot.capture(self),
        bogies = self.bogies.map(RenderableFrameSnapshot::capture)
    )

    private fun bogieRootPosition(state: RenderableFrameSnapshot): Vector3d =
        state.positionVector().add(state.normalVector().mul(self.floorHeight))

    companion object {
        private const val NEAR_UPDATE_INTERVAL = 1
        private const val FAR_UPDATE_INTERVAL = 4
        private const val MOVING_EPSILON_SQUARED = 1.0e-6
    }
}

/** Bukkit entity motion derived exclusively from one immutable Core frame. */
internal data class BukkitCarFrameMotion(
    val bodyVelocityBlocksPerTick: Vector3d,
    val bogieVelocitiesBlocksPerTick: List<Vector3d>
)

/** Converts Core world-space metres/second to Bukkit blocks/tick without changing direction. */
internal fun CarFrameSnapshot.bukkitMotion(): BukkitCarFrameMotion = BukkitCarFrameMotion(
    bodyVelocityBlocksPerTick = state.velocityVector().div(SERVER_TICKS_PER_SECOND),
    bogieVelocitiesBlocksPerTick = bogies.map { it.velocityVector().div(SERVER_TICKS_PER_SECOND) }
)

private const val SERVER_TICKS_PER_SECOND = 20.0

/** Per-car ordering gate for frames emitted by one exact process-local train stream. */
internal class CarFramePublicationGate {
    private var publicationStreamId: UUID? = null
    private var latestRevision: Long = -1L

    fun accept(car: CoreCar, streamId: UUID, revision: Long): Boolean {
        require(revision >= 0L) { "published car frame revision must be non-negative" }
        if (!car.isOwnedByPublicationStream(streamId)) return false
        if (publicationStreamId == streamId && revision <= latestRevision) return false
        publicationStreamId = streamId
        latestRevision = revision
        return true
    }
}

/** Detached values only; no script initialization, render graph or source-world callback. */
internal fun captureCarBehavior(source: SavedTrainPackageSnapshot, model: SavedCarModelTree): CapturedCarBehavior =
    CapturedCarBehavior(
        source.behavior?.copy(),
        source.actions.mapValues { (_, action) -> action.copy(
            animations = action.animations.toList(), sounds = action.sounds.toList()
        ) },
        model.captureAnimationParts()
    )
