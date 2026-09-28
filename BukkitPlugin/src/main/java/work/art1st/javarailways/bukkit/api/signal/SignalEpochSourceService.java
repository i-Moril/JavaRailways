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
 * widening a train's authority. An empty replacement removes only this owner's signal-epoch
 * sources; inspection and route-planning registrations owned by the same plugin are preserved.
 * Complete owner removal is intentionally not exposed here: a
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
     * <p>An empty replacement removes only the signal-epoch source set after the same retained
     * restriction check. It does not unregister any inspection, route-planning or infrastructure
     * state owned by the plugin.</p>
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
     * Atomically commits plugin state and replaces this plugin's complete source set.
     *
     * <p>The host first copies and validates the complete candidate source union, checks removed
     * source restriction streams, and compiles the candidate monitored-area runtime. Only then is
     * {@code commitAction} invoked. If validation or compilation fails, the callback is not run. If
     * the callback throws, the old source set and runtime remain installed. After a successful
     * callback the already compiled source state is installed by an infallible reference swap.</p>
     *
     * <p>Validation and monitored-area compilation may be linear or sorting work in the complete
     * installed area set, so an online editor must call this method from its asynchronous
     * preparation worker rather than a Bukkit/Paper owner thread. The operation does not acquire
     * JavaRailways' cross-world simulation lock. An already-running epoch keeps the immutable old
     * source snapshot; the next epoch sees the new snapshot and establishes a fresh baseline.</p>
     *
     * <p>The callback is the final, bounded commit boundary. It may exchange already-prepared
     * immutable state and commit an already-staged atomic file replacement, but it must not encode
     * or validate a full document, perform graph/index construction, call Bukkit, re-enter this
     * service, or wait for asynchronous work. Any attempted service re-entry from the callback is
     * rejected before it can change another source category.</p>
     *
     * @throws IllegalStateException if the plugin/world/service is unavailable or a removed source
     *                               still has a non-empty retained restriction stream
     * @throws IllegalArgumentException if the combined source definitions are invalid
     */
    void replaceSourcesAtomically(
        Plugin pluginOwner,
        UUID worldId,
        Collection<SignalEpochSource> sources,
        Runnable commitAction
    );
}
