package work.art1st.javarailways.bukkit.tool

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent

internal class RailWandEditMode(private val wand: RailWand) {
    fun handle(player: Player, state: WandPlayerState, event: PlayerInteractEvent) {
        when {
            event.action == Action.RIGHT_CLICK_BLOCK || event.action == Action.RIGHT_CLICK_AIR -> {
                val anchor = wand.findNearestAnchor(player, 3.0)
                if (anchor != null) {
                    state.selectedRails.clear()
                    state.selectedAnchors.clear()
                    state.selectedAnchors.add(anchor.id)
                    wand.updateAnchorDisplay(player)
                    wand.editAnchor(player, anchor, state)
                    return
                }

                wand.findLookedAtRailAsync(player) { rail ->
                    if (rail == null) {
                        player.sendActionBar(Component.text("没有找到所指轨道", NamedTextColor.GRAY))
                    } else {
                        wand.editRail(player, rail, state)
                    }
                }
            }

            event.action == Action.LEFT_CLICK_BLOCK || event.action == Action.LEFT_CLICK_AIR -> {
                val anchor = wand.findNearestAnchor(player, 3.0)
                if (anchor != null) {
                    state.selectedRails.clear()
                    if (player.isSneaking) {
                        if (anchor.id !in state.selectedAnchors) state.selectedAnchors.add(anchor.id)
                    } else {
                        state.selectedAnchors.clear()
                        state.selectedAnchors.add(anchor.id)
                    }
                    wand.updateAnchorDisplay(player)
                    player.sendActionBar(Component.text("已选择锚点 ${anchor.id}", NamedTextColor.YELLOW))
                    return
                }

                wand.findLookedAtRailAsync(player) { rail ->
                    if (rail == null) {
                        wand.clearAnchorSelection(player, state)
                        player.sendActionBar(Component.text("没有找到所指轨道", NamedTextColor.GRAY))
                    } else {
                        wand.selectRail(player, rail, state)
                    }
                }
            }
        }
    }
}
