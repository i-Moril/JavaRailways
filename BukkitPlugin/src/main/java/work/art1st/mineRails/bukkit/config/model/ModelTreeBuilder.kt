package work.art1st.mineRails.bukkit.config.model

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.CustomModelData
import net.kyori.adventure.key.Key
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.joml.Quaterniond
import org.joml.Vector3d
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
import work.art1st.mineRails.bukkit.render.model.AbstractModel
import work.art1st.mineRails.bukkit.render.model.CompactTransform
import work.art1st.mineRails.bukkit.render.model.CompositeModel
import work.art1st.mineRails.bukkit.render.model.DisplayModel
import work.art1st.mineRails.bukkit.render.model.ModelState
import work.art1st.mineRails.bukkit.render.model.PlatformModel
import work.art1st.mineRails.bukkit.render.model.configureTrainDisplaySmoothing
import work.art1st.mineRails.core.api.physics.World

/**
 * Dummy model with no entity - purely a transform grouping node.
 */
class DummyModel internal constructor(
    world: World,
    state: ModelState,
    attachedModels: Collection<AbstractModel>,
    animation: Map<String, work.art1st.mineRails.bukkit.render.Animation>
) : AbstractModel(world, state, attachedModels, animation)

/**
 * Builds the AbstractModel tree from configuration definitions.
 *
 * Converts PartDefinition/CarDefinition into a renderable model tree
 * consisting of DisplayModel (for ITEM_DISPLAY parts), PlatformModel
 * (for INTERACT parts/seat attachment points), and DummyModel (for
 * transform grouping nodes).
 */
