package work.art1st.javarailways.bukkit.api.signal;

import org.bukkit.plugin.Plugin;
import work.art1st.javarailways.simpleato.api.signal.SignalEpochSource;

import java.util.Collection;
import java.util.UUID;

/**
 * Installs independently implemented signal sources into JavaRailways worlds.
 *
 * <p>Each call replaces the complete source set owned by {@code pluginOwner} in one exact live
 * world. JavaRailways validates that complete owner set together with every other plugin's set and
 * swaps it between railway epochs; an invalid replacement leaves the previous set installed.
 * Source callbacks run synchronously on the railway coordinator and therefore must not call this
 * service, Bukkit, blocking I/O, or asynchronously defer safety work.</p>
 *
 * <p>Removing a source ID is allowed only after that source has explicitly published a newer empty
 * restriction snapshot for every retained train stream. This prevents plugin unload from silently
 * widening a train's authority. A planned disable must call {@link #unregisterOwner(Plugin)} while
 * the plugin is still enabled. If Bukkit forcibly disables a plugin that still owns sources, the
 * host removes its callbacks and stalls every affected railway world fail-closed.</p>
 *
 * <p>World unload discards the world's registrations. A later world runtime with the same UUID is
 * a new binding and plugins must install their sources again.</p>
 */
public interface SignalEpochSourceService {
    /**
     * Atomically replaces this plugin's complete source set for one live world.
     *
     * <p>An empty collection removes the owner from that world subject to the explicit-withdrawal
     * rule described on this interface.</p>
     *
     * @throws IllegalStateException if the plugin/world/service is unavailable or a removed source
     *                               still has a non-empty retained restriction stream
     * @throws IllegalArgumentException if the combined source definitions are invalid
     */
    void replaceSources(
        Plugin pluginOwner,
        UUID worldId,
        Collection<SignalEpochSource> sources
    );

    /**
     * Removes this plugin's source sets from every live world after verifying explicit withdrawal.
     * This operation is idempotent when the owner has no installed sources.
     *
     * @throws IllegalStateException if the service is closed, the plugin is disabled, or any source
     *                               still has a non-empty retained restriction stream
     */
    void unregisterOwner(Plugin pluginOwner);
}
