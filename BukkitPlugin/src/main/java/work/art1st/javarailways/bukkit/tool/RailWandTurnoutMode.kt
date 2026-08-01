package work.art1st.javarailways.bukkit.tool

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent

internal class RailWandTurnoutMode(private val wand: RailWand) {
    fun handle(player: Player, state: WandPlayerState, event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.RIGHT_CLICK_AIR) return
        val anchor = wand.findLookedAtAnchor(player, 128.0, 1.0)
            ?: wand.findNearestAnchor(player, 3.0)
        if (anchor == null) {
            player.sendActionBar(Component.text("请指向要创建道岔的锚点", NamedTextColor.RED))
            return
        }
        wand.createTurnoutAtAnchor(player, state, anchor)
    }
}
