package work.art1st.mineRails.bukkit.tool

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEvent

internal class RailWandDecorateMode {
    fun handle(player: Player, state: WandPlayerState, event: PlayerInteractEvent) {
        player.sendActionBar(Component.text("装饰模式 - 功能尚未实现", NamedTextColor.YELLOW))
    }
}
