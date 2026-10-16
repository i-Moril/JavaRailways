package work.art1st.javarailways.bukkit.tool

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent

internal class RailWandConnectMode(private val wand: RailWand) {
    fun handle(player: Player, state: WandPlayerState, event: PlayerInteractEvent) {
        when {
            // 右键 → 如果选择列表已满，创建连接
            event.action == Action.RIGHT_CLICK_BLOCK || event.action == Action.RIGHT_CLICK_AIR -> {
                val expectedCount = state.parallelCount * 2  // 并行 × (起点+终点)
                if (state.selectedAnchors.size >= expectedCount) {
                    wand.connectSelectedAnchors(player, state)
                } else {
                    player.sendActionBar(RailWandI18n.component(
                        player,
                        "message.required_anchor_count",
                        expectedCount,
                        state.selectedAnchors.size
                    ).color(NamedTextColor.RED))
                }
            }
            // 左键锚点 → 选择
            event.action == Action.LEFT_CLICK_BLOCK || event.action == Action.LEFT_CLICK_AIR -> {
                val clickedAnchor = wand.findTargetAnchor(player)
                if (clickedAnchor != null) {
                    if (player.isSneaking) {
                        if (clickedAnchor.id !in state.selectedAnchors) {
                            state.selectedAnchors.add(clickedAnchor.id)
                            player.sendActionBar(RailWandI18n.component(
                                player,
                                "message.anchor_selected_count",
                                RailWandI18n.anchorName(player, clickedAnchor.id),
                                state.selectedAnchors.size
                            ).color(NamedTextColor.YELLOW))
                        }
                    } else {
                        state.selectedAnchors.clear()
                        state.selectedAnchors.add(clickedAnchor.id)
                        player.sendActionBar(RailWandI18n.component(
                            player,
                            "message.anchor_selected",
                            RailWandI18n.anchorName(player, clickedAnchor.id)
                        ).color(NamedTextColor.YELLOW))
                    }
                    wand.updateAnchorDisplay(player)
                } else {
                    wand.clearAnchorSelection(player, state)
                }
            }
        }
    }
}
