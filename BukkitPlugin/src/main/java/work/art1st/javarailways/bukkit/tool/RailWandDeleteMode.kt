package work.art1st.javarailways.bukkit.tool

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import java.util.UUID

internal class RailWandDeleteMode(private val wand: RailWand) {
    fun handle(player: Player, state: WandPlayerState, event: PlayerInteractEvent) {
        when {
            event.action == Action.LEFT_CLICK_BLOCK || event.action == Action.LEFT_CLICK_AIR -> {
                // 检查是否点击了锚点。轨道端点锚点在删除模式下代表其所属轨道。
                val clickedAnchor = wand.findNearestAnchor(player, 3.0)
                if (clickedAnchor != null) {
                    val endpointRailId = wand.railIdFromEndpointAnchor(clickedAnchor.id)
                    if (endpointRailId != null) {
                        handleRailDelete(player, state, endpointRailId)
                        return
                    }
                    if (state.pendingDeleteAnchor == clickedAnchor.id) {
                        wand.deleteAnchor(player, clickedAnchor.id, state)
                        state.pendingDeleteAnchor = null
                    } else {
                        state.pendingDeleteAnchor = clickedAnchor.id
                        state.pendingDeleteRail = null
                        player.sendActionBar(Component.text("再次左键锚点 ${clickedAnchor.id} 以确认删除", NamedTextColor.RED))
                    }
                    return
                }

                wand.findLookedAtRailAsync(player) { lookedRail ->
                    if (lookedRail != null) {
                        handleRailDelete(player, state, lookedRail.id)
                        wand.persistPlayerState(player, state)
                        return@findLookedAtRailAsync
                    }

                    state.pendingDeleteAnchor = null
                    state.pendingDeleteRail = null
                    wand.persistPlayerState(player, state)
                    player.sendActionBar(Component.text("未找到可删除的锚点或轨道", NamedTextColor.GRAY))
                }
            }
        }
    }

    private fun handleRailDelete(player: Player, state: WandPlayerState, railId: UUID) {
        val selectionId = wand.logicalRailSelectionId(player, railId)
        if (state.pendingDeleteRail == selectionId) {
            wand.deleteRail(player, selectionId, state)
            state.pendingDeleteRail = null
        } else {
            state.pendingDeleteRail = selectionId
            state.pendingDeleteAnchor = null
            player.sendActionBar(Component.text("再次左键该逻辑曲线以确认删除", NamedTextColor.RED))
        }
    }
}
