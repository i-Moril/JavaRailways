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
import work.art1st.mineRails.core.api.physics.train.Bogie
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
import work.art1st.mineRails.core.physics.train.CoreCar
import work.art1st.mineRails.bukkit.extension.renderChunkManager
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

    override fun commit() {
        val renderManager = self.world.renderChunkManager
        renderManager.update(self, self.lastState.position, self.position)
        // Bogie entities are owned here, but their transforms follow the live bogies.
        super.commit()
    }

    override fun destroy() {
        val renderManager = self.world.renderChunkManager
        renderManager.remove(self)
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
        val rootLocation = Location(bukkitWorld, self.position.x(), self.position.y(), self.position.z())

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
        modelUpdateSequence += 1L
        val nearDetailAudience = hasAudiencePlayer(::isWithinDetailDistance)
        val movingWithAudience = self.velocity.lengthSquared() > MOVING_EPSILON_SQUARED && hasAudiencePlayer { true }
        val updateInterval = if (nearDetailAudience || movingWithAudience) NEAR_UPDATE_INTERVAL else FAR_UPDATE_INTERVAL
        val shouldUpdateModel = forceNextModelUpdate || !modelStateApplied ||
            updateInterval == 1 || modelUpdateSequence % updateInterval == 0L
        if (!shouldUpdateModel) {
            detailStateCurrent = false
            // Keep world positions exact every tick while avoiding the expensive recursive child
            // transform walk for distant cars.
            model.updateRootPosition(self.position)
            self.bogies.zip(bogieModels).forEach { (bogie, bogieModel) ->
                bogieModel.updateRootPosition(bogieRootPosition(bogie))
            }
            refreshInteractiveActivationForAudience()
            return
        }
        forceNextModelUpdate = false
        modelStateApplied = true
        detailStateCurrent = true
        val renderRotation = rotationFrom(self.direction, self.normal)
        val carTransform = CompactTransform(
            position = self.position,
            rotation = renderRotation
        )
        model.updateState(carTransform, self.velocity)

        if (TrainDebugLog.enabled) {
            val euler = TrainDebugLog.euler(renderRotation)
            TrainDebugLog.logRow(listOf(
                System.currentTimeMillis(), "render", self.world.bukkitWorldData.bukkitWorld.uid,
                null, null, (self as? CoreCar)?.id ?: self.hashCode(),
                null, null, null,
                null, null, null, null, null,
                self.position.x(), self.position.y(), self.position.z(),
                self.direction.x(), self.direction.y(), self.direction.z(),
                self.normal.x(), self.normal.y(), self.normal.z(),
                null, null, null, null, null, null, null, null, null, null, null, null,
                euler.x(), euler.y(), euler.z(),
                renderRotation.x(), renderRotation.y(), renderRotation.z(), renderRotation.w(),
                "CarExtension.updateModel"
            ))
        }

        self.bogies.zip(bogieModels).forEach { (bogie, bogieModel) ->
            val bogieTransform = CompactTransform(
                position = bogieRootPosition(bogie),
                rotation = currentBogieRotation(bogie)
            )
            bogieModel.updateState(bogieTransform, bogie.velocity)
        }

        refreshInteractiveActivationForAudience()
    }

    override fun refreshFor(player: Player) {
        ThreadGuard.ensureMainThread("CarExtension.refreshFor")
        if (isWithinDetailDistance(player) && !detailStateCurrent) {
            forceNextModelUpdate = true
            updateModel()
            return
        }
        refreshInteractiveActivation(player)
    }

    override fun onAudienceRemoved(player: Player) {
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
        val dx = location.x - self.position.x()
        val dy = location.y - self.position.y()
        val dz = location.z - self.position.z()
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

    private fun currentBogieRotation(bogie: Bogie): Quaterniond =
        rotationFrom(bogie.direction, bogie.normal)

    private fun bogieRootPosition(bogie: Bogie): Vector3d =
        Vector3d(bogie.position).add(Vector3d(bogie.normal).mul(self.floorHeight))

    companion object {
        private const val NEAR_UPDATE_INTERVAL = 1
        private const val FAR_UPDATE_INTERVAL = 4
        private const val MOVING_EPSILON_SQUARED = 1.0e-6
    }
}
