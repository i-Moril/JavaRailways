package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.plugin.Plugin
import work.art1st.javarailways.bukkit.api.simulation.SimulationFeaturePreparer
import work.art1st.javarailways.bukkit.api.simulation.SimulationFeatureService
import work.art1st.javarailways.simpleato.api.simulation.PreparedSimulationFeature
import java.util.UUID

internal class DefaultSimulationFeatureService : SimulationFeatureService, AutoCloseable {
    private class Registration(val owner: Plugin, val preparer: SimulationFeaturePreparer)
    private val lock = Any()
    private val registrations = linkedMapOf<String, Registration>()
    private var closed = false

    override fun register(owner: Plugin, preparer: SimulationFeaturePreparer): AutoCloseable {
        check(owner.isEnabled) { "Cannot register simulation feature for disabled plugin ${owner.name}" }
        val id = owner.name.lowercase()
        val registration = Registration(owner, preparer)
        synchronized(lock) {
            check(!closed) { "SimulationFeatureService is closed" }
            check(registrations.putIfAbsent(id, registration) == null) { "Simulation feature already registered: $id" }
        }
        return AutoCloseable { synchronized(lock) { registrations.remove(id, registration) } }
    }

    /** Caller holds the actual source world owner/frame boundary; no callback runs under this directory lock. */
    fun prepare(worldId: UUID): List<PreparedSimulationFeature> {
        val captured = synchronized(lock) {
            check(!closed) { "SimulationFeatureService is closed" }
            registrations.toList()
        }
        return captured.map { (id, registration) ->
            check(registration.owner.isEnabled) { "Simulation feature owner is disabled: $id" }
            registration.preparer.prepare(worldId).also {
                require(it.id == id) { "Simulation feature ID ${it.id} does not match owner $id" }
            }
        }
    }

    fun unregisterOwner(owner: Plugin) = synchronized(lock) {
        registrations.values.removeIf { it.owner === owner }
        Unit
    }

    override fun close() = synchronized(lock) {
        closed = true
        registrations.clear()
    }
}
