package work.art1st.mineRails.bukkit.tool

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent

internal class RailWandEditMode(private val wand: RailWand) {
    fun handle(player: Player, state: WandPlayerState, event: PlayerInteractEvent) {
        when {
            // 右键锚点 → 编辑单锚点
            event.action == Action.RIGHT_CLICK_BLOCK || event.action == Action.RIGHT_CLICK_AIR -> {
                val clickedAnchor = wand.findNearestAnchor(player, 3.0)
                if (clickedAnchor != null) {
                    state.selectedAnchors.clear()
                    state.selectedAnchors.add(clickedAnchor.id)
                    wand.updateAnchorDisplay(player)
                    wand.editAnchor(player, clickedAnchor, state)
                } else {
                    // 右键空气 → 编辑已选择的锚点列表
                    if (state.selectedAnchors.isNotEmpty()) {
                        wand.editSelectedAnchors(player, state)
                    } else {
                        player.sendActionBar(Component.text("请选择锚点后右键编辑", NamedTextColor.GRAY))
                    }
                }
            }
            // 左键锚点 → 选择
            event.action == Action.LEFT_CLICK_BLOCK || event.action == Action.LEFT_CLICK_AIR -> {
                val clickedAnchor = wand.findNearestAnchor(player, 3.0)
                if (clickedAnchor != null) {
                    if (player.isSneaking) {
                        if (clickedAnchor.id !in state.selectedAnchors) {
                            state.selectedAnchors.add(clickedAnchor.id)
                        }
                    } else {
                        state.selectedAnchors.clear()
                        state.selectedAnchors.add(clickedAnchor.id)
                    }
                    wand.updateAnchorDisplay(player)
                    player.sendActionBar(Component.text("已选择锚点 ${clickedAnchor.id}", NamedTextColor.YELLOW))
                } else {
                    wand.clearAnchorSelection(player, state)
                }
            }
        }
    }
}
