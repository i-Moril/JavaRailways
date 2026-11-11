package work.art1st.javarailways.bukkit.tool

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent

internal class RailWandGradeSmoothMode(private val wand: RailWand) {
    fun handle(player: Player, state: WandPlayerState, event: PlayerInteractEvent) {
        when (event.action) {
            Action.LEFT_CLICK_BLOCK, Action.LEFT_CLICK_AIR -> {
                val anchor = wand.findTargetAnchor(player)
                if (anchor == null) {
                    wand.clearAnchorSelection(player, state)
                    return
                }
                if (!player.isSneaking) state.selectedAnchors.clear()
                if (anchor.id !in state.selectedAnchors) state.selectedAnchors += anchor.id
                player.sendActionBar(RailWandI18n.component(
                    player,
                    "message.grade_anchor_selected",
                    RailWandI18n.anchorName(player, anchor.id),
                    state.selectedAnchors.size
                ).color(NamedTextColor.YELLOW))
                wand.updateAnchorDisplay(player)
            }
            Action.RIGHT_CLICK_BLOCK, Action.RIGHT_CLICK_AIR -> wand.smoothSelectedGrades(player, state)
            else -> Unit
        }
    }
}
