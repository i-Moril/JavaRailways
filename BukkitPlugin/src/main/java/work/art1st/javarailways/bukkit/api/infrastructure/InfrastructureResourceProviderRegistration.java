package work.art1st.javarailways.bukkit.api.infrastructure;

/** Idempotent registration handle for one stable resource type provider. */
@FunctionalInterface
public interface InfrastructureResourceProviderRegistration extends AutoCloseable {
    @Override
    void close();
}
