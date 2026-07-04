package work.art1st.mineRails.bukkit.tool

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent

internal class RailWandDeleteMode(private val wand: RailWand) {
    fun handle(player: Player, state: WandPlayerState, event: PlayerInteractEvent) {
        when {
            event.action == Action.LEFT_CLICK_BLOCK || event.action == Action.LEFT_CLICK_AIR -> {
                // 检查是否点击了锚点
                val clickedAnchor = wand.findNearestAnchor(player, 3.0)
                if (clickedAnchor != null) {
                    if (state.pendingDeleteAnchor == clickedAnchor.id) {
                        // 二次确认 → 删除
                        wand.deleteAnchor(player, clickedAnchor.id, state)
                        state.pendingDeleteAnchor = null
                    } else {
                        // 第一次左键 → 进入待删除状态
                        state.pendingDeleteAnchor = clickedAnchor.id
                        state.pendingDeleteRail = null
                        player.sendActionBar(Component.text("再次左键锚点 ${clickedAnchor.id} 以确认删除", NamedTextColor.RED))
                    }
                    return
                }

                // 退出待删除状态
                state.pendingDeleteAnchor = null
                state.pendingDeleteRail = null
            }
        }
    }
}
