package work.art1st.mineRails.bukkit.extension.train

import org.bukkit.Location
import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Vector3d
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.render.model.CompactTransform
import work.art1st.mineRails.bukkit.render.model.CompositeModel
import work.art1st.mineRails.core.api.physics.train.Car
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
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

    private var compositeModel: CompositeModel? = null

    override fun save(): Pair<String, SerializableExtensionData>? = null

    override fun commit() {
        val renderManager = self.world.renderChunkManager
        renderManager.update(self, self.lastState.position, self.position)
        // Bogies are rendered as part of the car model tree; no separate tracking needed.
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

        val builder = modelManager.createTreeBuilder(self.world)
        val bukkitWorld = self.world.bukkitWorldData.bukkitWorld
        val rootLocation = Location(bukkitWorld, self.position.x(), self.position.y(), self.position.z())

        compositeModel = builder.buildCar(carDef, rootLocation)
    }

    override fun updateModel() {
        val model = compositeModel ?: return
        val carTransform = CompactTransform(
            position = self.position,
            rotation = currentCarRotation()
        )
        model.updateState(carTransform, self.velocity)
    }

    override fun destroyModel() {
        compositeModel?.destroy()
        compositeModel = null
    }

    /**
     * Compute the car's world-space rotation from its direction and normal vectors.
     */
    private fun currentCarRotation(): Quaterniond {
        val direction = self.direction
        val normal = self.normal
        val right = Vector3d()
        normal.cross(direction, right).normalize()
        // Matrix: model-X→right(width), model-Y→normal(up), model-Z→direction(fwd)
        val rotationMatrix = Matrix3d(
            right.x(), normal.x(), direction.x(),
            right.y(), normal.y(), direction.y(),
            right.z(), normal.z(), direction.z()
        )
        val q = Quaterniond().setFromNormalized(rotationMatrix)
        // Roll 180° around Z (= direction-of-travel) to flip model right-side up.
        val roll180 = Quaterniond().rotateZ(Math.PI)
        return q.mul(roll180)
    }
}
