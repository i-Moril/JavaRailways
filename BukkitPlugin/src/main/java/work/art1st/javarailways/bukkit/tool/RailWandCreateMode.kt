package work.art1st.javarailways.bukkit.tool

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent

internal class RailWandCreateMode(private val wand: RailWand) {
    fun handle(player: Player, state: WandPlayerState, event: PlayerInteractEvent) {
        when {
            // 右键 → 创建锚点
            event.action == Action.RIGHT_CLICK_BLOCK || event.action == Action.RIGHT_CLICK_AIR -> {
                wand.createAnchorAtPlayer(player, state, event)
            }
            // 左键锚点 → 选择
            event.action == Action.LEFT_CLICK_BLOCK || event.action == Action.LEFT_CLICK_AIR -> {
                val clickedAnchor = wand.findTargetAnchor(player)
                if (clickedAnchor != null) {
                    if (clickedAnchor.id !in state.selectedAnchors) {
                        if (player.isSneaking) {
                            state.selectedAnchors.add(clickedAnchor.id)
                        } else {
                            state.selectedAnchors.clear()
                            state.selectedAnchors.add(clickedAnchor.id)
                        }
                        player.sendActionBar(RailWandI18n.component(
                            player,
                            "message.anchor_selected",
                            RailWandI18n.anchorName(player, clickedAnchor.id)
                        ).color(NamedTextColor.YELLOW))
                        wand.updateAnchorDisplay(player)
                    }
                } else {
                    wand.clearAnchorSelection(player, state)
                }
            }
        }
    }
}
