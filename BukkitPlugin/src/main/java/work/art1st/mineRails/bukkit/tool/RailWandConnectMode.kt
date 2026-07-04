package work.art1st.mineRails.bukkit.tool

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
                    if (wand.connectSelectedAnchors(player, state) != null) {
                        state.selectedAnchors.clear()
                        wand.updateAnchorDisplay(player)
                    }
                } else {
                    player.sendActionBar(Component.text("请选择 $expectedCount 个锚点 (当前: ${state.selectedAnchors.size})", NamedTextColor.RED))
                }
            }
            // 左键锚点 → 选择
            event.action == Action.LEFT_CLICK_BLOCK || event.action == Action.LEFT_CLICK_AIR -> {
                val clickedAnchor = wand.findNearestAnchor(player, 3.0)
                if (clickedAnchor != null) {
                    if (player.isSneaking) {
                        if (clickedAnchor.id !in state.selectedAnchors) {
                            state.selectedAnchors.add(clickedAnchor.id)
                            player.sendActionBar(Component.text("已选择锚点 ${clickedAnchor.id} (${state.selectedAnchors.size})", NamedTextColor.YELLOW))
                        }
                    } else {
                        state.selectedAnchors.clear()
                        state.selectedAnchors.add(clickedAnchor.id)
                        player.sendActionBar(Component.text("已选择锚点 ${clickedAnchor.id}", NamedTextColor.YELLOW))
                    }
                    wand.updateAnchorDisplay(player)
                } else {
                    wand.clearAnchorSelection(player, state)
                }
            }
        }
    }
}
