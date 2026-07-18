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
import work.art1st.mineRails.core.api.physics.train.Car
import work.art1st.mineRails.core.api.physics.train.CarFrameSnapshot
import work.art1st.mineRails.core.api.render.RenderableFrameSnapshot
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
import work.art1st.mineRails.core.physics.train.CoreCar
import work.art1st.mineRails.bukkit.extension.renderChunkManager
import work.art1st.mineRails.bukkit.util.BukkitThreadOwnership
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
    private val detailAudience: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

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
    @Synchronized
    fun applyFrame(frame: CarFrameSnapshot, revision: Long) {
        BukkitThreadOwnership.ensureLocation(
            Location(
                self.world.bukkitWorldData.bukkitWorld,
                frame.state.positionX,
                frame.state.positionY,
                frame.state.positionZ
            ),
            "CarExtension.applyFrame"
        )
        if (revision <= latestFrameRevision) return
        val oldPosition = latestFrame?.state?.positionVector() ?: self.lastState.position
        latestFrame = frame
        latestFrameRevision = revision
        self.world.renderChunkManager.update(self, oldPosition, frame.state.positionVector())
        super.commit()
    }

    @Synchronized
    override fun destroy() {
        val renderManager = self.world.renderChunkManager
        renderManager.remove(self, latestFrame?.state?.positionVector() ?: self.position)
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
        val rootState = renderFrame().state
        val rootLocation = Location(bukkitWorld, rootState.positionX, rootState.positionY, rootState.positionZ)

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
        val position = state.positionVector()
        val direction = state.directionVector()
        val normal = state.normalVector()
        val velocity = state.velocityVector()
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

        frame.bogies.zip(bogieModels).forEach { (bogieState, bogieModel) ->
            val bogieTransform = CompactTransform(
                position = bogieRootPosition(bogieState),
                rotation = rotationFrom(bogieState.directionVector(), bogieState.normalVector())
            )
            bogieModel.updateState(bogieTransform, bogieState.velocityVector())
        }

    }

    @Synchronized
    override fun refreshFor(player: Player) {
        BukkitThreadOwnership.ensureEntity(player, "CarExtension.refreshFor")
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
        detailAudience.clear()
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
