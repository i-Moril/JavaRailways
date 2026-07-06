package work.art1st.mineRails.bukkit.render.model

import org.bukkit.Location
import org.bukkit.entity.Interaction
import work.art1st.mineRails.bukkit.config.model.PartInteractionConfig
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
import work.art1st.mineRails.bukkit.render.Animation
import work.art1st.mineRails.core.api.physics.World

class InteractionModel internal constructor(
    world: World,
    state: ModelState,
    attachedModels: Collection<AbstractModel>,
    animation: Map<String, Animation>,
    private val interactionConfig: PartInteractionConfig,
    private val initialLocation: Location
) : EntityModel(world, state, attachedModels, animation, null) {
    init {
        spawnEntity()
    }

    override fun spawnEntity() {
        val bukkitWorld = world.bukkitWorldData.bukkitWorld
        val interaction = bukkitWorld.spawn(initialLocation, Interaction::class.java)
        interaction.isPersistent = false
        interaction.setInteractionWidth(interactionConfig.width.toFloat())
        interaction.setInteractionHeight(interactionConfig.height.toFloat())
        entity = interaction
    }
}
