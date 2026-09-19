package work.art1st.javarailways.bukkit.api.infrastructure;

import java.util.UUID;

/**
 * Authoritative existence check for one stable infrastructure {@code typeId}.
 *
 * <p>Called synchronously on the lease service caller's thread. Implementations must be
 * thread-safe, deterministic, non-blocking, and must not access Bukkit entity or region state.</p>
 */
@FunctionalInterface
public interface InfrastructureResourceProvider {
    boolean exists(UUID worldId, String resourceId);
}
