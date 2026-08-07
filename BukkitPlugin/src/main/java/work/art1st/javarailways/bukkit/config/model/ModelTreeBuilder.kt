package work.art1st.javarailways.bukkit.config.model

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.CustomModelData
import net.kyori.adventure.key.Key
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaterniond
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.extension.bukkitWorldData
import work.art1st.javarailways.bukkit.render.model.AbstractModel
import work.art1st.javarailways.bukkit.render.model.CompactTransform
import work.art1st.javarailways.bukkit.render.model.CompositeModel
import work.art1st.javarailways.bukkit.render.model.DisplayModel
import work.art1st.javarailways.bukkit.render.model.InteractionModel
import work.art1st.javarailways.bukkit.render.model.ModelState
import work.art1st.javarailways.bukkit.render.model.PlatformModel
import work.art1st.javarailways.bukkit.render.model.SeatModel
import work.art1st.javarailways.bukkit.render.backend.DisplayVisual
import work.art1st.javarailways.bukkit.render.backend.DisplayVisualContent
import work.art1st.javarailways.bukkit.render.backend.DisplayVisualDescriptor
import work.art1st.javarailways.bukkit.render.backend.displayViewRangeForDistance
import work.art1st.javarailways.bukkit.render.model.TRAIN_DISPLAY_INTERPOLATION_TICKS
import work.art1st.javarailways.bukkit.render.model.TRAIN_DISPLAY_TELEPORT_TICKS
import work.art1st.javarailways.bukkit.render.model.TRAIN_ROOT_INTERPOLATION_TICKS
import work.art1st.javarailways.bukkit.render.model.TRAIN_ROOT_TELEPORT_TICKS
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.train.Car

/**
 * Dummy model with no entity - purely a transform grouping node.
 */
class DummyModel internal constructor(
    world: World,
    state: ModelState,
    attachedModels: Collection<AbstractModel>,
    animation: Map<String, work.art1st.javarailways.bukkit.render.Animation>
) : AbstractModel(world, state, attachedModels, animation)

/**
 * Builds the AbstractModel tree from configuration definitions.
 *
 * Converts PartDefinition/CarDefinition into a renderable model tree
 * consisting of DisplayModel (for ITEM_DISPLAY parts), InteractionModel
 * (for INTERACT hitboxes), SeatModel (for invisible minecart seats),
 * PlatformModel (for collision platforms), and DummyModel (for transform
 * grouping nodes).
 */
class ModelTreeBuilder(
    private val world: World,
    private val partRegistry: PartResolver
) {
    private val bukkitWorld
        get() = world.bukkitWorldData.bukkitWorld

    // Root location used as spawn anchor for display entities,
    // ensuring they start near the player instead of at (0,0,0).
    private var rootLocation: Location = Location(null, 0.0, 0.0, 0.0)
    private var ownerCar: Car? = null

    /**
     * Build the car body model tree at the given root location.
     * Bogies are built separately so they can follow their own rail locations.
     */
    fun buildCarBody(
        carDef: CarDefinition,
        rootLocation: Location,
        ownerCar: Car? = null
    ): CompositeModel {
        this.rootLocation = rootLocation
        this.ownerCar = ownerCar

        val bodyModels = buildPartList(carDef.bodyParts, carDef.namespace)

        val rootVisual = createRootVisual(rootLocation)

        return CompositeModel(
            world = world,
            models = bodyModels,
            rootVisual = rootVisual
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
        this.ownerCar = null

        val bogieModel = buildBogieGroup(bogieDef, namespace, includeLongitudinalOffset = false)

        val rootVisual = createRootVisual(rootLocation)

        return CompositeModel(
            world = world,
            models = listOf(bogieModel),
            rootVisual = rootVisual
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
            // Imported reference: resolve from the package registry.
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

        val scale = def.item?.scale
        val scaleVec = if (scale != null) Vector3d(scale.x, scale.y, scale.z) else Vector3d(1.0, 1.0, 1.0)

        val modelState = ModelState(
            CompactTransform(position = position, rotation = rotation, scale = scaleVec)
        )

        val children = buildPartList(def.parts, childNamespace)
        val animations = def.animations.toRuntimeAnimationMap()
        // Spawn entities at the car's root location so they start near the player,
        // not at world origin. DisplayModel.updateState will teleport them to exact positions.
        val spawnLoc = rootLocation.clone()

        return when (def.type) {
            PartType.ITEM_DISPLAY -> {
                val itemConfig = def.item
                    ?: throw IllegalArgumentException("ITEM_DISPLAY part '${def.name}' missing item config")
                val itemStack = buildItemStack(itemConfig)
                val visual = createItemDisplayVisual(spawnLoc, itemStack)
                DisplayModel(
                    world = world,
                    state = modelState,
                    attachedModels = children,
                    animation = animations,
                    visual = visual,
                    initialItemStack = itemStack
                )
            }
            PartType.INTERACT -> {
                InteractionModel(world = world, state = modelState, attachedModels = children, animation = animations, interactionConfig = def.interaction, initialLocation = spawnLoc)
            }
            PartType.SEAT -> {
                SeatModel(
                    world = world,
                    state = modelState,
                    attachedModels = children,
                    animation = animations,
                    initialLocation = spawnLoc,
                    ownerCar = ownerCar,
                    driverSeat = def.interaction.driver,
                    controlEnd = def.interaction.controlEnd
                )
            }
            PartType.PLATFORM -> {
                PlatformModel(world = world, state = modelState, attachedModels = children, animation = animations, initialLocation = spawnLoc, platformConfig = def.platform)
            }
            PartType.DUMMY -> {
                DummyModel(world = world, state = modelState, attachedModels = children, animation = animations)
            }
        }
    }

    private fun buildItemStack(itemConfig: PartItemConfig): ItemStack {
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

        return itemStack
    }

    private fun createRootVisual(location: Location): DisplayVisual = createDisplayVisual(
        location = location,
        itemStack = ItemStack(Material.AIR),
        interpolationDuration = TRAIN_ROOT_INTERPOLATION_TICKS,
        teleportDuration = TRAIN_ROOT_TELEPORT_TICKS
    )

    private fun createItemDisplayVisual(location: Location, itemStack: ItemStack): DisplayVisual = createDisplayVisual(
        location = location,
        itemStack = itemStack,
        interpolationDuration = TRAIN_DISPLAY_INTERPOLATION_TICKS,
        teleportDuration = TRAIN_DISPLAY_TELEPORT_TICKS
    )

    private fun createDisplayVisual(
        location: Location,
        itemStack: ItemStack,
        interpolationDuration: Int,
        teleportDuration: Int
    ): DisplayVisual {
        return BukkitPlugin.INSTANCE.displayRenderBackend.create(
            bukkitWorld,
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
}
