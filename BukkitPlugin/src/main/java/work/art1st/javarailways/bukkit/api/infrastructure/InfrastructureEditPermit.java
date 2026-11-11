package work.art1st.javarailways.bukkit.api.infrastructure;

import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner;

/**
 * Held exclusive permission to mutate one exact topology edit impact.
 *
 * <p>The caller must keep this permit open for the complete topology and persistence transaction.
 * Closing it is idempotent. A permit is world-local and must not be persisted.</p>
 */
public interface InfrastructureEditPermit extends AutoCloseable {
    InfrastructureOwner owner();

    /** Executes one synchronous mutation while authenticating this held permit. */
    <T> T execute(java.util.function.Supplier<T> action);

    @Override
    void close();
}
