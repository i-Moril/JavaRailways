package work.art1st.javarailways.bukkit.api.infrastructure;

/**
 * Idempotent registration handle for one stable resource type provider.
 *
 * <p>Closing the handle refuses to remove the provider while any protected route still references
 * its resource type. The provider must remain enabled until a later close succeeds.</p>
 */
@FunctionalInterface
public interface InfrastructureResourceProviderRegistration extends AutoCloseable {
    /**
     * Removes this provider registration when its resource type is no longer active.
     *
     * @throws IllegalStateException if protected route resources still reference the type
     */
    @Override
    void close();
}
