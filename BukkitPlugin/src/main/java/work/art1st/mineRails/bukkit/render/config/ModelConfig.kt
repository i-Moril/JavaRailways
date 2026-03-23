package work.art1st.mineRails.bukkit.render.config

import kotlinx.serialization.Serializable
import org.bukkit.Location
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.joml.Quaterniond
import org.joml.Vector3d
import org.koin.core.scope.Scope
import work.art1st.mineRails.bukkit.extension.bukkitLocation
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
import work.art1st.mineRails.bukkit.nms.PlatformEntity
import work.art1st.mineRails.bukkit.render.model.AbstractModel
import work.art1st.mineRails.bukkit.render.model.CompactTransform
import work.art1st.mineRails.bukkit.render.model.CompositeModel
import work.art1st.mineRails.bukkit.render.model.DisplayModel
import work.art1st.mineRails.bukkit.render.model.EntityModel
import work.art1st.mineRails.bukkit.render.model.LodDisplayModel
import work.art1st.mineRails.bukkit.render.model.ModelState
import work.art1st.mineRails.bukkit.render.model.PlatformModel
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.render.Renderable
import work.art1st.mineRails.core.api.util.persistence.Loads
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData

@Serializable
class CompositeModelConfigV1(
    val attachedModels: List<ModelConfigV1<*>>
) : Loads<CompositeModel, Renderable>, SerializableExtensionData() {
    override fun load(dependency: Renderable): CompositeModel {
        return CompositeModel(
            world = dependency.world,
            models = attachedModels.map { it.load(dependency) },
            rootEntity = dependency.world.bukkitWorldData.bukkitWorld.spawn(
                dependency.bukkitLocation,
                ItemDisplay::class.java
            )
        )
    }
}

@Serializable
abstract class ModelConfigV1 <T : AbstractModel> (
    val offset: Vector3d = Vector3d(),
    val rotation: Vector3d = Vector3d(),
    val attachedModels: Collection<ModelConfigV1<*>> = listOf()
) : Loads<T, Renderable> {
    val modelState
        get() = ModelState(CompactTransform(
                        position = offset,
                        rotation = Quaterniond().rotateXYZ(
                            Math.toRadians(rotation.x),
                            Math.toRadians(rotation.y),
                            Math.toRadians(rotation.z)
                        )
                    )
                )
}

@Serializable
class DisplayModelConfigV1(
    val item: ItemStackConfig,
    val animations: Map<String, AnimationConfig>
) : ModelConfigV1<DisplayModel>() {
    override fun load(dependency: Renderable): DisplayModel {
        val world = dependency.world
        val bukkitWorld = world.bukkitWorldData.bukkitWorld
        val bukkitEntity = bukkitWorld.spawn(
            dependency.bukkitLocation,
            ItemDisplay::class.java
        )
        bukkitEntity.setItemStack(item.load(Unit))
        val model = DisplayModel(
            world = world,
            state = modelState,
            attachedModels = attachedModels.map { it.load(dependency) },
            animation = animations.mapValues { it.value.load(dependency) },
            entity = bukkitEntity
        )
        return model
    }
}

@Serializable
class LodDisplayModelConfigV1(
    val loItem: ItemStackConfig,
    val hiItem: ItemStackConfig,
    val animations: Map<String, AnimationConfig>
) : ModelConfigV1<LodDisplayModel>() {
    override fun load(dependency: Renderable): LodDisplayModel {
        val world = dependency.world
        val bukkitWorld = world.bukkitWorldData.bukkitWorld
        val bukkitEntityLo = bukkitWorld.spawn(
            dependency.bukkitLocation,
            ItemDisplay::class.java
        )
        val bukkitEntityHi = bukkitWorld.spawn(
            dependency.bukkitLocation,
            ItemDisplay::class.java
        )
        bukkitEntityLo.setItemStack(loItem.load(Unit))
        bukkitEntityHi.setItemStack(hiItem.load(Unit))
        val model = LodDisplayModel(
            world = world,
            state = modelState,
            attachedModels = attachedModels.map { it.load(dependency) },
            animation = animations.mapValues { it.value.load(dependency) },
            loEntity = bukkitEntityLo,
            hiEntity = bukkitEntityHi
        )
        return model
    }
}

@Serializable
class PlatformModelConfigV1(
    val animations: Map<String, AnimationConfig>
) : ModelConfigV1<PlatformModel>() {
    override fun load(dependency: Renderable): PlatformModel {
        val model = PlatformModel(
            world = dependency.world,
            state = modelState,
            attachedModels = attachedModels.map { it.load(dependency) },
            animation = animations.mapValues { it.value.load(dependency) }
        )
        return model
    }
}