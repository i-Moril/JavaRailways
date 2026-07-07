package work.art1st.mineRails.bukkit.extension.train

import org.bukkit.Location
import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Vector3d
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.config.TrainModelUpdatePolicy
import work.art1st.mineRails.bukkit.debug.TrainDebugLog
import work.art1st.mineRails.bukkit.persistence.CarModelSnapshotStore
import work.art1st.mineRails.bukkit.render.model.CompactTransform
import work.art1st.mineRails.bukkit.render.model.CompositeModel
import work.art1st.mineRails.core.api.physics.train.Car
import work.art1st.mineRails.core.api.physics.train.Bogie
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
import work.art1st.mineRails.core.physics.train.CoreCar
import work.art1st.mineRails.bukkit.extension.renderChunkManager

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
        applyHeldScenes(pendingHeldScenes)
    }

    override fun updateModel() {
        val model = bodyModel ?: return
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
            val bogieRootPosition = Vector3d(bogie.position).add(
                Vector3d(bogie.normal).mul(self.floorHeight)
            )
            val bogieTransform = CompactTransform(
                position = bogieRootPosition,
                rotation = currentBogieRotation(bogie)
            )
            bogieModel.updateState(bogieTransform, bogie.velocity)
        }
    }

    override fun destroyModel() {
        bodyModel?.destroy()
        bogieModels.forEach { it.destroy() }
        bodyModel = null
        bogieModels = emptyList()
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
}
