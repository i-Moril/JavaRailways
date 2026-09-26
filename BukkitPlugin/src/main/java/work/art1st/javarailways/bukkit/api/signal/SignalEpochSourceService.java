package work.art1st.javarailways.bukkit.api.signal;

import org.bukkit.plugin.Plugin;
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService;
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
 * widening a train's authority. Complete owner removal is intentionally not exposed here: a
 * planned disable must call {@link InfrastructureResourceService#unregisterOwner(Plugin)} while
 * the plugin is still enabled, so signal and infrastructure ownership are checked before either is
 * removed. If Bukkit forcibly disables a plugin, the host removes its callbacks and stalls only
 * worlds where that plugin still contributes a non-empty train restriction or owns active
 * infrastructure state.</p>
 *
 * <p>World unload discards the world's registrations. A later world runtime with the same UUID is
 * a new binding and plugins must install their sources again.</p>
 */
public interface SignalEpochSourceService {
    /**
     * Atomically replaces this plugin's complete source set for one live world.
     *
     * <p>The replacement must contain at least one source. Use
     * {@link InfrastructureResourceService#unregisterOwner(Plugin)} to remove the owner's complete
     * source set together with its infrastructure registrations.</p>
     *
     * @throws IllegalStateException if the plugin/world/service is unavailable or a removed source
     *                               still has a non-empty retained restriction stream
     * @throws IllegalArgumentException if the collection is empty or the combined source
     *                                  definitions are invalid
     */
    void replaceSources(
        Plugin pluginOwner,
        UUID worldId,
        Collection<SignalEpochSource> sources
    );
}
