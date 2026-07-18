package work.art1st.mineRails.bukkit.extension.train

import org.bukkit.Location
import org.bukkit.entity.Player
import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Vector3d
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.config.TrainModelUpdatePolicy
import work.art1st.mineRails.bukkit.debug.TrainDebugLog
import work.art1st.mineRails.bukkit.persistence.CarModelSnapshotStore
import work.art1st.mineRails.bukkit.render.model.CompactTransform
import work.art1st.mineRails.bukkit.render.model.CompositeModel
import work.art1st.mineRails.bukkit.render.model.PlatformModel
import work.art1st.mineRails.bukkit.render.model.SeatModel
import work.art1st.mineRails.bukkit.render.chunk.railViewDistanceSettings
import work.art1st.mineRails.core.api.physics.train.BogieFrameSnapshot
import work.art1st.mineRails.core.api.physics.train.Car
import work.art1st.mineRails.core.api.physics.train.CarFrameSnapshot
import work.art1st.mineRails.core.api.render.RenderableFrameSnapshot
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
import work.art1st.mineRails.core.physics.train.CoreCar
import work.art1st.mineRails.bukkit.extension.renderChunkManager
import work.art1st.mineRails.bukkit.util.BukkitThreadOwnership
import work.art1st.mineRails.core.util.ThreadGuard

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
    private var bodyModel: CompositeModel? = null
    private var bogieModels: List<CompositeModel> = emptyList()
    private var platformModels: List<PlatformModel> = emptyList()
    private var seatModels: List<SeatModel> = emptyList()
    private var modelUpdateSequence: Long = 0L
    private var modelStateApplied: Boolean = false
    private var detailStateCurrent: Boolean = false
    private var forceNextModelUpdate: Boolean = false
    private var latestFrameRevision: Long = -1L
    private var latestFrame: CarFrameSnapshot? = null

    override fun initialize(host: Car, savedExtensionData: Map<String, SerializableExtensionData>) {
        super.initialize(host, savedExtensionData)
        val savedState = savedExtensionData[BukkitPlugin.EXTENSION_ID] as? SavedCarRenderState
        if (savedState != null) {
            carModelName = savedState.carModelName
            pendingHeldScenes = savedState.heldScenes
            savedModelSnapshotHash = savedState.modelSnapshotHash
        }
    }

    override fun save(): Pair<String, SerializableExtensionData>? {
        val modelName = carModelName ?: return null
        val snapshotHash = if (BukkitPlugin.INSTANCE.mineRailsConfig.trainModelUpdatePolicy == TrainModelUpdatePolicy.SNAPSHOT) {
            BukkitPlugin.INSTANCE.modelManager.createCarModelSnapshot(modelName)?.let { snapshot ->
                CarModelSnapshotStore.save(snapshot)
            }
        } else {
            null
        }
        return BukkitPlugin.EXTENSION_ID to SavedCarRenderState(
            carModelName = modelName,
            heldScenes = collectHeldScenes(),
            modelSnapshotHash = snapshotHash
        )
    }

    /** Legacy synchronous entry point retained for explicit Core command paths. */
    override fun commit() {
        applyFrame(captureLiveFrame(), latestFrameRevision + 1L)
    }

    /** Applies one value-only frame; stale revisions cannot move a model backwards. */
    fun applyFrame(frame: CarFrameSnapshot, revision: Long) {
        ThreadGuard.ensureMainThread("CarExtension.applyFrame")
        if (revision <= latestFrameRevision) return
        val oldPosition = latestFrame?.state?.position?.toVector3d() ?: self.lastState.position
        latestFrame = frame
        latestFrameRevision = revision
        self.world.renderChunkManager.update(self, oldPosition, frame.state.position.toVector3d())
        super.commit()
    }

    override fun destroy() {
        val renderManager = self.world.renderChunkManager
        renderManager.remove(self, latestFrame?.state?.position?.toVector3d() ?: self.position)
        super.destroy()
    }

    override fun buildModel() {
        val modelName = carModelName
            ?: throw IllegalStateException("Car model name not set for car at ${self.position}")
        val modelManager = BukkitPlugin.INSTANCE.modelManager
        val snapshot = if (BukkitPlugin.INSTANCE.mineRailsConfig.trainModelUpdatePolicy == TrainModelUpdatePolicy.SNAPSHOT) {
            savedModelSnapshotHash?.let { CarModelSnapshotStore.load(it) }
        } else {
            null
        }
        val carDef = snapshot?.car ?: modelManager.getCar(modelName)
            ?: throw IllegalStateException("Car model not found: $modelName")
        val builder = if (snapshot != null) {
            modelManager.createTreeBuilder(self.world, snapshot.partResolver())
        } else {
            modelManager.createTreeBuilder(self.world, carDef.packageName)
        }
        val bukkitWorld = self.world.bukkitWorldData.bukkitWorld
        val rootPosition = renderFrame().state.position
        val rootLocation = Location(bukkitWorld, rootPosition.x, rootPosition.y, rootPosition.z)

        bodyModel = builder.buildCarBody(carDef, rootLocation, self)
        bogieModels = carDef.bogies.map { bogieDef ->
            builder.buildBogie(bogieDef, carDef.namespace, rootLocation)
        }
        platformModels = buildList {
            bodyModel?.collectModelsOfType(this)
            bogieModels.forEach { it.collectModelsOfType(this) }
        }
        seatModels = buildList {
            bodyModel?.collectModelsOfType(this)
            bogieModels.forEach { it.collectModelsOfType(this) }
        }
        applyHeldScenes(pendingHeldScenes)
    }

    override fun updateModel() {
        val model = bodyModel ?: return
        val frame = renderFrame()
        val state = frame.state
        val position = state.position.toVector3d()
        val direction = state.direction.toVector3d()
        val normal = state.normal.toVector3d()
        val velocity = state.velocity.toVector3d()
        modelUpdateSequence += 1L
        val nearDetailAudience = hasAudiencePlayer(::isWithinDetailDistance)
        val movingWithAudience = velocity.lengthSquared() > MOVING_EPSILON_SQUARED && hasAudiencePlayer { true }
        val updateInterval = if (nearDetailAudience || movingWithAudience) NEAR_UPDATE_INTERVAL else FAR_UPDATE_INTERVAL
        val shouldUpdateModel = forceNextModelUpdate || !modelStateApplied ||
            updateInterval == 1 || modelUpdateSequence % updateInterval == 0L
        if (!shouldUpdateModel) {
            detailStateCurrent = false
            // Keep world positions exact every tick while avoiding the expensive recursive child
            // transform walk for distant cars.
            model.updateRootPosition(position)
            frame.bogies.zip(bogieModels).forEach { (bogie, bogieModel) ->
                bogieModel.updateRootPosition(bogieRootPosition(bogie.state))
            }
            refreshInteractiveActivationForAudience()
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

        frame.bogies.zip(bogieModels).forEach { (bogie, bogieModel) ->
            val bogieState = bogie.state
            val bogieTransform = CompactTransform(
                position = bogieRootPosition(bogieState),
                rotation = rotationFrom(bogieState.direction.toVector3d(), bogieState.normal.toVector3d())
            )
            bogieModel.updateState(bogieTransform, bogieState.velocity.toVector3d())
        }

        refreshInteractiveActivationForAudience()
    }

    override fun refreshFor(player: Player) {
        BukkitThreadOwnership.ensureEntity(player, "CarExtension.refreshFor")
        bodyModel?.showTo(player)
        bodyModel?.updatePlayerVisibility(player)
        bogieModels.forEach { model ->
            model.showTo(player)
            model.updatePlayerVisibility(player)
        }
        if (isWithinDetailDistance(player) && !detailStateCurrent) {
            forceNextModelUpdate = true
            updateModel()
            return
        }
        refreshInteractiveActivation(player)
    }

    override fun onAudienceRemoved(player: Player) {
        bodyModel?.hideFrom(player)
        bogieModels.forEach { it.hideFrom(player) }
        deactivateInteractiveModelsFor(player)
    }

    override fun destroyModel() {
        snapshotHeldScenesForReload()
        bodyModel?.destroy()
        bogieModels.forEach { it.destroy() }
        bodyModel = null
        bogieModels = emptyList()
        platformModels = emptyList()
        seatModels = emptyList()
        modelUpdateSequence = 0L
        modelStateApplied = false
        detailStateCurrent = false
        forceNextModelUpdate = false
    }

    override fun applyAnimation(name: String): Boolean {
        var applied = bodyModel?.applyAnimation(name) ?: false
        bogieModels.forEach { model ->
            applied = model.applyAnimation(name) || applied
        }
        return applied
    }

    override fun applyAnimation(name: String, sceneName: String): Boolean {
        var applied = bodyModel?.applyAnimation(name, sceneName) ?: false
        bogieModels.forEach { model ->
            applied = model.applyAnimation(name, sceneName) || applied
        }
        if (applied) {
            pendingHeldScenes = pendingHeldScenes - name
        }
        return applied
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

    private fun refreshInteractiveActivationForAudience() {
        forEachAudiencePlayer(::refreshInteractiveActivation)
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
        val position = renderFrame().state.position
        val dx = location.x - position.x
        val dy = location.y - position.y
        val dz = location.z - position.z
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
        state = RenderableFrameSnapshot.capture(self),
        bogies = self.bogies.map { bogie ->
            BogieFrameSnapshot(RenderableFrameSnapshot.capture(bogie))
        }
    )

    private fun bogieRootPosition(state: RenderableFrameSnapshot): Vector3d =
        state.position.toVector3d().add(state.normal.toVector3d().mul(self.floorHeight))

    companion object {
        private const val NEAR_UPDATE_INTERVAL = 1
        private const val FAR_UPDATE_INTERVAL = 4
        private const val MOVING_EPSILON_SQUARED = 1.0e-6
    }
}
