package work.art1st.javarailways.bukkit.render.model

import work.art1st.javarailways.core.api.util.DestructionSteps
import org.bukkit.Location
import org.bukkit.entity.Interaction
import work.art1st.javarailways.bukkit.config.model.PartInteractionConfig
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.extension.bukkitWorldData
import work.art1st.javarailways.bukkit.render.Animation
import java.util.concurrent.atomic.AtomicBoolean
import work.art1st.javarailways.core.api.physics.World

class InteractionModel internal constructor(
    world: World,
    state: ModelState,
    attachedModels: Collection<AbstractModel>,
    animation: Map<String, Animation>,
    private val interactionConfig: PartInteractionConfig,
    private val initialLocation: Location
) : EntityModel(world, state, attachedModels, animation, null) {
    private val spawnScheduled = AtomicBoolean(false)
    @Volatile
    private var retired = false

    override fun updateState(
        parentRelativeTransform: CompactTransform,
        rootTransform: CompactTransform,
        rootVelocity: org.joml.Vector3dc,
        transformDirty: Boolean
    ) {
        super.updateState(parentRelativeTransform, rootTransform, rootVelocity, transformDirty)
        if (entity == null) {
            spawnEntity()
            entity?.let { syncEntity(it, rootVelocity) }
        }
    }

    override fun spawnEntity() {
        if (retired) return
        val bukkitWorld = world.bukkitWorldData.bukkitWorld
        val position = state.worldTransform.position
        val spawnLocation = Location(
            bukkitWorld,
            position.x(),
            position.y(),
            position.z(),
            initialLocation.yaw,
            initialLocation.pitch
        )
        if (!spawnScheduled.compareAndSet(false, true)) return
        BukkitPlugin.INSTANCE.platformDispatcher.runAt(spawnLocation) {
            try {
                synchronized(this@InteractionModel) {
                    if (retired || entity != null) return@synchronized
                    val interaction = bukkitWorld.spawn(spawnLocation, Interaction::class.java)
                    interaction.isPersistent = false
                    interaction.setInteractionWidth(interactionConfig.width.toFloat())
                    interaction.setInteractionHeight(interactionConfig.height.toFloat())
                    entity = interaction
                }
            } finally {
                spawnScheduled.set(false)
            }
        }
    }

    override fun collectDestruction(steps: DestructionSteps) {
        retired = true
        super.collectDestruction(steps)
    }
}