class ModelTreeBuilder(
    private val world: World,
    private val partRegistry: PartRegistry
) {
    private val bukkitWorld
        get() = world.bukkitWorldData.bukkitWorld

    // Root location used as spawn anchor for display entities,
    // ensuring they start near the player instead of at (0,0,0).
    private var rootLocation: Location = Location(null, 0.0, 0.0, 0.0)

    /**
     * Build the car body model tree at the given root location.
     * Bogies are built separately so they can follow their own rail locations.
     */
    fun buildCarBody(
        carDef: CarDefinition,
        rootLocation: Location
    ): CompositeModel {
        this.rootLocation = rootLocation

        val bodyModels = buildPartList(carDef.bodyParts, carDef.namespace)

        val rootEntity = bukkitWorld.spawn(rootLocation, ItemDisplay::class.java)
        rootEntity.isPersistent = false
        rootEntity.isVisibleByDefault = false
        rootEntity.configureTrainDisplaySmoothing()

        return CompositeModel(
            world = world,
            models = bodyModels,
            rootEntity = rootEntity
        )
    }

    /**
     * Build a bogie model tree at the given root location.
     * The root transform is expected to be the live bogie transform, not the car transform.
     */
    fun buildBogie(
        bogieDef: BogieDefinition,
        namespace: String,
        rootLocation: Location
    ): CompositeModel {
        this.rootLocation = rootLocation

        val bogieModel = buildBogieGroup(bogieDef, namespace, includeLongitudinalOffset = false)

        val rootEntity = bukkitWorld.spawn(rootLocation, ItemDisplay::class.java)
        rootEntity.isPersistent = false
        rootEntity.isVisibleByDefault = false
        rootEntity.configureTrainDisplaySmoothing()

        return CompositeModel(
            world = world,
            models = listOf(bogieModel),
            rootEntity = rootEntity
        )
    }

    /**
     * Build a bogie group node - creates a DummyModel with bogie parts as children.
     * When a bogie is bound directly to a live bogie location, its car-relative
     * longitudinal offset must not be applied again.
     */
    private fun buildBogieGroup(
        bogieDef: BogieDefinition,
        namespace: String,
        includeLongitudinalOffset: Boolean
    ): DummyModel {
        val bogieOffset = bogieDef.offset
        val modelState = ModelState(
            CompactTransform(
                position = Vector3d(
                    bogieOffset.x,
                    bogieOffset.y,
                    if (includeLongitudinalOffset) bogieOffset.z else 0.0
                ),
                rotation = Quaterniond()
            )
        )

        // Combine bogie parts and wheel parts
        val allBogiePartRefs = bogieDef.parts + bogieDef.wheelParts
        val children = buildPartList(allBogiePartRefs, namespace)

        return DummyModel(
            world = world,
            state = modelState,
            attachedModels = children,
            animation = emptyMap()
        )
    }

    /**
     * Build models from a list of part references.
     */
    private fun buildPartList(entries: List<PartRefEntry>, namespace: String): List<AbstractModel> {
        return entries.map { entry -> buildPart(entry, namespace) }
    }

    /**
     * Build a single part model tree from a part reference entry.
     */
    private fun buildPart(entry: PartRefEntry, namespace: String): AbstractModel {
        val def: PartDefinition
        val entryOffset: Vec3Config
        val entryRotation: Vec3Config
        val childNamespace: String

        if (entry.inline != null) {
            // Inline definition: use the inline part directly
            def = entry.inline
            entryOffset = entry.offset
            entryRotation = entry.rotation
            childNamespace = def.namespace
        } else {
            // Import or legacy reference: resolve from registry
            val resolved = partRegistry.resolve(entry.name, namespace)
            def = resolved.resolved
            entryOffset = entry.offset
            entryRotation = entry.rotation
            childNamespace = def.namespace
        }

        // Merge: part's own offset + reference entry's offset override
        val mergedOffset = Vec3Config(
            x = def.offset.x + entryOffset.x,
            y = def.offset.y + entryOffset.y,
            z = def.offset.z + entryOffset.z
        )
        val mergedRotation = Vec3Config(
            x = def.rotation.x + entryRotation.x,
            y = def.rotation.y + entryRotation.y,
            z = def.rotation.z + entryRotation.z
        )

        val position = Vector3d(mergedOffset.x, mergedOffset.y, mergedOffset.z)
        val rotation = Quaterniond().rotateXYZ(
            Math.toRadians(mergedRotation.x),
            Math.toRadians(mergedRotation.y),
            Math.toRadians(mergedRotation.z)
        )

        val scale = def.item?.scale ?: def.scale
        val scaleVec = if (scale != null) Vector3d(scale.x, scale.y, scale.z) else Vector3d(1.0, 1.0, 1.0)

        val modelState = ModelState(
            CompactTransform(position = position, rotation = rotation, scale = scaleVec)
        )

        val children = buildPartList(def.parts, childNamespace)
        // Spawn entities at the car's root location so they start near the player,
        // not at world origin. DisplayModel.updateState will teleport them to exact positions.
        val spawnLoc = rootLocation.clone()

        return when (def.type) {
            PartType.ITEM_DISPLAY -> {
                val itemConfig = def.item
                    ?: throw IllegalArgumentException("ITEM_DISPLAY part '${def.name}' missing item config")
                val entity = spawnItemDisplay(spawnLoc, itemConfig)
                DisplayModel(world = world, state = modelState, attachedModels = children, animation = emptyMap(), entity = entity)
            }
            PartType.INTERACT, PartType.SEAT -> {
                PlatformModel(world = world, state = modelState, attachedModels = children, animation = emptyMap())
            }
            PartType.DUMMY -> {
                DummyModel(world = world, state = modelState, attachedModels = children, animation = emptyMap())
            }
        }
    }

    /**
     * Spawn an ItemDisplay entity with the configured item and CustomModelData.
     */
    private fun spawnItemDisplay(location: Location, itemConfig: PartItemConfig): ItemDisplay {
        val material = try {
            Material.valueOf(itemConfig.type.uppercase())
        } catch (e: Exception) {
            Material.STONE
        }

        val itemStack = ItemStack(material)

        // Prefer item_model component (1.21.4+), fall back to CMD
        if (itemConfig.itemModel != null) {
            // item_model resolves to assets/<namespace>/items/<path>.json
            itemStack.setData(DataComponentTypes.ITEM_MODEL,
                Key.key("minecraft:${itemConfig.itemModel}"))
        }
        if (itemConfig.customModelData != null) {
            itemStack.setData(DataComponentTypes.CUSTOM_MODEL_DATA,
                CustomModelData.customModelData().addFloat(itemConfig.customModelData.toFloat()).build())
        }

        val entity = bukkitWorld.spawn(location, ItemDisplay::class.java)
        entity.isPersistent = false
        entity.configureTrainDisplaySmoothing()
        entity.setItemStack(itemStack)
        // Use NONE transform: model is rendered in its natural orientation,
        // positioned solely by our Transformation rotation.
        entity.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.NONE)

        return entity
    }
}
