package work.art1st.javarailways.bukkit.api.wand;

import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

/** Shared F-key routing for host and add-on Wands; does not own tool state or register listeners. */
public final class WandModeSwitch {
    private WandModeSwitch() {}

    /**
     * Handle a swap-hands event when the current main-hand item belongs to this Wand.
     * No sneaking is required. Offhand tools never take over the main-hand tool's shortcut.
     * Already-cancelled events are ignored; matching events are cancelled before invoking the callback,
     * so the tool stays in its hand even if the consumer refuses a mode change.
     *
     * <p>Call from the consumer's Bukkit event listener on the player's owning thread/region.
     * The predicate and callback run synchronously there; nothing is retained or scheduled.
     * Consumers own permission checks, mode selection, item persistence and invalidating stale edits.
     * Exceptions propagate to the listener caller; the matching event remains cancelled.</p>
     *
     * @return whether this Wand consumed the shortcut
     */
    public static boolean handle(PlayerSwapHandItemsEvent event, Predicate<ItemStack> matches,
                                 Consumer<Player> switchMode) {
        if (event.isCancelled()) return false;
        Player player = event.getPlayer();
        if (!matches.test(player.getInventory().getItemInMainHand())) return false;
        event.setCancelled(true);
        switchMode.accept(player);
        return true;
    }
}
