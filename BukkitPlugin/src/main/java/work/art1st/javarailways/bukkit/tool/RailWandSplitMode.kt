package work.art1st.javarailways.bukkit.tool

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent

internal class RailWandSplitMode(private val wand: RailWand) {
    fun handle(player: Player, state: WandPlayerState, event: PlayerInteractEvent) {
        when (event.action) {
            Action.RIGHT_CLICK_BLOCK, Action.RIGHT_CLICK_AIR -> wand.selectOrConfirmSplit(player, state)
            Action.LEFT_CLICK_BLOCK, Action.LEFT_CLICK_AIR -> {
                val anchor = wand.findLookedAtAnchor(player, 64.0, 1.5)
                    ?: wand.findNearestAnchor(player, 3.0)
                if (anchor == null) {
                    player.sendActionBar(RailWandI18n.component(
                        player,
                        "message.split_merge_target_missing"
                    ).color(NamedTextColor.YELLOW))
                } else {
                    wand.mergeSplitAnchor(player, anchor)
                }
            }
            else -> Unit
        }
    }
}
