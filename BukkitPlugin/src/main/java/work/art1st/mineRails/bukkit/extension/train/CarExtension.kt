package work.art1st.mineRails.bukkit.extension.train

import org.bukkit.Location
import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Vector3d
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.debug.TrainDebugLog
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

    private var bodyModel: CompositeModel? = null
    private var bogieModels: List<CompositeModel> = emptyList()

    override fun save(): Pair<String, SerializableExtensionData>? = null

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
        val modelManager = BukkitPlugin.INSTANCE.modelManager
        val carDef = carModelName?.let { modelManager.getCar(it) }
            ?: throw IllegalStateException("Car model name not set for car at ${self.position}")

        val builder = modelManager.createTreeBuilder(self.world, carDef.packageName)
        val bukkitWorld = self.world.bukkitWorldData.bukkitWorld
        val rootLocation = Location(bukkitWorld, self.position.x(), self.position.y(), self.position.z())

        bodyModel = builder.buildCarBody(carDef, rootLocation)
        bogieModels = carDef.bogies.map { bogieDef ->
            builder.buildBogie(bogieDef, carDef.namespace, rootLocation)
        }
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
