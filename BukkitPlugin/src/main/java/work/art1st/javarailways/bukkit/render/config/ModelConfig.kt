@file:UseSerializers(work.art1st.javarailways.bukkit.render.persistence.Vector3dSerializer::class)

package work.art1st.javarailways.bukkit.render.config

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaterniond
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import work.art1st.configLib.persistence.Loads
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.config.model.PartPlatformConfig
import work.art1st.javarailways.bukkit.extension.bukkitLocation
import work.art1st.javarailways.bukkit.extension.bukkitWorldData
import work.art1st.javarailways.bukkit.render.backend.DisplayVisual
import work.art1st.javarailways.bukkit.render.backend.DisplayVisualContent
import work.art1st.javarailways.bukkit.render.backend.DisplayVisualDescriptor
import work.art1st.javarailways.bukkit.render.backend.displayViewRangeForDistance
import work.art1st.javarailways.bukkit.render.model.AbstractModel
import work.art1st.javarailways.bukkit.render.model.CompactTransform
import work.art1st.javarailways.bukkit.render.model.CompositeModel
import work.art1st.javarailways.bukkit.render.model.DisplayModel
import work.art1st.javarailways.bukkit.render.model.LodDisplayModel
import work.art1st.javarailways.bukkit.render.model.ModelState
import work.art1st.javarailways.bukkit.render.model.PlatformModel
import work.art1st.javarailways.bukkit.render.model.TRAIN_DISPLAY_INTERPOLATION_TICKS
import work.art1st.javarailways.bukkit.render.model.TRAIN_DISPLAY_TELEPORT_TICKS
import work.art1st.javarailways.bukkit.render.model.TRAIN_ROOT_INTERPOLATION_TICKS
import work.art1st.javarailways.bukkit.render.model.TRAIN_ROOT_TELEPORT_TICKS
import work.art1st.javarailways.core.api.render.Renderable
import work.art1st.javarailways.core.api.util.plugin.SerializableExtensionData

@Serializable
class CompositeModelConfigV1(
    val attachedModels: List<ModelConfigV1<*>>
) : Loads<CompositeModel, Renderable>, SerializableExtensionData() {
    override fun load(dependency: Renderable): CompositeModel {
        val rootVisual = createItemVisual(
            dependency = dependency,
            location = dependency.bukkitLocation,
            itemStack = ItemStack(Material.AIR),
            interpolationDuration = TRAIN_ROOT_INTERPOLATION_TICKS,
            teleportDuration = TRAIN_ROOT_TELEPORT_TICKS
        )
        return CompositeModel(
            world = dependency.world,
            models = attachedModels.map { it.load(dependency) },
            rootVisual = rootVisual
        )
    }
}

@Serializable
abstract class ModelConfigV1<T : AbstractModel>(
    val offset: Vector3d = Vector3d(),
    val rotation: Vector3d = Vector3d(),
    val attachedModels: Collection<ModelConfigV1<*>> = listOf()
) : Loads<T, Renderable> {
    val modelState
        get() = ModelState(
            CompactTransform(
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
) : ModelConfigV1<@Contextual DisplayModel>() {
    override fun load(dependency: Renderable): DisplayModel {
        val itemStack = item.load(Unit)
        val visual = createItemVisual(
            dependency,
            dependency.bukkitLocation,
            itemStack,
            TRAIN_DISPLAY_INTERPOLATION_TICKS,
            TRAIN_DISPLAY_TELEPORT_TICKS
        )
        return DisplayModel(
            world = dependency.world,
            state = modelState,
            attachedModels = attachedModels.map { it.load(dependency) },
            animation = animations.mapValues { it.value.load(dependency) },
            visual = visual,
            initialItemStack = itemStack
        )
    }
}

@Serializable
class LodDisplayModelConfigV1(
    val loItem: ItemStackConfig,
    val hiItem: ItemStackConfig,
    val animations: Map<String, AnimationConfig>
) : ModelConfigV1<@Contextual LodDisplayModel>() {
    override fun load(dependency: Renderable): LodDisplayModel {
        val loVisual = createItemVisual(
            dependency,
            dependency.bukkitLocation,
            loItem.load(Unit),
            TRAIN_DISPLAY_INTERPOLATION_TICKS,
            TRAIN_DISPLAY_TELEPORT_TICKS
        )
        val hiVisual = createItemVisual(
            dependency,
            dependency.bukkitLocation,
            hiItem.load(Unit),
            TRAIN_DISPLAY_INTERPOLATION_TICKS,
            TRAIN_DISPLAY_TELEPORT_TICKS
        )
        return LodDisplayModel(
            world = dependency.world,
            state = modelState,
            attachedModels = attachedModels.map { it.load(dependency) },
            animation = animations.mapValues { it.value.load(dependency) },
            loVisual = loVisual,
            hiVisual = hiVisual
        )
    }
}

@Serializable
class PlatformModelConfigV1(
    val animations: Map<String, AnimationConfig>
) : ModelConfigV1<@Contextual PlatformModel>() {
    override fun load(dependency: Renderable): PlatformModel {
        return PlatformModel(
            world = dependency.world,
            state = modelState,
            attachedModels = attachedModels.map { it.load(dependency) },
            animation = animations.mapValues { it.value.load(dependency) },
            initialLocation = dependency.bukkitLocation,
            platformConfig = PartPlatformConfig()
        )
    }
}

private fun createItemVisual(
    dependency: Renderable,
    location: Location,
    itemStack: ItemStack,
    interpolationDuration: Int,
    teleportDuration: Int
): DisplayVisual {
    val world = dependency.world.bukkitWorldData.bukkitWorld
    return BukkitPlugin.INSTANCE.displayRenderBackend.create(
        world,
        DisplayVisualDescriptor(
            location = location,
            content = DisplayVisualContent.Item(itemStack),
            transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(1f), Quaternionf()),
            viewRange = displayViewRangeForDistance(
                BukkitPlugin.INSTANCE.javaRailwaysConfig.displayRenderer.playerDistance.maxBlocks
            ),
            interpolationDuration = interpolationDuration,
            teleportDuration = teleportDuration
        )
    )
}
