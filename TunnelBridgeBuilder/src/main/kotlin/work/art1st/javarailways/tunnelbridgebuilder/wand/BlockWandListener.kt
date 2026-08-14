package work.art1st.javarailways.tunnelbridgebuilder.wand

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import work.art1st.javarailways.tunnelbridgebuilder.rail.RailSweepService

class BlockWandListener(
    private val wandItem: BlockWandItem,
    private val menu: BlockWandMenu,
    private val railSweepService: RailSweepService
) : Listener {
    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val wand = event.player.inventory.itemInMainHand
        if (!wandItem.isWand(wand)) return

        event.isCancelled = true
        if (event.player.isSneaking) {
            menu.open(event.player, wand)
            return
        }
        val state = wandItem.load(wand) ?: return
        railSweepService.replaceLookedAtRail(event.player, state)
    }
}
